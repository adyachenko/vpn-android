package com.sbcfg.manager.vpn

import android.net.ConnectivityManager
import com.sbcfg.manager.util.AppLog
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watchdog that verifies both that the sing-box engine is alive AND that the
 * tunnel can actually route traffic, using sing-box's own urltest results as
 * the source of truth for tunnel health.
 *
 * Why not a direct socket probe?
 * The VPN-owning app is excluded from its own tunnel (addDisallowedApplication
 * prevents routing loops when the app fetches config / reports to Sentry / etc).
 * That exclusion also makes `Network.bindSocket()` to the VPN network fail
 * with EPERM — so we cannot send a test packet through the tunnel from this
 * process. Instead we read the urltest delays that sing-box itself measured
 * while probing outbounds: if every proxy outbound timed out on its last
 * probe, the tunnel is black-holing packets and needs a full restart to
 * rebind sockets (especially Hysteria2 UDP, which stays bound to the stale
 * underlying interface after a wifi/cellular change).
 *
 * Two failure modes:
 * - CommandClient connect/read fails → libbox engine crashed → onUnhealthy → reload
 * - urltest reports all proxy outbounds as timed out → onConnectivityLost → full restart
 */
class VpnHealthCheck(
    @Suppress("unused") private val connectivityManager: ConnectivityManager,
    private val onUnhealthy: () -> Unit,
    private val onConnectivityLost: () -> Unit
) {
    companion object {
        private const val TAG = "VpnHealthCheck"
        private const val CHECK_INTERVAL_MS = 60_000L
        // First probe delay. urltest needs a few seconds after engine start
        // to measure outbounds; polling before that would give false negatives.
        private const val INITIAL_DELAY_MS = 30_000L
        // Short wait after CommandClient.connect() so libbox has time to push
        // initial group state via writeGroups() before we disconnect.
        private const val GROUP_STATE_WAIT_MS = 600L
        private const val MAX_FAILURES = 3
        private const val MAX_TUNNEL_FAILURES = 2
        // Tag of the urltest group in the generated config. Must stay in
        // sync with ConfigGenerator's template; see sing-box-proxy config.
        private const val PROXY_GROUP_TAG = "proxy-select"
        private const val NETWORK_CHANGE_CHECK_DELAY_MS = 10_000L
    }

    private var job: Job? = null
    private var networkCheckJob: Job? = null
    private var tunnelFailures = 0

    @Volatile
    private var lastSelectedOutbound: String? = null

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            AppLog.i(TAG, "Health check started (interval=${CHECK_INTERVAL_MS}ms, threshold=$MAX_FAILURES)")
            delay(INITIAL_DELAY_MS)
            var failures = 0
            while (isActive) {
                val result = probe()
                when {
                    !result.libboxAlive -> {
                        failures++
                        tunnelFailures = 0
                        AppLog.w(TAG, "Libbox ping failed ($failures/$MAX_FAILURES)")
                        if (failures >= MAX_FAILURES) {
                            AppLog.e(TAG, "Libbox unresponsive — reloading engine")
                            runCatching { onUnhealthy() }
                                .onFailure { AppLog.e(TAG, "onUnhealthy callback failed", it) }
                            failures = 0
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
                delay(CHECK_INTERVAL_MS)
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

    fun stop() {
        job?.cancel()
        job = null
        networkCheckJob?.cancel()
        networkCheckJob = null
        tunnelFailures = 0
    }

    private data class ProbeResult(
        val libboxAlive: Boolean,
        val outboundHealthy: Boolean
    )

    /**
     * Connects to libbox's CommandServer over the unix socket, waits briefly
     * for an initial state push, then reads urltest delays for the proxy
     * group. The connect itself proves libbox is alive; the delays prove
     * whether the tunnel can actually reach the upstream.
     */
    private fun probe(): ProbeResult {
        var client: CommandClient? = null
        val handler = GroupAwareHandler()
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
            AppLog.w(TAG, "Ping failed: ${e.message}")
            false
        } finally {
            try { client?.disconnect() } catch (_: Exception) {}
        }

        if (!libboxAlive) return ProbeResult(libboxAlive = false, outboundHealthy = false)

        handler.selectedOutbound?.let { selected ->
            val prev = lastSelectedOutbound
            if (prev != null && prev != selected) {
                AppLog.i(TAG, "Outbound switched: $prev -> $selected")
            }
            lastSelectedOutbound = selected
        }

        val proxyState = handler.proxyGroupState
        return if (proxyState == null) {
            // No proxy urltest group seen yet — treat as healthy, don't flap.
            // This happens in the short window between engine restart and the
            // first urltest cycle; the next probe will have real data.
            AppLog.i(TAG, "No proxy group state yet — assuming healthy")
            ProbeResult(libboxAlive = true, outboundHealthy = true)
        } else {
            AppLog.i(TAG, "Proxy outbounds: ${proxyState.description}")
            ProbeResult(libboxAlive = true, outboundHealthy = proxyState.hasHealthy)
        }
    }

    private data class ProxyGroupState(
        val hasHealthy: Boolean,
        val description: String
    )

    private inner class GroupAwareHandler : CommandClientHandler {
        @Volatile
        var selectedOutbound: String? = null

        @Volatile
        var proxyGroupState: ProxyGroupState? = null

        override fun writeGroups(groups: OutboundGroupIterator?) {
            if (groups == null) return
            while (groups.hasNext()) {
                val group = groups.next()
                if (group.type != "urltest" && group.type != "selector") continue

                val items = mutableListOf<String>()
                var anyHealthy = false
                val itemIter = group.items
                while (itemIter.hasNext()) {
                    val item = itemIter.next()
                    val d = item.urlTestDelay
                    if (d > 0) anyHealthy = true
                    items.add("${item.tag}=${if (d > 0) "${d}ms" else "timeout"}")
                }

                if (group.tag == PROXY_GROUP_TAG) {
                    selectedOutbound = group.selected
                    proxyGroupState = ProxyGroupState(
                        hasHealthy = anyHealthy,
                        description = "selected=${group.selected}, ${items.joinToString()}"
                    )
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
}
