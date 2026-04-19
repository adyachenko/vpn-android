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
 * Why not a direct socket probe?
 * The VPN-owning app is excluded from its own tunnel (addDisallowedApplication
 * prevents routing loops when the app fetches config / reports to Sentry).
 * That exclusion also makes Network.bindSocket() to the VPN network fail with
 * EPERM — so we cannot send a test packet through the tunnel from this
 * process. We read the urltest delays that sing-box itself measured instead.
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
    }

    private var job: Job? = null
    private var networkCheckJob: Job? = null
    private var statusJob: Job? = null
    private var statusClient: CommandClient? = null
    private var tunnelFailures = 0

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
                val nextDelay = if (failures > 0 || tunnelFailures > 0) RETRY_INTERVAL_MS else CHECK_INTERVAL_MS
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
                tunnelFailures++
                AppLog.w(TAG, "All proxy outbounds timing out ($tunnelFailures/$MAX_TUNNEL_FAILURES)")
                if (tunnelFailures >= MAX_TUNNEL_FAILURES) {
                    AppLog.e(TAG, "Tunnel dead — triggering VPN restart")
                    tunnelFailures = 0
                    runCatching { onConnectivityLost() }
                        .onFailure { AppLog.e(TAG, "onConnectivityLost callback failed", it) }
                }
            }
            else -> {
                if (failures > 0 || tunnelFailures > 0) {
                    AppLog.i(TAG, "Tunnel recovered")
                }
                failures = 0
                tunnelFailures = 0
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
        val socketMissing: Boolean = false
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

        return ProbeResult(libboxAlive = true, outboundHealthy = auto.hasHealthy)
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

        override fun writeStatus(status: StatusMessage?) {
            if (status == null) return
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

            // Log only when there's actual traffic — otherwise we flood
            // the buffer with idle-device ticks.
            if (dtx > 0 || drx > 0) {
                AppLog.i(
                    TAG,
                    "rx-watcher: dtx=${dtx}B drx=${drx}B " +
                            "sumTx=${sumTx}B sumRx=${sumRx}B " +
                            "up=${status.uplink}Bps down=${status.downlink}Bps " +
                            "state=$state susp=$consecutiveSuspicious",
                )
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
