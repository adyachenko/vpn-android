package com.sbcfg.manager.vpn

import com.sbcfg.manager.util.AppLog
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
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
import java.net.InetAddress

/**
 * Watchdog that periodically pings libbox CommandServer over the unix socket
 * to verify the sing-box engine is still responsive, and checks DNS connectivity
 * to detect broken name resolution after network changes.
 *
 * Two failure modes:
 * - Socket ping fails (libbox crashed) → onUnhealthy → reload engine
 * - DNS resolve fails (routing broken after wifi↔mobile/sleep) → onConnectivityLost → full restart
 */
class VpnHealthCheck(
    private val onUnhealthy: () -> Unit,
    private val onConnectivityLost: () -> Unit
) {
    companion object {
        private const val TAG = "VpnHealthCheck"
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val INITIAL_DELAY_MS = 30_000L
        private const val MAX_FAILURES = 3
        private const val MAX_DNS_FAILURES = 2
        private const val DNS_CHECK_HOST = "dns.google"
        private const val NETWORK_CHANGE_CHECK_DELAY_MS = 5_000L
    }

    private var job: Job? = null
    private var networkCheckJob: Job? = null
    private var dnsFailures = 0

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            AppLog.i(TAG, "Health check started (interval=${CHECK_INTERVAL_MS}ms, threshold=$MAX_FAILURES)")
            delay(INITIAL_DELAY_MS)
            var failures = 0
            while (isActive) {
                val healthy = ping()
                if (healthy) {
                    if (failures > 0) {
                        AppLog.i(TAG, "Recovered after $failures failed checks")
                    }
                    failures = 0
                    checkDnsConnectivity()
                } else {
                    failures++
                    dnsFailures = 0
                    AppLog.w(TAG, "Health check failed ($failures/$MAX_FAILURES)")
                    if (failures >= MAX_FAILURES) {
                        AppLog.e(TAG, "VPN unhealthy — triggering restart")
                        try {
                            onUnhealthy()
                        } catch (e: Exception) {
                            AppLog.e(TAG, "onUnhealthy callback failed", e)
                        }
                        failures = 0
                    }
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Called after a network change (wifi↔mobile, sleep wake).
     * Schedules a DNS connectivity check after a delay to verify
     * sing-box is still routing correctly after reload.
     */
    fun onNetworkChanged(scope: CoroutineScope) {
        networkCheckJob?.cancel()
        networkCheckJob = scope.launch(Dispatchers.IO) {
            delay(NETWORK_CHANGE_CHECK_DELAY_MS)
            AppLog.i(TAG, "Post-network-change DNS check")
            checkDnsConnectivity()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        networkCheckJob?.cancel()
        networkCheckJob = null
        dnsFailures = 0
    }

    private fun checkDnsConnectivity() {
        val ok = resolveDns()
        if (ok) {
            if (dnsFailures > 0) {
                AppLog.i(TAG, "DNS recovered after $dnsFailures failures")
            }
            dnsFailures = 0
        } else {
            dnsFailures++
            AppLog.w(TAG, "DNS check failed ($dnsFailures/$MAX_DNS_FAILURES)")
            if (dnsFailures >= MAX_DNS_FAILURES) {
                AppLog.e(TAG, "DNS unreachable — triggering VPN reconnect")
                dnsFailures = 0
                try {
                    onConnectivityLost()
                } catch (e: Exception) {
                    AppLog.e(TAG, "onConnectivityLost callback failed", e)
                }
            }
        }
    }

    private fun resolveDns(): Boolean {
        return try {
            val addresses = InetAddress.getAllByName(DNS_CHECK_HOST)
            addresses.isNotEmpty()
        } catch (e: Exception) {
            AppLog.d(TAG, "DNS resolve failed: ${e.message}")
            false
        }
    }

    private fun ping(): Boolean {
        var client: CommandClient? = null
        return try {
            val options = CommandClientOptions().apply {
                statusInterval = CHECK_INTERVAL_MS
            }
            client = CommandClient(SilentClientHandler, options)
            client.connect()
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "Ping failed: ${e.message}")
            false
        } finally {
            try {
                client?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private object SilentClientHandler : CommandClientHandler {
        override fun clearLogs() {}
        override fun connected() {}
        override fun disconnected(message: String?) {}
        override fun initializeClashMode(modes: StringIterator?, current: String?) {}
        override fun setDefaultLogLevel(level: Int) {}
        override fun updateClashMode(mode: String?) {}
        override fun writeConnectionEvents(events: ConnectionEvents?) {}
        override fun writeGroups(groups: OutboundGroupIterator?) {}
        override fun writeLogs(logs: LogIterator?) {}
        override fun writeStatus(status: StatusMessage?) {}
    }
}
