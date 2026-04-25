package com.sbcfg.manager.vpn

import com.sbcfg.manager.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ClashHealthMonitor(
    private val clashClient: ClashApiClient,
    private val scope: CoroutineScope,
    private val onTrafficUpdate: (TrafficSnapshot) -> Unit = {},
) {
    companion object {
        private const val TAG = "ClashHealth"
        private const val INITIAL_DELAY_MS = 3_000L
        private const val CHECK_INTERVAL_MS = 3_000L
        private const val STALE_THRESHOLD = 3
    }

    private var job: Job? = null
    private var lastUpload: Long = 0L
    private var staleCount: Int = 0

    fun start() {
        job = scope.launch {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                check()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun check() {
        val snapshot = try {
            clashClient.fetchTrafficSnapshot()
        } catch (e: Exception) {
            AppLog.w(TAG, "API unreachable: ${e.message}")
            return
        }

        onTrafficUpdate(snapshot)

        val connCount = snapshot.activeConnections
        val upload = snapshot.uploadTotal
        val download = snapshot.downloadTotal

        AppLog.i(TAG, "[clash-health] conns=$connCount up=${formatBytes(upload)} down=${formatBytes(download)}")

        val isStale = connCount == 0 && upload == lastUpload

        if (isStale) {
            staleCount++
            if (staleCount >= STALE_THRESHOLD) {
                val staleSecs = (staleCount * CHECK_INTERVAL_MS) / 1000
                AppLog.w(TAG, "[clash-health] STALE — no conns, no traffic for ${staleSecs}s")
            }
        } else {
            if (staleCount >= STALE_THRESHOLD) {
                AppLog.i(TAG, "[clash-health] recovered after $staleCount stale ticks")
            }
            staleCount = 0
        }

        lastUpload = upload
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.1f GB".format(gb)
}
