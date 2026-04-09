package com.sbcfg.manager.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
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

object BoxService : CommandServerHandler {

    private const val TAG = "BoxService"

    val status = MutableLiveData(Status.Stopped)

    private var commandServer: CommandServer? = null
    private var service: Service? = null
    private var vpnService: VPNService? = null
    private var notification: ServiceNotification? = null
    private var configContent: String? = null
    private var binder: ServiceBinder? = null
    private var healthCheck: VpnHealthCheck? = null
    private var healthScope: CoroutineScope? = null

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
        notification?.close()
        GlobalScope.launch(Dispatchers.IO) {
            // 1. Close TUN fd
            vpnService?.closeTun()
            AppLog.i(TAG, "TUN closed")

            // 2. Stop sing-box engine
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

            // 4. Stop Android service
            withContext(Dispatchers.Main) {
                AppLog.i(TAG, "stopSelf()")
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
                    status.postValue(Status.Started)
                    AppLog.i(TAG, "Status set to Started")
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
            commandServer?.close()
        } catch (_: Exception) {}
        commandServer = null
        notification?.close()
        notification = null
        service = null
        vpnService = null
        binder = null
        status.postValue(Status.Stopped)
    }

    private fun startHealthCheck() {
        stopHealthCheck()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        healthScope = scope
        healthCheck = VpnHealthCheck(onUnhealthy = ::onVpnUnhealthy).also {
            it.start(scope)
        }
    }

    private fun stopHealthCheck() {
        healthCheck?.stop()
        healthCheck = null
        healthScope?.cancel()
        healthScope = null
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
