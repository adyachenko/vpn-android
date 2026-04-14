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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var binder: ServiceBinder? = null
    private var healthCheck: VpnHealthCheck? = null
    private var healthScope: CoroutineScope? = null

    @Volatile
    private var lastConnectivityRestart: Long = 0
    private const val MIN_RESTART_INTERVAL_MS = 60_000L

    @Volatile
    private var lastNetworkRestart: Long = 0
    // Throttle network-change restarts: avoid restart storm during fast wifi/cellular handoffs.
    private const val MIN_NETWORK_RESTART_INTERVAL_MS = 15_000L
    // Wait a bit after network change before acting so the new link stabilises (DHCP, RA, DNS).
    private const val NETWORK_CHANGE_RESTART_DELAY_MS = 2_500L

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

    fun stop() {
        AppLog.i(TAG, "stop() called, current status=${status.value}")
        val currentService = service
        if (currentService == null) {
            AppLog.w(TAG, "stop() but service is null")
            return
        }
        if (status.value != Status.Started) {
            AppLog.w(TAG, "stop() but status is ${status.value}, not Started")
            return
        }
        status.postValue(Status.Stopping)
        AppLog.i(TAG, "Status set to Stopping")
        stopHealthCheck()
        GlobalScope.launch(Dispatchers.IO) {
            // 1. Close TUN fd
            vpnService?.closeTun()
            AppLog.i(TAG, "TUN closed")

            // 2. Stop sing-box engine (releases dup'd TUN fd)
            try {
                commandServer?.closeService()
                AppLog.i(TAG, "closeService() done")
            } catch (e: Exception) {
                AppLog.e(TAG, "closeService() error", e)
            }

            // 3. Close command server
            try {
                commandServer?.close()
                AppLog.i(TAG, "CommandServer closed")
            } catch (e: Exception) {
                AppLog.e(TAG, "CommandServer close error", e)
            }

            // 4. Remove foreground notification only after engine is fully stopped.
            // Moving this after closeService() is critical: stopForeground() drops
            // the service priority, and without a foreground Activity (e.g. when
            // stopping from the QS tile) the system may kill the process before the
            // engine releases its dup'd TUN fd — leaving the VPN icon stuck.
            withContext(Dispatchers.Main) {
                notification?.close()
            }

            // 5. Stop Android service
            withContext(Dispatchers.Main) {
                AppLog.i(TAG, "stopSelf()")
                startedAt = null
                status.postValue(Status.Stopped)
                currentService.stopSelf()
            }
        }
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
        if (status.value != Status.Started) {
            AppLog.i(TAG, "Not running — saving config only")
            this.configContent = newConfig
            return
        }
        GlobalScope.launch(Dispatchers.Main) {
            stop()
            // Wait for the engine to actually shut down before re-creating it.
            val deadline = System.currentTimeMillis() + 5000
            while (status.value != Status.Stopped && System.currentTimeMillis() < deadline) {
                delay(100)
            }
            if (status.value != Status.Stopped) {
                AppLog.w(TAG, "restart() timed out waiting for stop")
                return@launch
            }
            delay(300)
            start(context, newConfig)
            AppLog.i(TAG, "restart() completed")
        }
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

                // Dump config to file for debugging
                try {
                    val debugFile = java.io.File(vpnService!!.filesDir, "debug-config.json")
                    debugFile.writeText(config)
                    AppLog.i(TAG, "Config dumped to ${debugFile.absolutePath}")
                } catch (_: Exception) {}

                AppLog.i(TAG, "Calling startOrReloadService()...")
                val overrideOptions = OverrideOptions()
                server.startOrReloadService(config, overrideOptions)
                AppLog.i(TAG, "startOrReloadService() completed successfully")

                withContext(Dispatchers.Main) {
                    startedAt = System.currentTimeMillis()
                    status.postValue(Status.Started)
                    AppLog.i(TAG, "Status set to Started, startedAt=$startedAt")
                    notification?.show("VPN подключён")
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
        try {
            commandServer?.closeService()
        } catch (_: Exception) {}
        try {
            commandServer?.close()
        } catch (_: Exception) {}
        commandServer = null
        notification?.close()
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
    }

    private fun stopHealthCheck() {
        healthCheck?.stop()
        healthCheck = null
        healthScope?.cancel()
        healthScope = null
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
     * Called when the tunnel connectivity probe (real HTTP request through
     * the VPN network) fails repeatedly. Does a full VPN restart because a
     * dead Hysteria2 UDP outbound is only recoverable by rebinding sockets.
     */
    private fun onConnectivityLost() {
        val now = System.currentTimeMillis()
        if (now - lastConnectivityRestart < MIN_RESTART_INTERVAL_MS) {
            AppLog.w(TAG, "Skipping connectivity restart — last restart was ${(now - lastConnectivityRestart) / 1000}s ago")
            return
        }
        lastConnectivityRestart = now

        val ctx = vpnService ?: return
        val config = configContent ?: return
        AppLog.e(TAG, "Tunnel connectivity lost — full VPN restart")
        GlobalScope.launch(Dispatchers.Main) {
            restart(ctx, config)
        }
    }

    private fun onVpnUnhealthy() {
        AppLog.e(TAG, "Health check reported VPN unhealthy — reloading sing-box engine")
        val config = configContent ?: return
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val overrideOptions = OverrideOptions()
                commandServer?.startOrReloadService(config, overrideOptions)
                AppLog.i(TAG, "VPN engine reloaded after health check failure")
            } catch (e: Exception) {
                AppLog.e(TAG, "Reload after health check failure failed", e)
            }
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
