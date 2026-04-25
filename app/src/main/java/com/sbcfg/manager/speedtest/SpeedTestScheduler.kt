package com.sbcfg.manager.speedtest

import com.sbcfg.manager.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SpeedTestScheduler(
    private val engine: SpeedTestEngine,
    private val dao: SpeedTestDao,
    private val powerManager: android.os.PowerManager,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "SpeedTest"
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
    }

    private var job: Job? = null

    private val _latestResult = MutableStateFlow<SpeedTestResult?>(null)
    val latestResult: StateFlow<SpeedTestResult?> = _latestResult.asStateFlow()

    private val _lastPingMs = MutableStateFlow<Double?>(null)
    val lastPingMs: StateFlow<Double?> = _lastPingMs.asStateFlow()

    /**
     * Starts the ping-only heartbeat loop.
     * Also loads the most recent result from Room to restore state after process restart.
     */
    fun start() {
        if (job?.isActive == true) return

        // Restore last result from database in the background
        scope.launch {
            try {
                val latest = dao.getLatest()
                if (latest != null) {
                    _latestResult.value = latest
                    _lastPingMs.value = latest.pingMs.takeIf { it >= 0 }
                    AppLog.i(TAG, "Restored last result: ping=${latest.pingMs.toInt()}ms from ${latest.serverName}")
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to load last speed test result: ${e.message}")
            }
        }

        job = scope.launch {
            AppLog.i(TAG, "Heartbeat started (interval=${HEARTBEAT_INTERVAL_MS / 1000}s)")
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)

                if (!powerManager.isInteractive) {
                    AppLog.i(TAG, "Screen off — skipping heartbeat ping")
                    continue
                }

                try {
                    val server = DEFAULT_SERVERS.first()
                    val pingMs = engine.measurePing(server.pingUrl)
                    _lastPingMs.value = pingMs.takeIf { it >= 0 }
                    AppLog.i(TAG, "Heartbeat ping: ${if (pingMs >= 0) "${pingMs.toInt()}ms" else "failed"}")
                } catch (e: Exception) {
                    AppLog.w(TAG, "Heartbeat ping error: ${e.message}")
                }
            }
            AppLog.i(TAG, "Heartbeat stopped")
        }
    }

    /**
     * Cancels the heartbeat loop.
     */
    fun stop() {
        job?.cancel()
        job = null
        AppLog.i(TAG, "Heartbeat cancelled")
    }

    /**
     * Runs a full test: ping → download → upload (if supported by server).
     * Saves result to Room and updates [latestResult] and [lastPingMs] flows.
     */
    suspend fun runFullTest(
        server: SpeedTestServer = DEFAULT_SERVERS.first(),
        onProgress: (phase: String, valueMbps: Double) -> Unit = { _, _ -> },
    ): SpeedTestResult {
        AppLog.i(TAG, "Full test started on server: ${server.name} (${server.location})")

        val pingMs = engine.measurePing(server.pingUrl)
        _lastPingMs.value = pingMs.takeIf { it >= 0 }
        onProgress("ping", pingMs.coerceAtLeast(0.0))
        AppLog.i(TAG, "Full test — ping: ${if (pingMs >= 0) "${pingMs.toInt()}ms" else "failed"}")

        val downloadMbps = engine.measureDownload(server.downloadUrl) { currentMbps ->
            onProgress("download", currentMbps)
        }
        AppLog.i(TAG, "Full test — download: ${"%.2f".format(downloadMbps)} Mbps")

        val uploadMbps = if (server.uploadUrl != null) {
            engine.measureUpload(server.uploadUrl, method = server.uploadMethod) { currentMbps ->
                onProgress("upload", currentMbps)
            }.also { AppLog.i(TAG, "Full test — upload: ${"%.2f".format(it)} Mbps") }
        } else {
            AppLog.i(TAG, "Full test — upload: skipped (no upload URL for ${server.name})")
            -1.0
        }

        val result = SpeedTestResult(
            serverName = server.name,
            pingMs = pingMs,
            downloadMbps = downloadMbps,
            uploadMbps = uploadMbps,
            isBackground = false,
        )

        try {
            dao.insert(result)
            _latestResult.value = result
            AppLog.i(
                TAG,
                "Full test saved: ping=${pingMs.toInt()}ms dl=${"%.1f".format(downloadMbps)}Mbps ul=${"%.1f".format(uploadMbps)}Mbps"
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to save speed test result", e)
        }

        return result
    }
}
