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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
 * Why not a direct socket probe?
 * The VPN-owning app is excluded from its own tunnel via
 * addDisallowedApplication(packageName) — that prevents config-fetch loops
 * and keeps startup traffic (WorkManager, Hilt, DNS lookups) off the tunnel.
 * That exclusion also makes Network.bindSocket() to the VPN network fail
 * with EPERM, so we can't send a test packet through tun from this process.
 * urltest delays measured by sing-box itself are the source of truth.
 *
 * DNS-handler silent failure (issue #001) is NOT caught by the current
 * signals. See probeDns() — kept as a stub for a future detector that will
 * likely bind via ConnectivityManager.getNetworkForType(TYPE_VPN).
 */
class VpnHealthCheck(
    @Suppress("unused") private val connectivityManager: ConnectivityManager,
    private val onUnhealthy: () -> Unit,
    private val onConnectivityLost: (force: Boolean) -> Unit
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
        private const val WAKE_RESTART_COOLDOWN_MS = 120_000L

        // --- Screen-off battery optimization ---
        // Probe interval when screen is off — no user is looking, so slow
        // cadence saves battery while still catching dead engines.
        private const val SCREEN_OFF_CHECK_INTERVAL_MS = 120_000L
        // Delay before disconnecting persistent detectors on screen-off.
        // Prevents disconnect/reconnect churn on quick screen peeks.
        private const val SCREEN_OFF_DETECTOR_DELAY_MS = 5_000L

        // --- TX/RX stall detector (active between probes) ---
        // How often libbox pushes StatusMessage to our persistent Status
        // subscriber. 1s matches sfa's dashboard and gives us sub-second
        // granularity for pattern detection without hammering the server.
        private const val STATUS_PUSH_INTERVAL_MS = 1_000L
        // Per-tick uplink threshold (bytes) for a tick to be flagged
        // "suspicious". Below this we treat the tick as idle (keepalives,
        // ACKs) regardless of rx. 1KB sits above QUIC/TCP keepalive noise
        // but well below any real request body.
        private const val RX_WATCHER_TX_THRESHOLD = 1_024L
        // Consecutive ticks with (dtx >= TX_THRESHOLD && drx == 0) before
        // we declare DEAD. 5 ticks ≈ 5s of absolute downlink silence under
        // active uplink — the signature of a black-holed tunnel (firewall
        // dropping QUIC after wake/handover). On normal usage even a slow
        // server replies with TCP ACKs / QUIC ACKs (single-digit bytes) so
        // any rx > 0 within 5s resets the counter.
        private const val RX_WATCHER_CONFIRM_TICKS = 5
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

        // --- Connection failure detector (since v1.2.19) ---
        // Monitors ConnectionEvents from libbox to detect intermittent QUIC
        // degradation: the session is half-alive (urltest passes, DNS works)
        // but real TCP streams get "connection refused" randomly.
        // Sliding window for counting failures.
        private const val CONN_WINDOW_MS = 30_000L
        // Minimum failed TCP connections (0 downlink, lifetime < 120s) in the
        // window before triggering restart. 5 is high enough that a single
        // broken CDN won't fire it, but low enough to catch the burst of
        // refused connections we see during QUIC degradation (~20 in 90s).
        private const val CONN_FAIL_THRESHOLD = 5
        // Max connection lifetime to count as "failed". Longer-lived idle
        // connections that close with 0 downlink are not failures.
        private const val CONN_FAIL_MAX_LIFETIME_MS = 120_000L
        // Only count connections through these outbounds.
        private val PROXY_OUTBOUNDS = setOf("hysteria2-out", "naive-out")
    }

    private var job: Job? = null
    private var networkCheckJob: Job? = null
    private var detectorJob: Job? = null
    private var detectorClient: CommandClient? = null
    private var screenOffJob: Job? = null
    @Volatile
    private var screenOn: Boolean = true
    private var activeScope: CoroutineScope? = null
    private var tunnelFailures = 0
    private var dnsFailures = 0

    @Volatile
    private var lastStallRestartAt: Long = 0L
    @Volatile
    private var lastRestartRequestAt: Long = 0L

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
    private var wakeSignal: CompletableDeferred<Unit>? = null

    fun start(scope: CoroutineScope) {
        stop()
        failures = 0
        screenOn = true
        activeScope = scope
        job = scope.launch(Dispatchers.IO) {
            AppLog.i(TAG, "Health check started (interval=${CHECK_INTERVAL_MS}ms, retry=${RETRY_INTERVAL_MS}ms, threshold=$MAX_FAILURES)")
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                handleProbeResult(probe())
                val hasFailures = failures > 0 || tunnelFailures > 0 || dnsFailures > 0
                val nextDelay = when {
                    hasFailures -> RETRY_INTERVAL_MS
                    !screenOn -> SCREEN_OFF_CHECK_INTERVAL_MS
                    else -> CHECK_INTERVAL_MS
                }
                val signal = CompletableDeferred<Unit>()
                wakeSignal = signal
                try {
                    withTimeoutOrNull(nextDelay) { signal.await() }
                } finally {
                    wakeSignal = null
                }
            }
        }
        startDetector(scope, initialDelay = true)
    }

    /**
     * Launches a single persistent CommandClient subscribed to both
     * CommandStatus (TX/RX stall detection) and CommandConnections
     * (connection failure detection). One socket instead of two cuts
     * wake events in half; pausing on screen-off eliminates them entirely.
     */
    private fun startDetector(scope: CoroutineScope, initialDelay: Boolean) {
        stopDetector()
        detectorJob = scope.launch(Dispatchers.IO) {
            if (initialDelay) delay(INITIAL_DELAY_MS)
            while (isActive) {
                val stallDetector = TunnelStallDetector()
                val connDetector = ConnectionFailureDetector()
                val handler = CombinedDetectorHandler(stallDetector, connDetector)
                val options = CommandClientOptions().apply {
                    addCommand(Libbox.CommandStatus)
                    addCommand(Libbox.CommandConnections)
                    statusInterval = STATUS_PUSH_INTERVAL_MS
                }
                val client = CommandClient(handler, options)
                try {
                    client.connect()
                    detectorClient = client
                    AppLog.i(TAG, "Combined detector connected (status+connections, interval=${STATUS_PUSH_INTERVAL_MS}ms)")
                    awaitCancellation()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w(TAG, "Combined detector failed: ${e.message}")
                    delay(STATUS_RETRY_DELAY_MS)
                } finally {
                    detectorClient = null
                    runCatching { client.disconnect() }
                }
            }
        }
    }

    private fun stopDetector() {
        detectorJob?.cancel()
        detectorJob = null
        runCatching { detectorClient?.disconnect() }
        detectorClient = null
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
                    lastRestartRequestAt = System.currentTimeMillis()
                    runCatching { onConnectivityLost(false) }
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
                    lastRestartRequestAt = System.currentTimeMillis()
                    runCatching { onConnectivityLost(false) }
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
                lastRestartRequestAt = System.currentTimeMillis()
                runCatching { onConnectivityLost(false) }
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
        screenOn = false
        val scope = activeScope ?: return
        screenOffJob?.cancel()
        screenOffJob = scope.launch(Dispatchers.IO) {
            delay(SCREEN_OFF_DETECTOR_DELAY_MS)
            AppLog.i(TAG, "Screen off — pausing detectors to save battery")
            stopDetector()
        }
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
        screenOn = true
        screenOffJob?.cancel()
        screenOffJob = null
        if (detectorJob == null || detectorJob?.isActive != true) {
            AppLog.i(TAG, "Resuming detectors after screen-on")
            startDetector(scope, initialDelay = false)
        }

        if (lastSelectorSelected == null) {
            wakeSignal?.complete(Unit)
            return
        }

        val off = lastScreenOffAt
        val sleepMs = if (off > 0L) System.currentTimeMillis() - off else 0L

        networkCheckJob?.cancel()
        networkCheckJob = scope.launch(Dispatchers.IO) {
            if (sleepMs >= WAKE_RESET_THRESHOLD_MS) {
                val sinceLastRestart = System.currentTimeMillis() - lastRestartRequestAt
                if (lastRestartRequestAt > 0 && sinceLastRestart < WAKE_RESTART_COOLDOWN_MS) {
                    AppLog.i(TAG, "Screen on after ${sleepMs}ms — skipping restart, detector restarted ${sinceLastRestart / 1000}s ago")
                    handleProbeResult(probe())
                    wakeSignal?.complete(Unit)
                    return@launch
                }
                AppLog.i(TAG, "Screen on after ${sleepMs}ms — restarting VPN to drop stale QUIC/TCP")
                tunnelFailures = 0
                lastRestartRequestAt = System.currentTimeMillis()
                runCatching { onConnectivityLost(false) }
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
        screenOffJob?.cancel()
        screenOffJob = null
        stopDetector()
        tunnelFailures = 0
        dnsFailures = 0
        failures = 0
        lastScreenOffAt = 0L
        lastStallRestartAt = 0L
        lastRestartRequestAt = 0L
        screenOn = true
        activeScope = null
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
     * DNS-handler health probe. DISABLED for now (always returns true).
     *
     * The intent was to send a real UDP DNS query to 172.19.0.2:53 and catch
     * the case where sing-box's DNS handler silently stops answering while
     * outbound urltest still reports healthy (see wiki §1.3a, issue #001).
     *
     * Problem: our process is excluded from the tunnel via
     * addDisallowedApplication(packageName). Even explicitly binding the
     * socket to the VPN Network via Network.bindSocket() fails with
     * `SocketException: Binding socket to network N failed: EPERM`
     * (empirically confirmed 2026-04-20 on v1.2.18, Android, tun0 network
     * id=364). So we cannot send a probe packet from this process, period.
     *
     * Attempting to flip the exclusion (v1.2.17 first pass) broke things
     * differently — WorkManager/Hilt/etc traffic poured into the tunnel on
     * startup and triggered the rx-watcher DEAD path within 3 seconds.
     *
     * Leaving the code structure in place (ProbeResult.dnsAlive, handleProbeResult
     * branch, counters) so wiring is done when we find a real solution —
     * the path is extending libbox with a CommandDNSTest that invokes the
     * sing-box DNS router over the unix-socket command channel (same
     * mechanism as URLTest and SelectOutbound, which already work from this
     * excluded process). Until then, returning true means the DNS branch
     * is never taken.
     */
    @Suppress("FunctionOnlyReturningConstant")
    private fun probeDns(): Boolean = true

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

    private inner class CombinedDetectorHandler(
        private val stallDetector: TunnelStallDetector,
        private val connDetector: ConnectionFailureDetector,
    ) : CommandClientHandler {
        override fun writeStatus(status: StatusMessage?) = stallDetector.writeStatus(status)
        override fun writeConnectionEvents(events: ConnectionEvents?) = connDetector.writeConnectionEvents(events)
        override fun clearLogs() {}
        override fun connected() {}
        override fun disconnected(message: String?) {}
        override fun initializeClashMode(modes: StringIterator?, current: String?) {}
        override fun setDefaultLogLevel(level: Int) {}
        override fun updateClashMode(mode: String?) {}
        override fun writeLogs(logs: LogIterator?) {}
        override fun writeGroups(groups: OutboundGroupIterator?) {}
    }

    /**
     * Passive end-to-end tunnel health detector. Runs as a long-lived
     * CommandStatus subscriber (not part of probe()) so we see traffic
     * counters every STATUS_PUSH_INTERVAL_MS regardless of the main probe
     * cadence.
     *
     * Detects the "apps retrying into a black-holed tunnel" pattern:
     * uplink keeps flowing (TCP retransmits from browsers / push clients),
     * but downlink is absolute zero. This is the exact symptom of the
     * short-sleep QUIC-NAT-lie that probe() cannot catch — probe()'s own
     * stream re-opens the NAT binding and reports "healthy" while real
     * application sockets are dead.
     *
     * Trigger condition (RX_WATCHER_CONFIRM_TICKS consecutive ticks):
     *   dtx >= RX_WATCHER_TX_THRESHOLD  AND  drx == 0
     *
     * Per-tick (not windowed): a sliding-window sum allowed false positives
     * during active video traffic (Instagram scrolling, etc.) where a real
     * pause of 1-2s between payloads bracketed by 1-2 small-rx ticks could
     * push the sum below the floor. With drx == 0 as the bad-tick criterion,
     * any reply (even a TCP ACK or QUIC ACK, typically tens of bytes) resets
     * the counter — only a truly black-holed downlink survives 5s straight.
     */
    private inner class TunnelStallDetector : CommandClientHandler {
        private var prevUpTotal = -1L
        private var prevDownTotal = -1L
        private var consecutiveBad = 0
        private var lastTickAtMs = 0L
        private var lastLoggedState: HealthMetrics.StallState? = null
        private var logCounter = 0

        override fun writeStatus(status: StatusMessage?) {
            if (status == null) return
            val now = System.currentTimeMillis()
            // Drop burst pushes (engine shutdown drain, etc.). Real pushes
            // come at STATUS_PUSH_INTERVAL_MS; anything closer is libbox
            // flushing its buffer and would stack our counter into a fake
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
            // negative delta and corrupt the bad-tick check.
            val dtx = (upTotal - prevUpTotal).coerceAtLeast(0L)
            val drx = (downTotal - prevDownTotal).coerceAtLeast(0L)
            prevUpTotal = upTotal
            prevDownTotal = downTotal

            val isBad = dtx >= RX_WATCHER_TX_THRESHOLD && drx == 0L
            consecutiveBad = if (isBad) consecutiveBad + 1 else 0

            val state = when {
                consecutiveBad >= RX_WATCHER_CONFIRM_TICKS -> HealthMetrics.StallState.DEAD
                isBad -> HealthMetrics.StallState.SUSPICIOUS
                else -> HealthMetrics.StallState.HEALTHY
            }

            publish(status, state, consecutiveBad)

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
                            "up=${status.uplink}Bps down=${status.downlink}Bps " +
                            "state=$state bad=$consecutiveBad",
                )
                lastLoggedState = state
            }

            if (state == HealthMetrics.StallState.DEAD) {
                if (now - lastStallRestartAt < MIN_STALL_RESTART_INTERVAL_MS) {
                    return
                }
                lastStallRestartAt = now
                lastRestartRequestAt = now
                AppLog.e(
                    TAG,
                    "rx-watcher DETECT: tunnel stalled " +
                            "($consecutiveBad consecutive ticks with rx=0 and dtx>=${RX_WATCHER_TX_THRESHOLD}B) " +
                            "— force restarting",
                )
                consecutiveBad = 0
                runCatching { onConnectivityLost(true) }
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

    /**
     * Detects intermittent QUIC session degradation by monitoring actual
     * connection outcomes via libbox's ConnectionEvents stream.
     *
     * Stale QUIC sessions can be partially functional: urltest passes,
     * DNS resolves, but real TCP streams randomly get "connection refused".
     * Neither the probe() (urltest-based) nor TunnelStallDetector (TX/RX
     * aggregate) can catch this — the intermittent successes mask the
     * failure in aggregate metrics.
     *
     * This detector counts individual TCP connections through proxy
     * outbounds that close with 0 bytes received (DownlinkTotal == 0)
     * within a short lifetime — the exact signature of a refused/reset
     * connection. When enough pile up in a sliding window, trigger restart.
     */
    private inner class ConnectionFailureDetector : CommandClientHandler {

        private val failures = ArrayDeque<Long>()
        private var logCounter = 0

        override fun writeConnectionEvents(events: ConnectionEvents?) {
            if (events == null) return
            val iter = events.iterator()
            while (iter.hasNext()) {
                val event = iter.next()
                if (event.type.toInt() != 2) continue // ConnectionEventClosed
                val conn = event.connection ?: continue
                if (conn.network != "tcp") continue
                if (conn.outbound !in PROXY_OUTBOUNDS) continue

                val lifetimeMs = event.closedAt - conn.createdAt
                val failed = conn.downlinkTotal == 0L && lifetimeMs in 1 until CONN_FAIL_MAX_LIFETIME_MS

                logCounter++
                if (logCounter % 10 == 1 || failed) {
                    AppLog.i(
                        TAG,
                        "conn-mon: ${conn.outbound} → ${conn.destination} " +
                                "dl=${conn.downlinkTotal}B life=${lifetimeMs}ms " +
                                if (failed) "FAIL" else "ok",
                    )
                }

                if (!failed) continue

                val now = System.currentTimeMillis()
                failures.addLast(now)
                evictOld(now)

                if (failures.size >= CONN_FAIL_THRESHOLD) {
                    if (now - lastStallRestartAt < MIN_STALL_RESTART_INTERVAL_MS) return
                    lastStallRestartAt = now
                    lastRestartRequestAt = now
                    AppLog.e(
                        TAG,
                        "conn-mon DETECT: ${failures.size} failed proxy connections " +
                                "in ${CONN_WINDOW_MS / 1000}s — restarting",
                    )
                    failures.clear()
                    runCatching { onConnectivityLost(false) }
                        .onFailure { AppLog.e(TAG, "conn-mon onConnectivityLost failed", it) }
                }
            }
        }

        private fun evictOld(now: Long) {
            val cutoff = now - CONN_WINDOW_MS
            while (failures.isNotEmpty() && failures.first() < cutoff) {
                failures.removeFirst()
            }
        }

        override fun clearLogs() {}
        override fun connected() {}
        override fun disconnected(message: String?) {}
        override fun initializeClashMode(modes: StringIterator?, current: String?) {}
        override fun setDefaultLogLevel(level: Int) {}
        override fun updateClashMode(mode: String?) {}
        override fun writeLogs(logs: LogIterator?) {}
        override fun writeGroups(groups: OutboundGroupIterator?) {}
        override fun writeStatus(status: StatusMessage?) {}
    }
}
