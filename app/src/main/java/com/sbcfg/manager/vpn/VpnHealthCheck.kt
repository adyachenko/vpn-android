package com.sbcfg.manager.vpn

import android.net.ConnectivityManager
import com.sbcfg.manager.util.AppLog
import com.sbcfg.manager.util.HealthMetrics
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watchdog that verifies both that the sing-box engine is alive AND that the
 * tunnel can actually route traffic, using sing-box's own urltest results as
 * the source of truth for tunnel health.
 *
 * Topology (must match sing-box-proxy client-template):
 *   proxy-select (selector, default=proxy-auto) ┐
 *     ├─ proxy-auto (urltest, tolerance=600)    │  all traffic enters here
 *     ├─ hysteria2-out                          │  via route.final / dns detour
 *     └─ naive-out                              ┘
 *
 * - `proxy-auto` measures delays for both outbounds. High tolerance keeps
 *   urltest from flipping on small latency changes.
 * - `proxy-select` is a selector so we can *force* Hysteria2 the moment it
 *   recovers, instead of waiting up to a full urltest cycle.
 *
 * Per-probe logic:
 *   Hysteria healthy   → force selector to hysteria2-out (if not already)
 *   Hysteria timing out, Naive healthy, selector stuck on hysteria2-out
 *                      → revert selector to proxy-auto (urltest picks naive)
 *   both timing out    → existing onConnectivityLost → full restart
 *
 * End-to-end DNS probe (since v1.2.17):
 * urltest alone misses a class of failure where sing-box's internal DNS
 * server on 172.19.0.2:53 silently stops answering queries while the
 * outbound itself is still healthy — apps then get UnknownHostException on
 * every request. We send a real DNS query through tun to the in-tunnel DNS
 * address and treat two consecutive no-replies as onConnectivityLost.
 * This became possible after we stopped calling
 * addDisallowedApplication(packageName); app HTTP clients now bypass the
 * tunnel per-socket via ProtectedSocketFactory, leaving probe sockets free
 * to actually go through tun.
 */
class VpnHealthCheck(
    @Suppress("unused") private val connectivityManager: ConnectivityManager,
    private val onUnhealthy: () -> Unit,
    private val onConnectivityLost: () -> Unit
) {
    companion object {
        private const val TAG = "VpnHealthCheck"
        // Regular interval when everything is healthy — probes are not free
        // (each one opens a gRPC stream and waits for a state push) so we
        // don't want to hammer libbox.
        private const val CHECK_INTERVAL_MS = 30_000L
        // Tight interval once we've seen a failure. Lets us either confirm a
        // real outage within a few seconds (restart) or recover quickly from
        // a single transient glitch.
        private const val RETRY_INTERVAL_MS = 5_000L
        // First probe delay. urltest interval is 1m, so delays are populated
        // within ~10s of engine start; 30s gives a safety margin.
        private const val INITIAL_DELAY_MS = 30_000L
        // Short wait after CommandClient.connect() so libbox has time to push
        // initial group state via writeGroups() before we disconnect.
        private const val GROUP_STATE_WAIT_MS = 600L
        private const val MAX_FAILURES = 2
        private const val MAX_TUNNEL_FAILURES = 2
        // Must stay in sync with sing-box-proxy client-template.json.
        private const val PROXY_GROUP_TAG = "proxy-select"
        private const val PROXY_AUTO_TAG = "proxy-auto"
        private const val HYSTERIA_TAG = "hysteria2-out"
        private const val NETWORK_CHANGE_CHECK_DELAY_MS = 10_000L
        // After a screen-off, apps' TCP connections die and Hysteria2's QUIC
        // session outlives its NAT binding on the path (home router / carrier
        // CGNAT). A probe can wrongly report the tunnel healthy (the probe
        // stream re-opens the NAT binding), while real traffic fails with
        // ERR_CONNECTION_RESET. Only a full VPN restart rebinds sockets and
        // tears down the stale QUIC session. 15s covers mobile CGNAT / Doze,
        // which rip TCP sockets well before the typical 30–60s UDP timeout.
        // Shorter risks unnecessary restarts on quick screen-peek patterns.
        private const val WAKE_RESET_THRESHOLD_MS = 15_000L

        // --- TX/RX stall detector (active between probes) ---
        // How often libbox pushes StatusMessage to our persistent Status
        // subscriber. 1s matches sfa's dashboard and gives us sub-second
        // granularity for pattern detection without hammering the server.
        private const val STATUS_PUSH_INTERVAL_MS = 1_000L
        // Rolling window (in StatusMessage ticks ≈ seconds) for the stall
        // pattern. 3 ticks ≈ 3s — enough to see sustained retries from real
        // apps (Chrome, Telegram) against a black-holed tunnel without
        // triggering on a one-off spike.
        private const val RX_WATCHER_WINDOW = 3
        // Uplink threshold (bytes) over the window that signals "apps are
        // actively trying to push data". Below this we assume idle and skip
        // the detector — RX=0 on idle is expected, not a stall.
        private const val RX_WATCHER_TX_THRESHOLD = 10_240L
        // Downlink ceiling (bytes) over the window. QUIC keepalives/ACKs to
        // Hysteria2 are tens of bytes; 512B keeps the floor above that but
        // well below any real HTTP response payload.
        private const val RX_WATCHER_RX_THRESHOLD = 512L
        // Consecutive suspicious windows before we declare DEAD. Three
        // windows = ~9s of continuous "tx without rx" — matches the typical
        // TCP retransmit cadence, and catches the Chrome ERR_CONNECTION_*
        // wave before the user's retry limit.
        private const val RX_WATCHER_CONFIRM_TICKS = 3
        // Throttle: minimum gap between stall-triggered restarts. Same value
        // as BoxService.MIN_RESTART_INTERVAL_MS — one dead-tunnel restart
        // per minute is plenty; more than that means something else is wrong
        // and hammering restarts only makes it worse.
        private const val MIN_STALL_RESTART_INTERVAL_MS = 60_000L
        // Status subscription retry delay after a failure (e.g. engine not
        // ready, socket closed). Matches RETRY_INTERVAL_MS for symmetry.
        private const val STATUS_RETRY_DELAY_MS = 5_000L
        // Drop StatusMessage pushes arriving faster than this. Libbox
        // drains its buffered Status events all at once on closeService()
        // (observed 17 pushes in 1ms during a stop), which would otherwise
        // stack into a fake DEAD detect. Half the push interval leaves
        // plenty of room for real jitter while cutting the burst to 1 tick.
        private const val STATUS_MIN_GAP_MS = STATUS_PUSH_INTERVAL_MS / 2
        // Log one rx-watcher line every N non-idle ticks (or immediately on
        // state change). Without this, 500-entry AppLog buffer holds ~30s
        // of history — not enough to calibrate or diagnose after the fact.
        private const val RX_WATCHER_LOG_EVERY = 5

        // --- DNS probe (since v1.2.17) ---
        // In-tunnel DNS server address assigned by sing-box to the tun peer.
        // Must match the `address` range the engine publishes (172.19.0.0/30;
        // .1 is the device side, .2 is the sing-box DNS endpoint). Changing
        // the subnet in server config requires updating this constant.
        private const val DNS_PROBE_ADDR = "172.19.0.2"
        private const val DNS_PROBE_PORT = 53
        // Domain we ask about. Any short valid label works — we don't parse
        // the answer, we only care whether libbox replied at all. Using a
        // host that's guaranteed to resolve (google.com) keeps the reply tiny
        // and caches on the DNS side, avoiding outbound traffic for repeat
        // probes.
        private const val DNS_PROBE_HOST = "google.com"
        // UDP recv timeout for a single probe. Local libbox DNS replies land
        // in <50ms when healthy; 2s catches both socket-level stalls and
        // cases where libbox is forwarding upstream and the path is slow.
        private const val DNS_PROBE_TIMEOUT_MS = 2_000
        // Consecutive DNS failures before declaring dead. Same cadence as
        // tunnel-failure counter so the total-time-to-restart (~10s with
        // RETRY_INTERVAL_MS) matches — no point making one signal slower
        // than the others.
        private const val MAX_DNS_FAILURES = 2
    }

    private var job: Job? = null
    private var networkCheckJob: Job? = null
    private var statusJob: Job? = null
    private var statusClient: CommandClient? = null
    private var tunnelFailures = 0
    private var dnsFailures = 0

    @Volatile
    private var lastStallRestartAt: Long = 0L

    @Volatile
    private var lastSelectorSelected: String? = null

    @Volatile
    private var lastScreenOffAt: Long = 0L

    // Shared state so out-of-band probes (screen-on, network change) can
    // participate in the same failure counter as the main loop.
    @Volatile
    private var failures = 0

    // Signals the main loop to wake up immediately instead of sleeping out
    // its current delay — used after an out-of-band probe so the next tick
    // lands in line with the adaptive interval policy.
    @Volatile
    private var wakeSignal: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun start(scope: CoroutineScope) {
        stop()
        failures = 0
        job = scope.launch(Dispatchers.IO) {
            AppLog.i(TAG, "Health check started (interval=${CHECK_INTERVAL_MS}ms, retry=${RETRY_INTERVAL_MS}ms, threshold=$MAX_FAILURES)")
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                handleProbeResult(probe())
                val nextDelay = if (failures > 0 || tunnelFailures > 0 || dnsFailures > 0) RETRY_INTERVAL_MS else CHECK_INTERVAL_MS
                val signal = kotlinx.coroutines.CompletableDeferred<Unit>()
                wakeSignal = signal
                try {
                    kotlinx.coroutines.withTimeoutOrNull(nextDelay) { signal.await() }
                } finally {
                    wakeSignal = null
                }
            }
        }
        // Persistent Status subscriber: independent channel that feeds the
        // TX/RX stall detector between probe() ticks. Not part of probe()
        // because probes are short-lived (600ms) and we need continuous
        // traffic-counter deltas to catch a black-holed tunnel while apps
        // are actively retrying — the scenario probe() itself cannot see.
        statusJob = scope.launch(Dispatchers.IO) {
            // Give libbox the same warm-up window as the main loop so we
            // don't hammer an engine that's still binding its sockets.
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                val detector = TunnelStallDetector()
                val options = CommandClientOptions().apply {
                    addCommand(Libbox.CommandStatus)
                    statusInterval = STATUS_PUSH_INTERVAL_MS
                }
                val client = CommandClient(detector, options)
                try {
                    client.connect()
                    statusClient = client
                    AppLog.i(TAG, "Status subscription connected (interval=${STATUS_PUSH_INTERVAL_MS}ms)")
                    // Block until this scope is cancelled — the handler
                    // pushes updates on the libbox push thread.
                    awaitCancellation()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w(TAG, "Status subscription failed: ${e.message}")
                    delay(STATUS_RETRY_DELAY_MS)
                } finally {
                    statusClient = null
                    runCatching { client.disconnect() }
                }
            }
        }
    }

    private fun handleProbeResult(result: ProbeResult) {
        when {
            !result.libboxAlive -> {
                tunnelFailures = 0
                if (result.socketMissing) {
                    // Command socket file is gone — CommandServer is
                    // definitively dead. Skip retry loop, restart now.
                    AppLog.e(TAG, "Command socket missing — triggering VPN restart immediately")
                    failures = 0
                    runCatching { onUnhealthy() }
                        .onFailure { AppLog.e(TAG, "onUnhealthy callback failed", it) }
                } else {
                    failures++
                    AppLog.w(TAG, "Libbox ping failed ($failures/$MAX_FAILURES)")
                    if (failures >= MAX_FAILURES) {
                        AppLog.e(TAG, "Libbox unresponsive — triggering VPN restart")
                        runCatching { onUnhealthy() }
                            .onFailure { AppLog.e(TAG, "onUnhealthy callback failed", it) }
                        failures = 0
                    }
                }
            }
            !result.outboundHealthy -> {
                failures = 0
                dnsFailures = 0
                tunnelFailures++
                AppLog.w(TAG, "All proxy outbounds timing out ($tunnelFailures/$MAX_TUNNEL_FAILURES)")
                if (tunnelFailures >= MAX_TUNNEL_FAILURES) {
                    AppLog.e(TAG, "Tunnel dead — triggering VPN restart")
                    tunnelFailures = 0
                    runCatching { onConnectivityLost() }
                        .onFailure { AppLog.e(TAG, "onConnectivityLost callback failed", it) }
                }
            }
            !result.dnsAlive -> {
                // libbox ping passes, urltest passes, but the in-tunnel DNS
                // handler silently stopped replying. Apps see
                // UnknownHostException on every query. Only a full VPN
                // restart is known to recover this — confirmed in prod on
                // v1.2.16 (issue #001). Two consecutive silent DNS probes
                // are enough: the handler either replies in <50ms or not
                // at all, there's no transient middle ground.
                failures = 0
                tunnelFailures = 0
                dnsFailures++
                AppLog.w(TAG, "In-tunnel DNS silent ($dnsFailures/$MAX_DNS_FAILURES)")
                if (dnsFailures >= MAX_DNS_FAILURES) {
                    AppLog.e(TAG, "DNS handler dead — triggering VPN restart")
                    dnsFailures = 0
                    runCatching { onConnectivityLost() }
                        .onFailure { AppLog.e(TAG, "onConnectivityLost callback failed", it) }
                }
            }
            else -> {
                if (failures > 0 || tunnelFailures > 0 || dnsFailures > 0) {
                    AppLog.i(TAG, "Tunnel recovered")
                }
                failures = 0
                tunnelFailures = 0
                dnsFailures = 0
            }
        }
    }

    /**
     * Called after a network change so we re-probe sooner than the regular
     * interval — lets us catch a dead tunnel before the user notices.
     */
    fun onNetworkChanged(scope: CoroutineScope) {
        networkCheckJob?.cancel()
        networkCheckJob = scope.launch(Dispatchers.IO) {
            delay(NETWORK_CHANGE_CHECK_DELAY_MS)
            AppLog.i(TAG, "Post-network-change probe")
            val result = probe()
            if (result.libboxAlive && !result.outboundHealthy) {
                AppLog.e(TAG, "Post-network-change: outbounds still timing out — restarting VPN")
                tunnelFailures = 0
                runCatching { onConnectivityLost() }
                    .onFailure { AppLog.e(TAG, "onConnectivityLost callback failed", it) }
            }
        }
    }

    /**
     * Called on screen-off. We record the timestamp so onWakeFromSleep can
     * decide whether the screen was off long enough for the QUIC NAT binding
     * to matter (WAKE_RESET_THRESHOLD_MS).
     */
    fun onScreenOff() {
        lastScreenOffAt = System.currentTimeMillis()
    }

    /**
     * Called on screen-on. Two-branch policy:
     *
     * - Short sleep (< WAKE_RESET_THRESHOLD_MS): force an immediate probe.
     *   Catches a broken tunnel the moment the user looks at the phone,
     *   instead of waiting up to CHECK_INTERVAL_MS for the next tick. Feeds
     *   the same failure counter as the main loop, so a failure flips the
     *   loop into RETRY_INTERVAL_MS cadence.
     *
     * - Long sleep (>= WAKE_RESET_THRESHOLD_MS): full VPN restart, skipping
     *   the probe entirely. After ~minute of silence the Hysteria2 QUIC
     *   session has outlived its NAT binding on the path, and apps' TCP
     *   connections have all died. A probe would wrongly report healthy
     *   (its own stream re-opens the binding) while real traffic fails —
     *   only a full stop→start rebinds sockets and tears down stale QUIC.
     */
    fun onWakeFromSleep(scope: CoroutineScope) {
        if (lastSelectorSelected == null) return

        val off = lastScreenOffAt
        val sleepMs = if (off > 0L) System.currentTimeMillis() - off else 0L

        networkCheckJob?.cancel()
        networkCheckJob = scope.launch(Dispatchers.IO) {
            if (sleepMs >= WAKE_RESET_THRESHOLD_MS) {
                AppLog.i(TAG, "Screen on after ${sleepMs}ms — restarting VPN to drop stale QUIC/TCP")
                tunnelFailures = 0
                runCatching { onConnectivityLost() }
                    .onFailure { AppLog.e(TAG, "onConnectivityLost callback failed", it) }
                return@launch
            }
            AppLog.i(TAG, "Screen on after ${sleepMs}ms — forcing health check")
            handleProbeResult(probe())
            // Wake the main loop so the next tick lands at the adaptive
            // interval (5s if this probe failed, 30s otherwise), not at
            // whatever the previous schedule had queued.
            wakeSignal?.complete(Unit)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        networkCheckJob?.cancel()
        networkCheckJob = null
        statusJob?.cancel()
        statusJob = null
        runCatching { statusClient?.disconnect() }
        statusClient = null
        tunnelFailures = 0
        dnsFailures = 0
        failures = 0
        lastScreenOffAt = 0L
        lastStallRestartAt = 0L
        HealthMetrics.clear()
    }

    private data class ProbeResult(
        val libboxAlive: Boolean,
        val outboundHealthy: Boolean,
        /**
         * True when the probe failed because the unix socket file itself
         * does not exist. That's an unambiguous sign the CommandServer
         * goroutine is dead — no point retrying, trigger a full restart
         * immediately.
         */
        val socketMissing: Boolean = false,
        /**
         * True when a real DNS query to the in-tunnel DNS address got a
         * reply within timeout. False means sing-box's DNS handler stopped
         * answering (known transient failure mode) — apps would be
         * getting UnknownHostException even though libbox ping and outbound
         * urltest both report healthy.
         *
         * Defaults to true so the early "libbox dead" / "no data yet" exit
         * paths don't need to set it.
         */
        val dnsAlive: Boolean = true,
    )

    /**
     * Connects to libbox's CommandServer over the unix socket, waits briefly
     * for state push, then reads both the selector's current choice and the
     * urltest group's measured delays. Applies the Hysteria-preference policy.
     */
    private fun probe(): ProbeResult {
        var client: CommandClient? = null
        val handler = GroupAwareHandler()
        var socketMissing = false
        // Force a fresh URL-test in the background before we read delays.
        // Without this, the group values we read below are whatever the
        // internal urltest interval last measured — potentially 60s stale
        // and wrong if the network path changed during that minute.
        // urlTest() is asynchronous: it kicks off the HTTP probe through
        // each outbound and the updated delays land in writeGroups shortly
        // after (covered by GROUP_STATE_WAIT_MS below).
        runCatching { Libbox.newStandaloneCommandClient().urlTest(PROXY_AUTO_TAG) }
            .onFailure { AppLog.w(TAG, "urlTest($PROXY_AUTO_TAG) failed: ${it.message}") }
        val libboxAlive = try {
            val options = CommandClientOptions().apply {
                // Subscribe to Group pushes — without this, writeGroups is never
                // called and we can't read urltest delays. StatusInterval alone
                // (used historically) only enables CommandStatus traffic stats.
                addCommand(Libbox.CommandGroup)
                statusInterval = CHECK_INTERVAL_MS
            }
            client = CommandClient(handler, options)
            client.connect()
            Thread.sleep(GROUP_STATE_WAIT_MS)
            true
        } catch (e: Exception) {
            val msg = e.message ?: ""
            // gRPC reports `connect: no such file or directory` when the unix
            // socket file is gone — CommandServer goroutine is dead and a
            // reload won't bring it back. Only a full VPN restart recreates
            // the listener, so fail fast instead of waiting MAX_FAILURES.
            socketMissing = msg.contains("no such file or directory")
            AppLog.w(TAG, "Ping failed: $msg")
            false
        } finally {
            try { client?.disconnect() } catch (_: Exception) {}
        }

        if (!libboxAlive) return ProbeResult(
            libboxAlive = false,
            outboundHealthy = false,
            socketMissing = socketMissing
        )

        val selected = handler.selectorSelected
        if (selected != null) {
            val prev = lastSelectorSelected
            if (prev != null && prev != selected) {
                AppLog.i(TAG, "Selector switched: $prev -> $selected")
            }
            lastSelectorSelected = selected
        }

        val auto = handler.autoState
        if (auto == null) {
            // No urltest data yet — short window after engine start.
            AppLog.i(TAG, "No proxy-auto state yet — assuming healthy")
            return ProbeResult(libboxAlive = true, outboundHealthy = true)
        }

        AppLog.i(TAG, "Proxy outbounds: ${auto.description} (selector=$selected)")

        HealthMetrics.updateProbe(
            HealthMetrics.ProbeSummary(
                selectorSelected = selected,
                hysteriaDelayMs = auto.hysteriaDelay,
                naiveDelayMs = auto.naiveDelay,
                timestampMs = System.currentTimeMillis(),
            )
        )

        applyPreferencePolicy(selected, auto)

        val dnsAlive = probeDns()

        return ProbeResult(
            libboxAlive = true,
            outboundHealthy = auto.hasHealthy,
            dnsAlive = dnsAlive,
        )
    }

    /**
     * Sends a real UDP DNS query to sing-box's in-tunnel DNS address and
     * returns whether it got any reply within [DNS_PROBE_TIMEOUT_MS].
     *
     * We don't parse the response — we only care whether the handler is
     * alive enough to emit bytes back. A correct reply has the same query
     * id in the first 2 bytes, which is our only validation.
     *
     * The socket is **not** protected, so Android's per-UID routing sends
     * it via tun (our own package is in the allow-list now, see openTun()).
     * That's exactly what we want: this call exercises the same path an
     * app's DNS query takes.
     */
    private fun probeDns(): Boolean {
        // If VPN isn't actually running from our perspective, skip — the
        // probe socket would either fail to route or leak onto wlan0.
        if (VpnServiceHolder.get() == null) return true

        val socket = try {
            java.net.DatagramSocket()
        } catch (e: Exception) {
            AppLog.w(TAG, "DNS probe: socket() failed: ${e.message}")
            return false
        }
        return try {
            socket.soTimeout = DNS_PROBE_TIMEOUT_MS
            val query = buildDnsQuery(DNS_PROBE_HOST)
            val addr = java.net.InetAddress.getByName(DNS_PROBE_ADDR)
            socket.send(java.net.DatagramPacket(query, query.size, addr, DNS_PROBE_PORT))
            val buf = ByteArray(512)
            val packet = java.net.DatagramPacket(buf, buf.size)
            socket.receive(packet)
            // First 2 bytes are the transaction id we set. Match ensures we
            // didn't pick up some unrelated datagram that happened to land
            // on the same port.
            val ok = packet.length >= 2 &&
                packet.data[0] == query[0] &&
                packet.data[1] == query[1]
            if (!ok) AppLog.w(TAG, "DNS probe: reply id mismatch (len=${packet.length})")
            ok
        } catch (e: Exception) {
            AppLog.w(TAG, "DNS probe to $DNS_PROBE_ADDR:$DNS_PROBE_PORT failed: ${e.message}")
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun buildDnsQuery(host: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(out)
        val id = (System.nanoTime() and 0xFFFF).toInt()
        dos.writeShort(id)
        dos.writeShort(0x0100) // standard query, recursion desired
        dos.writeShort(1)      // qdcount
        dos.writeShort(0)      // ancount
        dos.writeShort(0)      // nscount
        dos.writeShort(0)      // arcount
        for (label in host.split('.')) {
            dos.writeByte(label.length)
            dos.write(label.toByteArray(Charsets.US_ASCII))
        }
        dos.writeByte(0)       // root
        dos.writeShort(1)      // QTYPE = A
        dos.writeShort(1)      // QCLASS = IN
        return out.toByteArray()
    }

    /**
     * Enforces "Hysteria2 first" at the selector level, bypassing urltest's
     * tolerance for recovery events. urltest with tolerance=600 keeps us
     * stable on Naive once the switch happens; this brings us back as soon
     * as Hysteria2's probe succeeds.
     */
    private fun applyPreferencePolicy(selected: String?, auto: ProxyAutoState) {
        selected ?: return
        when {
            auto.hysteriaDelay > 0 && selected != HYSTERIA_TAG -> {
                AppLog.i(TAG, "Hysteria healthy (${auto.hysteriaDelay}ms) — forcing selector to $HYSTERIA_TAG")
                forceSelect(HYSTERIA_TAG)
            }
            auto.hysteriaDelay == 0 && auto.naiveDelay > 0 && selected == HYSTERIA_TAG -> {
                // We previously forced Hysteria; it died. Hand control back to
                // urltest so it picks Naive (the only live outbound).
                AppLog.i(TAG, "Hysteria down, Naive live — reverting selector to $PROXY_AUTO_TAG")
                forceSelect(PROXY_AUTO_TAG)
            }
        }
    }

    private fun forceSelect(outboundTag: String) {
        try {
            Libbox.newStandaloneCommandClient().selectOutbound(PROXY_GROUP_TAG, outboundTag)
        } catch (e: Exception) {
            AppLog.w(TAG, "selectOutbound($PROXY_GROUP_TAG, $outboundTag) failed: ${e.message}")
        }
    }

    private data class ProxyAutoState(
        val hysteriaDelay: Int,
        val naiveDelay: Int,
        val hasHealthy: Boolean,
        val description: String
    )

    private inner class GroupAwareHandler : CommandClientHandler {
        @Volatile
        var selectorSelected: String? = null

        @Volatile
        var autoState: ProxyAutoState? = null

        override fun writeGroups(groups: OutboundGroupIterator?) {
            if (groups == null) return
            while (groups.hasNext()) {
                val group = groups.next()
                when (group.tag) {
                    PROXY_GROUP_TAG -> if (group.type == "selector") {
                        selectorSelected = group.selected
                    }
                    PROXY_AUTO_TAG -> if (group.type == "urltest") {
                        var hysteriaDelay = 0
                        var naiveDelay = 0
                        val items = mutableListOf<String>()
                        val itemIter = group.items
                        while (itemIter.hasNext()) {
                            val item = itemIter.next()
                            val d = item.urlTestDelay
                            if (item.tag == HYSTERIA_TAG) hysteriaDelay = d
                            else naiveDelay = d
                            items.add("${item.tag}=${if (d > 0) "${d}ms" else "timeout"}")
                        }
                        autoState = ProxyAutoState(
                            hysteriaDelay = hysteriaDelay,
                            naiveDelay = naiveDelay,
                            hasHealthy = hysteriaDelay > 0 || naiveDelay > 0,
                            description = items.joinToString()
                        )
                    }
                }
            }
        }

        override fun clearLogs() {}
        override fun connected() {}
        override fun disconnected(message: String?) {}
        override fun initializeClashMode(modes: StringIterator?, current: String?) {}
        override fun setDefaultLogLevel(level: Int) {}
        override fun updateClashMode(mode: String?) {}
        override fun writeConnectionEvents(events: ConnectionEvents?) {}
        override fun writeLogs(logs: LogIterator?) {}
        override fun writeStatus(status: StatusMessage?) {}
    }

    /**
     * Passive end-to-end tunnel health detector. Runs as a long-lived
     * CommandStatus subscriber (not part of probe()) so we see traffic
     * counters every STATUS_PUSH_INTERVAL_MS regardless of the main probe
     * cadence.
     *
     * Detects the "apps retrying into a black-holed tunnel" pattern:
     * uplink is climbing (TCP retransmits from browsers / push clients),
     * but downlink stays flat (no answers from the server). This is the
     * exact symptom of the short-sleep QUIC-NAT-lie that probe() cannot
     * catch — probe()'s own stream re-opens the NAT binding and reports
     * "healthy" while real application sockets are dead.
     *
     * Trigger conditions (RX_WATCHER_CONFIRM_TICKS consecutive windows):
     *   sum(dtx over window) > RX_WATCHER_TX_THRESHOLD  (10 KB)
     *   sum(drx over window) < RX_WATCHER_RX_THRESHOLD  (512 B)
     *
     * Downlink threshold sits above QUIC keepalive/ACK noise but well
     * below any real HTTP response, so idle periods (no tx either) don't
     * false-positive.
     */
    private inner class TunnelStallDetector : CommandClientHandler {
        private var prevUpTotal = -1L
        private var prevDownTotal = -1L
        private val window = ArrayDeque<Pair<Long, Long>>()
        private var consecutiveSuspicious = 0
        private var lastTickAtMs = 0L
        private var lastLoggedState: HealthMetrics.StallState? = null
        private var logCounter = 0

        override fun writeStatus(status: StatusMessage?) {
            if (status == null) return
            val now = System.currentTimeMillis()
            // Drop burst pushes (engine shutdown drain, etc.). Real pushes
            // come at STATUS_PUSH_INTERVAL_MS; anything closer is libbox
            // flushing its buffer and would stack our window into a fake
            // DEAD within milliseconds.
            if (lastTickAtMs > 0L && now - lastTickAtMs < STATUS_MIN_GAP_MS) return
            lastTickAtMs = now

            val upTotal = status.uplinkTotal
            val downTotal = status.downlinkTotal

            // First tick — seed the baseline. No delta possible.
            if (prevUpTotal < 0L) {
                prevUpTotal = upTotal
                prevDownTotal = downTotal
                publish(status, HealthMetrics.StallState.HEALTHY, 0)
                return
            }

            // coerceAtLeast(0) guards against libbox's counters resetting
            // on an internal reload, which would otherwise produce a giant
            // negative delta and break the window sums.
            val dtx = (upTotal - prevUpTotal).coerceAtLeast(0L)
            val drx = (downTotal - prevDownTotal).coerceAtLeast(0L)
            prevUpTotal = upTotal
            prevDownTotal = downTotal

            window.addLast(dtx to drx)
            while (window.size > RX_WATCHER_WINDOW) window.removeFirst()

            val sumTx = window.sumOf { it.first }
            val sumRx = window.sumOf { it.second }
            val windowFull = window.size >= RX_WATCHER_WINDOW
            val isSuspicious =
                windowFull && sumTx > RX_WATCHER_TX_THRESHOLD && sumRx < RX_WATCHER_RX_THRESHOLD
            consecutiveSuspicious = if (isSuspicious) consecutiveSuspicious + 1 else 0

            val state = when {
                consecutiveSuspicious >= RX_WATCHER_CONFIRM_TICKS -> HealthMetrics.StallState.DEAD
                isSuspicious -> HealthMetrics.StallState.SUSPICIOUS
                else -> HealthMetrics.StallState.HEALTHY
            }

            publish(status, state, consecutiveSuspicious)

            // Log sparingly: always on state change (that's the signal
            // we care about), otherwise one line per RX_WATCHER_LOG_EVERY
            // non-idle ticks. Full per-second deltas still live in the
            // HealthMetrics history ring and show up in the export.
            val stateChanged = state != lastLoggedState
            val hasTraffic = dtx > 0 || drx > 0
            if (hasTraffic) logCounter++
            if (stateChanged || (hasTraffic && logCounter % RX_WATCHER_LOG_EVERY == 0)) {
                AppLog.i(
                    TAG,
                    "rx-watcher: dtx=${dtx}B drx=${drx}B " +
                            "sumTx=${sumTx}B sumRx=${sumRx}B " +
                            "up=${status.uplink}Bps down=${status.downlink}Bps " +
                            "state=$state susp=$consecutiveSuspicious",
                )
                lastLoggedState = state
            }

            if (state == HealthMetrics.StallState.DEAD) {
                val now = System.currentTimeMillis()
                if (now - lastStallRestartAt < MIN_STALL_RESTART_INTERVAL_MS) {
                    return
                }
                lastStallRestartAt = now
                AppLog.e(
                    TAG,
                    "rx-watcher DETECT: tunnel stalled " +
                            "(tx=${sumTx}B, rx=${sumRx}B over ${window.size} ticks) — restarting",
                )
                // Reset the local window so we don't re-trigger until we've
                // seen fresh data through the new tunnel.
                window.clear()
                consecutiveSuspicious = 0
                runCatching { onConnectivityLost() }
                    .onFailure { AppLog.e(TAG, "rx-watcher onConnectivityLost failed", it) }
            }
        }

        private fun publish(
            status: StatusMessage,
            state: HealthMetrics.StallState,
            susp: Int,
        ) {
            HealthMetrics.update(
                HealthMetrics.RxSnapshot(
                    uplinkBps = status.uplink,
                    downlinkBps = status.downlink,
                    uplinkTotal = status.uplinkTotal,
                    downlinkTotal = status.downlinkTotal,
                    stallState = state,
                    suspiciousCount = susp,
                    timestampMs = System.currentTimeMillis(),
                )
            )
        }

        override fun clearLogs() {}
        override fun connected() {}
        override fun disconnected(message: String?) {}
        override fun initializeClashMode(modes: StringIterator?, current: String?) {}
        override fun setDefaultLogLevel(level: Int) {}
        override fun updateClashMode(mode: String?) {}
        override fun writeConnectionEvents(events: ConnectionEvents?) {}
        override fun writeLogs(logs: LogIterator?) {}
        override fun writeGroups(groups: OutboundGroupIterator?) {}
    }
}
