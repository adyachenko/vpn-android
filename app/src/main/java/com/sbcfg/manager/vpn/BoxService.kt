package com.sbcfg.manager.vpn

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.IBinder
import androidx.lifecycle.MutableLiveData
import com.sbcfg.manager.constant.Alert
import com.sbcfg.manager.constant.Status
import com.sbcfg.manager.util.AppLog
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// BoxService is a process-scoped singleton that coordinates the VPN lifecycle.
// Context references (service, vpnService, notification) are always nulled out in
// onServiceDestroy() and stop(), so there is no actual leak.
@SuppressLint("StaticFieldLeak")
object BoxService : CommandServerHandler {

    private const val TAG = "BoxService"

    val status = MutableLiveData(Status.Stopped)

    /**
     * Wall-clock timestamp (System.currentTimeMillis) of when the tunnel
     * transitioned to Started. Null while stopped. Lives as long as the
     * process does — survives Activity/ViewModel recreation so the uptime
     * counter doesn't reset when the user reopens the app.
     */
    @Volatile
    var startedAt: Long? = null
        private set

    private var commandServer: CommandServer? = null
    private var service: Service? = null
    private var vpnService: VPNService? = null
    private var notification: ServiceNotification? = null
    private var configContent: String? = null

    /**
     * Read-only snapshot of the currently loaded sing-box config. Used by
     * DiagnosticsExporter to include (credential-masked) routing state in
     * exported logs. Null while VPN is stopped.
     */
    fun currentConfigSnapshot(): String? = configContent
    private var binder: ServiceBinder? = null
    private var healthCheck: VpnHealthCheck? = null
    private var healthScope: CoroutineScope? = null
    // Job of the in-flight stop() coroutine. Cancelled by forceResetForRestart()
    // so the post-closeService() teardown steps (close/notify/stopSelf/status)
    // don't execute against a successor instance spawned by restart().
    private var stopJob: Job? = null
    private var stopDeferred: CompletableDeferred<Unit>? = null
    private var uptimeJob: Job? = null
    private var clashHealthMonitor: ClashHealthMonitor? = null
    private var stuckConnectionMonitor: StuckConnectionMonitor? = null
    var clashClient: ClashApiClient? = null
        private set

    val trafficSnapshot = MutableLiveData<TrafficSnapshot?>(null)

    @Volatile
    private var lastConnectivityRestart: Long = 0
    private const val MIN_RESTART_INTERVAL_MS = 60_000L
    private const val MAX_FORCE_RESTARTS = 2
    @Volatile
    private var consecutiveForceRestarts: Int = 0

    @Volatile
    private var lastNetworkRestart: Long = 0
    // Throttle network-change restarts: avoid restart storm during fast wifi/cellular handoffs.
    private const val MIN_NETWORK_RESTART_INTERVAL_MS = 15_000L
    // Wait a bit after network change before acting so the new link stabilises (DHCP, RA, DNS).
    private const val NETWORK_CHANGE_RESTART_DELAY_MS = 2_500L

    // restart() waits this long for the engine to finish stopping before
    // forcing a reset. Hysteria2 QUIC outbounds can take >10s to close cleanly
    // on network change, so we keep a generous budget.
    private const val RESTART_STOP_TIMEOUT_MS = 30_000L
    // Hard cap on libbox closeService(). The Go call cannot be cancelled, but
    // we can stop blocking the Kotlin side and let the goroutine finish in the
    // background while we move Status to Stopped.
    private const val CLOSE_SERVICE_TIMEOUT_MS = 10_000L

    fun start(context: Context, configContent: String) {
        AppLog.i(TAG, "start() called, current status=${status.value}")
        if (status.value == Status.Starting || status.value == Status.Started) {
            AppLog.w(TAG, "Already starting/started, ignoring")
            return
        }
        this.configContent = configContent
        AppLog.i(TAG, "Config stored, length=${configContent.length}")

        val intent = Intent(context, VPNService::class.java)
        intent.putExtra("config", configContent)
        AppLog.i(TAG, "Calling startForegroundService(VPNService)")
        try {
            context.startForegroundService(intent)
            AppLog.i(TAG, "startForegroundService() returned OK")
        } catch (e: Exception) {
            AppLog.e(TAG, "startForegroundService() FAILED", e)
        }
    }

    fun stop(quiet: Boolean = false) {
        AppLog.i(TAG, "stop() called, current status=${status.value}")
        val currentService = service
        if (currentService == null) {
            AppLog.w(TAG, "stop() but service is null")
            stopDeferred?.complete(Unit)
            return
        }
        if (status.value != Status.Started) {
            AppLog.w(TAG, "stop() but status is ${status.value}, not Started")
            stopDeferred?.complete(Unit)
            return
        }
        status.postValue(Status.Stopping)
        AppLog.i(TAG, "Status set to Stopping")
        stopHealthCheck()
        stopUptimeUpdater()
        // Capture all mutable refs up-front. closeService() is a blocking Go call
        // that can outlive RESTART_STOP_TIMEOUT_MS; by the time this coroutine
        // resumes after it, restart() may already have spawned a successor
        // (new CommandServer / VPNService / notification). Reading the globals
        // at that point would apply close/stopSelf/status-update to the new
        // instance and break it (the exact bug v1.2.13 hit in the field).
        val localVpnService = vpnService
        val localCommandServer = commandServer
        val localNotification = notification
        stopJob = GlobalScope.launch(Dispatchers.IO) {
            // 1. Close TUN fd
            localVpnService?.closeTun()
            AppLog.i(TAG, "TUN closed")

            // 2. Stop sing-box engine (releases dup'd TUN fd).
            // closeService() is a blocking Go call that may hang on QUIC/Hysteria2
            // outbound shutdown. withTimeoutOrNull can't actually cancel the Go
            // call — the Kotlin coroutine stays blocked until Go returns — but
            // forceResetForRestart() will cancel stopJob, and the isActive check
            // below lets us bail out before touching the successor instance.
            try {
                val t0 = System.currentTimeMillis()
                val completed = withTimeoutOrNull(CLOSE_SERVICE_TIMEOUT_MS) {
                    localCommandServer?.closeService()
                    true
                }
                val dt = System.currentTimeMillis() - t0
                if (completed == null) {
                    AppLog.w(TAG, "closeService() did not finish within ${CLOSE_SERVICE_TIMEOUT_MS}ms — proceeding anyway (goroutine may still run)")
                } else {
                    AppLog.i(TAG, "closeService() done in ${dt}ms")
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "closeService() error", e)
            }

            // If restart() fired forceResetForRestart() while we were blocked in
            // closeService(), a successor VPN instance is now live. Skip the rest
            // of teardown — running close/stopSelf/status against the successor
            // would tear it down.
            if (!isActive) {
                AppLog.i(TAG, "stop() cancelled during closeService — skipping post-close teardown")
                return@launch
            }

            // 3. Close command server
            try {
                localCommandServer?.close()
                AppLog.i(TAG, "CommandServer closed")
            } catch (e: Exception) {
                AppLog.e(TAG, "CommandServer close error", e)
            }

            withContext(Dispatchers.Main) {
                if (quiet) localNotification?.close() else localNotification?.closeStopped()
            }

            // 5. Stop Android service
            withContext(Dispatchers.Main) {
                AppLog.i(TAG, "stopSelf()")
                startedAt = null
                status.postValue(Status.Stopped)
                stopDeferred?.complete(Unit)
                stopDeferred = null
                currentService.stopSelf()
            }
        }
    }

    suspend fun stopAndAwait(quiet: Boolean = false) {
        val deferred = CompletableDeferred<Unit>()
        stopDeferred = deferred
        stop(quiet)
        deferred.await()
    }

    fun reload(configContent: String) {
        this.configContent = configContent
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val overrideOptions = OverrideOptions()
                commandServer?.startOrReloadService(configContent, overrideOptions)
            } catch (e: Exception) {
                AppLog.e(TAG, "Error reloading service", e)
            }
        }
    }

    /**
     * Full VPN restart with a new config: stop current tunnel, wait for shutdown,
     * then start again. Required when per-app routing changes (include_package),
     * because that whitelist is frozen until VpnService.Builder.establish() is
     * called again.
     */
    fun restart(context: Context, newConfig: String) {
        AppLog.i(TAG, "restart() requested, current status=${status.value}")
        this.configContent = newConfig
        val currentStatus = status.value
        if (currentStatus == Status.Stopped) {
            // Desync recovery path: status is Stopped but a stale health check
            // or a stranded Android VPNService may still be around. Just call
            // start() — it will spin up a fresh CommandServer + VpnHealthCheck,
            // superseding whatever orphan state exists.
            AppLog.i(TAG, "Not running — starting fresh (recovery path)")
            start(context, newConfig)
            return
        }
        if (currentStatus != Status.Started) {
            AppLog.w(TAG, "restart() while $currentStatus — skipping to avoid double-transition")
            return
        }
        stopUptimeUpdater()
        notification?.update("Переподключение...")
        GlobalScope.launch(Dispatchers.Main) {
            stop(quiet = true)
            // Wait for the engine to actually shut down before re-creating it.
            val deadline = System.currentTimeMillis() + RESTART_STOP_TIMEOUT_MS
            while (status.value != Status.Stopped && System.currentTimeMillis() < deadline) {
                delay(100)
            }
            if (status.value != Status.Stopped) {
                // Engine still shutting down (QUIC/Hysteria2 hang). Rather than give
                // up and leave VPN off, force a reset and proceed with start(). The
                // old Go goroutine will finish in the background; the new run creates
                // a fresh CommandServer and VPNService instance.
                AppLog.w(TAG, "restart() timed out after ${RESTART_STOP_TIMEOUT_MS}ms — forcing reset and proceeding")
                forceResetForRestart()
            }
            delay(300)
            start(context, newConfig)
            AppLog.i(TAG, "restart() completed")
        }
    }

    /**
     * Force-reset singleton state when the engine fails to stop within the
     * restart deadline. Drops references without calling close() again — the
     * original closeService() coroutine in stop() is still running and will
     * finish asynchronously. Safe because start() always creates a fresh
     * CommandServer and the Android service lifecycle is reset via stopSelf().
     */
    private fun forceResetForRestart() {
        // Cancel the in-flight stop() coroutine. It may still be blocked inside
        // Go's closeService() — cancellation only takes effect once Go returns
        // control — but the isActive check in stop() will then bail out before
        // touching the successor instance.
        stopJob?.cancel()
        stopJob = null
        stopDeferred?.complete(Unit)
        stopDeferred = null
        stopUptimeUpdater()
        commandServer = null
        service = null
        vpnService = null
        notification = null
        binder = null
        startedAt = null
        clashClient = null
        clashHealthMonitor = null
        stuckConnectionMonitor = null
        status.postValue(Status.Stopped)
    }

    // Called from VPNService.onStartCommand
    internal fun onStartCommand(vpnService: VPNService, intent: Intent?) {
        AppLog.i(TAG, "onStartCommand() called")
        service = vpnService
        this.vpnService = vpnService

        val configFromIntent = intent?.getStringExtra("config")
        val config = configFromIntent ?: configContent
        AppLog.i(TAG, "Config source: ${if (configFromIntent != null) "intent" else "stored"}, isBlank=${config.isNullOrBlank()}")

        if (config.isNullOrBlank()) {
            AppLog.e(TAG, "Config is empty, stopping")
            stopAndAlert(Alert.EmptyConfiguration, "Конфигурация не задана")
            return
        }
        configContent = config

        status.postValue(Status.Starting)
        AppLog.i(TAG, "Status set to Starting")

        notification = ServiceNotification(vpnService)
        binder = ServiceBinder(status)

        // MUST call startForeground() immediately to avoid ForegroundServiceDidNotStartInTimeException
        notification!!.show("Подключение...")
        AppLog.i(TAG, "Foreground notification shown (connecting)")

        GlobalScope.launch(Dispatchers.IO) {
            try {
                AppLog.i(TAG, "Creating CommandServer...")
                val server = CommandServer(this@BoxService, vpnService)
                commandServer = server
                AppLog.i(TAG, "CommandServer created, calling start()...")
                server.start()
                AppLog.i(TAG, "CommandServer started")

                val freshConfig = rotateClashApiPort(config)
                configContent = freshConfig

                // Dump config to file for debugging
                try {
                    val debugFile = java.io.File(vpnService!!.filesDir, "debug-config.json")
                    debugFile.writeText(freshConfig)
                    AppLog.i(TAG, "Config dumped to ${debugFile.absolutePath}")
                } catch (_: Exception) {}
                AppLog.i(TAG, "Calling startOrReloadService()...")
                val overrideOptions = OverrideOptions()
                server.startOrReloadService(freshConfig, overrideOptions)
                AppLog.i(TAG, "startOrReloadService() completed successfully")

                withContext(Dispatchers.Main) {
                    startedAt = System.currentTimeMillis()
                    status.postValue(Status.Started)
                    AppLog.i(TAG, "Status set to Started, startedAt=$startedAt")
                    notification?.update("VPN подключён")
                    startUptimeUpdater()
                    binder?.broadcast(Status.Started)
                    startHealthCheck()
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Failed to start service", e)
                withContext(Dispatchers.Main) {
                    stopAndAlert(Alert.StartService, e.message ?: "Ошибка запуска VPN")
                }
            }
        }
    }

    internal fun onServiceDestroy() {
        AppLog.i(TAG, "onServiceDestroy()")
        stopHealthCheck()
        stopUptimeUpdater()
        try {
            commandServer?.closeService()
        } catch (_: Exception) {}
        try {
            commandServer?.close()
        } catch (_: Exception) {}
        commandServer = null
        notification?.remove()
        notification = null
        service = null
        vpnService = null
        binder = null
        startedAt = null
        status.postValue(Status.Stopped)
    }

    private fun startHealthCheck() {
        stopHealthCheck()
        val svc = vpnService ?: return
        val cm = svc.getSystemService(ConnectivityManager::class.java) ?: return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        healthScope = scope
        healthCheck = VpnHealthCheck(
            connectivityManager = cm,
            onUnhealthy = ::onVpnUnhealthy,
            onConnectivityLost = ::onConnectivityLost
        ).also {
            it.start(scope)
        }

        // Parse Clash API endpoint from config and start monitoring
        val (baseUrl, secret) = try {
            val cfg = org.json.JSONObject(configContent ?: "{}")
            val clashApi = cfg.optJSONObject("experimental")?.optJSONObject("clash_api")
            val controller = clashApi?.optString("external_controller", "127.0.0.1:9090") ?: "127.0.0.1:9090"
            val s = clashApi?.optString("secret", "") ?: ""
            "http://$controller" to s
        } catch (_: Exception) { "http://127.0.0.1:9090" to "" }
        AppLog.i(TAG, "Clash API client → $baseUrl")
        val client = ClashApiClient(baseUrl = baseUrl, secret = secret)
        clashClient = client
        clashHealthMonitor = ClashHealthMonitor(client, scope) { snap ->
            trafficSnapshot.postValue(snap)
        }.also { it.start() }
        stuckConnectionMonitor = StuckConnectionMonitor(client, scope).also { it.start() }
        // Применить сохранённый выбор сервера (proxy-select override) и
        // выбор протокола (per-server selector override) к только что
        // поднятому Clash API. См. ServerSelectionRepository /
        // ProtocolSelectionRepository.
        svc.applicationContext?.let { ctx ->
            com.sbcfg.manager.domain.ServerSelectionRepository
                .applyAfterVpnStart(ctx, client)
            com.sbcfg.manager.domain.ProtocolSelectionRepository
                .applyAfterVpnStart(ctx, client)
        }
    }

    private fun stopHealthCheck() {
        healthCheck?.stop()
        healthCheck = null
        clashHealthMonitor?.stop()
        clashHealthMonitor = null
        stuckConnectionMonitor?.stop()
        stuckConnectionMonitor = null
        clashClient = null
        trafficSnapshot.postValue(null)
        healthScope?.cancel()
        healthScope = null
    }

    private fun startUptimeUpdater() {
        stopUptimeUpdater()
        uptimeJob = GlobalScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(60_000)
                val ts = startedAt ?: break
                notification?.update("VPN подключён · ${formatUptime(ts)}")
            }
        }
    }

    private fun stopUptimeUpdater() {
        uptimeJob?.cancel()
        uptimeJob = null
    }

    private fun formatUptime(startedAt: Long): String {
        val elapsed = System.currentTimeMillis() - startedAt
        val minutes = ((elapsed / 60_000) % 60).toInt()
        val hours = ((elapsed / 3_600_000) % 24).toInt()
        val days = (elapsed / 86_400_000).toInt()
        return when {
            days > 0 -> "${days}д ${hours}ч"
            hours > 0 -> "${hours}ч ${minutes}м"
            minutes > 0 -> "${minutes} мин"
            else -> "< 1 мин"
        }
    }

    /**
     * Called by DefaultNetworkMonitor when the active network switches
     * (wifi↔mobile, reconnect after sleep/wifi flap). Performs a full VPN
     * restart: sing-box `reload` alone is not enough because Hysteria2's
     * UDP socket stays bound to the old underlying interface address and
     * silently black-holes packets. Only recreating outbound sockets via
     * stop→start reliably recovers the tunnel.
     */
    internal fun onNetworkChanged() {
        if (status.value != Status.Started) return
        val now = System.currentTimeMillis()
        if (now - lastNetworkRestart < MIN_NETWORK_RESTART_INTERVAL_MS) {
            AppLog.d(TAG, "Skipping network-change restart — too soon since last one")
            return
        }
        lastNetworkRestart = now

        GlobalScope.launch(Dispatchers.IO) {
            delay(NETWORK_CHANGE_RESTART_DELAY_MS)
            if (status.value != Status.Started) return@launch

            val ctx = vpnService ?: return@launch
            val config = configContent ?: return@launch
            AppLog.i(TAG, "Network changed — restarting VPN to rebind outbound sockets")
            withContext(Dispatchers.Main) {
                restart(ctx, config)
            }
        }
    }

    /**
     * Called by VPNService when ACTION_SCREEN_ON fires. Delegates to the
     * health check, which forces a urltest refresh and restarts the tunnel
     * if the cached post-Doze delays turn out to be stale.
     */
    internal fun onScreenOn() {
        if (status.value != Status.Started) return
        val scope = healthScope ?: return
        healthCheck?.onWakeFromSleep(scope)
    }

    /**
     * Called by VPNService when ACTION_SCREEN_OFF fires. Just records the
     * timestamp so the screen-on handler can gate on sleep duration.
     */
    internal fun onScreenOff() {
        if (status.value != Status.Started) return
        healthCheck?.onScreenOff()
    }

    /**
     * Called when the tunnel connectivity probe (real HTTP request through
     * the VPN network) fails repeatedly. Does a full VPN restart because a
     * dead Hysteria2 UDP outbound is only recoverable by rebinding sockets.
     */
    private fun onConnectivityLost(force: Boolean = false) {
        if (status.value != Status.Started) {
            AppLog.w(TAG, "Skipping connectivity restart — status=${status.value}, stopping stale health check")
            stopHealthCheck()
            return
        }
        val now = System.currentTimeMillis()
        val elapsed = now - lastConnectivityRestart
        val cooldownActive = elapsed < MIN_RESTART_INTERVAL_MS

        if (cooldownActive) {
            if (force && consecutiveForceRestarts < MAX_FORCE_RESTARTS) {
                consecutiveForceRestarts++
                AppLog.w(TAG, "Force restart #$consecutiveForceRestarts bypassing ${elapsed / 1000}s cooldown")
            } else {
                AppLog.w(TAG, "Skipping connectivity restart — last restart was ${elapsed / 1000}s ago" +
                    if (force) " (force limit reached)" else "")
                return
            }
        } else {
            consecutiveForceRestarts = 0
        }
        lastConnectivityRestart = now

        val ctx = vpnService ?: return
        val config = configContent ?: return
        AppLog.e(TAG, "Tunnel connectivity lost — full VPN restart")
        GlobalScope.launch(Dispatchers.Main) {
            restart(ctx, config)
        }
    }

    /**
     * Called when health check can't reach libbox's CommandServer
     * (command.sock missing / ping timeouts). A reload via
     * startOrReloadService() does NOT help here: that call only swaps config
     * inside a live CommandServer, it does not recreate the unix-socket
     * listener. The listener is created once in server.start() during
     * onStartCommand(), so once the Go goroutine behind it dies, the only
     * recovery is a full stop→start — the same path the user takes by
     * toggling VPN off/on.
     *
     * Shares throttle with onConnectivityLost: both paths can fire on the
     * same incident; we want at most one restart per MIN_RESTART_INTERVAL_MS.
     */
    private fun onVpnUnhealthy() {
        if (status.value != Status.Started) {
            // Orphan health check firing against a torn-down (or superseded)
            // BoxService. Kill it and bail — if a real recovery is needed the
            // next start() will spin up its own health check.
            AppLog.w(TAG, "Skipping unhealthy restart — status=${status.value}, stopping stale health check")
            stopHealthCheck()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastConnectivityRestart < MIN_RESTART_INTERVAL_MS) {
            AppLog.w(TAG, "Skipping unhealthy restart — last restart was ${(now - lastConnectivityRestart) / 1000}s ago")
            return
        }
        lastConnectivityRestart = now

        val ctx = vpnService ?: return
        val config = configContent ?: return
        AppLog.e(TAG, "Health check reported VPN unhealthy — full VPN restart")
        GlobalScope.launch(Dispatchers.Main) {
            restart(ctx, config)
        }
    }

    private fun rotateClashApiPort(config: String): String {
        return try {
            val json = JSONObject(config)
            val experimental = json.optJSONObject("experimental")
            if (experimental == null) {
                AppLog.w(TAG, "No experimental section in config — skipping Clash API port rotation")
                return config
            }
            val clashApi = experimental.optJSONObject("clash_api")
            if (clashApi == null) {
                AppLog.w(TAG, "No clash_api in config — skipping port rotation")
                return config
            }
            val port = try {
                java.net.ServerSocket(0).use { it.localPort }
            } catch (_: Exception) {
                10000 + (System.nanoTime() % 50000).toInt().let { if (it < 0) -it else it }
            }
            clashApi.put("external_controller", "127.0.0.1:$port")
            AppLog.i(TAG, "Rotated Clash API port to $port")
            json.toString()
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to rotate Clash API port: ${e.message}")
            config
        }
    }

    internal fun stopAndAlert(type: Alert, message: String) {
        AppLog.e(TAG, "stopAndAlert(type=$type, message=$message)")
        status.postValue(Status.Stopping)
        binder?.broadcastAlert(type, message)
        service?.stopSelf()
        onServiceDestroy()
    }

    internal fun getBinder(): IBinder? = binder

    // CommandServerHandler interface
    override fun serviceStop() {
        val currentService = service ?: return
        status.postValue(Status.Stopping)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                commandServer?.close()
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                currentService.stopSelf()
            }
        }
    }

    override fun serviceReload() {
        val config = configContent ?: return
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val overrideOptions = OverrideOptions()
                commandServer?.startOrReloadService(config, overrideOptions)
            } catch (e: Exception) {
                AppLog.e(TAG, "Reload failed", e)
            }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? = null

    override fun setSystemProxyEnabled(enabled: Boolean) {}

    override fun writeDebugMessage(message: String) {
        AppLog.d(TAG, "libbox: $message")
    }
}
