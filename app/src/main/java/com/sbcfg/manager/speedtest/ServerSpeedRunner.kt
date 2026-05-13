package com.sbcfg.manager.speedtest

import com.sbcfg.manager.domain.ServerListRepository
import com.sbcfg.manager.domain.model.VpnServer
import com.sbcfg.manager.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Прогоняет ping + speedtest для всех серверов из [ServerListRepository]
 * последовательно (чтобы не делить канал). Использует тот же
 * [SpeedTestEngine], что и общий SpeedTest screen — реюз кода.
 */
@Singleton
class ServerSpeedRunner @Inject constructor(
    okHttpClient: OkHttpClient,
    private val repository: ServerListRepository,
) {
    data class ServerResult(
        val pingMs: Long?,
        val downloadMbps: Double?,
    )

    enum class Phase { IDLE, PING, DOWNLOAD }

    data class UiState(
        /** tag → результат. null значит «ещё не тестировался» или ошибка. */
        val results: Map<String, ServerResult> = emptyMap(),
        val isRunning: Boolean = false,
        /** tag сервера, который тестируется сейчас. */
        val currentTag: String? = null,
        val currentPhase: Phase = Phase.IDLE,
        /** Live-индикатор скорости в Mbps во время download-фазы. */
        val currentMbps: Double = 0.0,
        val error: String? = null,
    )

    private val engine = SpeedTestEngine(okHttpClient)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    /** Запустить прогон. Повторный вызов во время выполнения — no-op. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val servers = repository.servers.first()
            if (servers.isEmpty()) {
                _state.update { it.copy(error = "Нет серверов") }
                return@launch
            }
            _state.update {
                it.copy(
                    isRunning = true,
                    error = null,
                    results = emptyMap(),
                )
            }
            try {
                val results = mutableMapOf<String, ServerResult>()
                for (server in servers) {
                    val r = testOne(server)
                    results[server.tag] = r
                    _state.update { it.copy(results = results.toMap()) }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "Run failed: ${e.message}")
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update {
                    it.copy(
                        isRunning = false,
                        currentTag = null,
                        currentPhase = Phase.IDLE,
                        currentMbps = 0.0,
                    )
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun testOne(server: VpnServer): ServerResult {
        AppLog.i(TAG, "Testing ${server.tag} → ${server.speedtestUrl}")

        _state.update {
            it.copy(currentTag = server.tag, currentPhase = Phase.PING, currentMbps = 0.0)
        }
        val ping = runCatching {
            engine.measurePing(
                url = server.speedtestUrl,
                pingCount = PING_COUNT,
                timeoutSec = PING_TIMEOUT_SEC,
            )
        }.getOrDefault(-1.0)

        _state.update { it.copy(currentPhase = Phase.DOWNLOAD, currentMbps = 0.0) }
        val mbps = runCatching {
            engine.measureDownload(
                url = server.speedtestUrl,
                timeoutMs = DOWNLOAD_TIMEOUT_MS,
            ) { live ->
                _state.update { it.copy(currentMbps = live) }
            }
        }.getOrDefault(-1.0)

        return ServerResult(
            pingMs = if (ping < 0) null else ping.toLong(),
            downloadMbps = if (mbps < 0) null else mbps,
        )
    }

    private companion object {
        const val TAG = "ServerSpeed"
        // Per-server timings: до ~5с при стабильном канале (1с ping + 4с download).
        // 2 сервера ≈ 10с total — соответствует ожиданию пользователя.
        const val PING_COUNT = 3
        const val PING_TIMEOUT_SEC = 2L
        const val DOWNLOAD_TIMEOUT_MS = 10_000L
    }
}
