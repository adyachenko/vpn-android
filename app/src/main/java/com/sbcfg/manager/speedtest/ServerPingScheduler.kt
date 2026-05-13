package com.sbcfg.manager.speedtest

import android.content.Context
import android.os.PowerManager
import com.sbcfg.manager.domain.ServerListRepository
import com.sbcfg.manager.domain.model.VpnServer
import com.sbcfg.manager.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Периодический пинг всех VPN-серверов из [ServerListRepository] прямым
 * HTTPS HEAD-запросом (без VPN-туннеля) — измеряет канал «клиент → VPS»,
 * именно это боттлнек при выборе сервера.
 *
 * Запускается из UI ([com.sbcfg.manager.ui.servers.ServersScreen]) и
 * останавливается при выходе с экрана, чтобы не жечь батарею в фоне.
 */
@Singleton
class ServerPingScheduler @Inject constructor(
    okHttpClient: OkHttpClient,
    private val repository: ServerListRepository,
    @ApplicationContext context: Context,
) {
    // SpeedTestEngine не Hilt-managed (см. SpeedTestViewModel) — конструируем
    // здесь же из инжектируемого OkHttpClient, чтобы не дублировать паттерн.
    private val engine = SpeedTestEngine(okHttpClient)
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _pings = MutableStateFlow<Map<String, Long?>>(emptyMap())
    /** Текущий пинг (мс) для каждого сервера. null = провал измерения. */
    val pings: StateFlow<Map<String, Long?>> = _pings.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var job: Job? = null

    /** Запустить периодический пинг. Идемпотентно — повторный вызов no-op. */
    fun start() {
        if (job?.isActive == true) return
        AppLog.i(TAG, "Starting server ping scheduler (interval=${INTERVAL_MS}ms)")
        _isRunning.value = true
        job = scope.launch {
            try {
                while (isActive) {
                    if (!powerManager.isInteractive) {
                        AppLog.i(TAG, "Screen off — skipping ping cycle")
                    } else {
                        runOnce()
                    }
                    delay(INTERVAL_MS)
                }
            } finally {
                _isRunning.value = false
            }
        }
    }

    /** Остановить. Безопасно вызывать несколько раз. */
    fun stop() {
        job?.cancel()
        job = null
        _isRunning.value = false
        AppLog.i(TAG, "Server ping scheduler stopped")
    }

    /** Запустить один цикл пинга всех серверов вне очереди (для pull-to-refresh). */
    suspend fun pingNow() = runOnce()

    private suspend fun runOnce() {
        val servers = repository.servers.first()
        if (servers.isEmpty()) {
            AppLog.i(TAG, "No servers — skipping ping cycle")
            return
        }
        // Параллельно по серверам — стабильный пинг не должен ждать соседа.
        val results = mutableMapOf<String, Long?>()
        servers.forEach { server -> results[server.tag] = pingOne(server) }
        _pings.value = results
    }

    private suspend fun pingOne(server: VpnServer): Long? {
        return try {
            val ms = engine.measurePing(server.speedtestUrl)
            if (ms < 0) null else ms.toLong()
        } catch (e: Exception) {
            AppLog.w(TAG, "Ping ${server.tag} failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "ServerPing"
        private const val INTERVAL_MS = 30_000L
    }
}
