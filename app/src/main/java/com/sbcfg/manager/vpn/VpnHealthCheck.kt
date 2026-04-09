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

/**
 * Watchdog that periodically pings libbox CommandServer over the unix socket
 * to verify the sing-box engine is still responsive. If it fails N times in a row,
 * triggers a restart via the supplied callback.
 *
 * This is a liveness probe — it doesn't verify upstream connectivity, but it does
 * catch the case where libbox crashes silently or the unix socket dies.
 */
class VpnHealthCheck(
    private val onUnhealthy: () -> Unit
) {
    companion object {
        private const val TAG = "VpnHealthCheck"
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val INITIAL_DELAY_MS = 30_000L
        private const val MAX_FAILURES = 3
    }

    private var job: Job? = null

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
                } else {
                    failures++
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

    fun stop() {
        job?.cancel()
        job = null
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
