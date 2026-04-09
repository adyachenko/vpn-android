package com.sbcfg.manager.integration

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.lifecycle.Observer
import com.sbcfg.manager.MainActivity
import com.sbcfg.manager.constant.Status
import com.sbcfg.manager.domain.ConfigManager
import com.sbcfg.manager.util.AppLog
import com.sbcfg.manager.vpn.BoxService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class VpnTileService : TileService() {

    companion object {
        private const val TAG = "VpnTile"
    }

    @Inject lateinit var configManager: ConfigManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusObserver: Observer<Status>? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTile(BoxService.status.value ?: Status.Stopped)
        val observer = Observer<Status> { status -> updateTile(status) }
        statusObserver = observer
        BoxService.status.observeForever(observer)
    }

    override fun onStopListening() {
        super.onStopListening()
        statusObserver?.let { BoxService.status.removeObserver(it) }
        statusObserver = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onClick() {
        super.onClick()
        val status = BoxService.status.value ?: Status.Stopped
        AppLog.i(TAG, "Tile clicked, current status=$status")
        when (status) {
            Status.Started, Status.Starting -> {
                BoxService.stop()
            }
            else -> {
                startVpnFromTile()
            }
        }
    }

    private fun startVpnFromTile() {
        // VPN permission required — launch the activity so the user can grant it.
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            AppLog.i(TAG, "VPN permission needed, launching activity")
            launchActivity()
            return
        }
        scope.launch {
            try {
                val config = configManager.generateConfigJson()
                BoxService.start(applicationContext, config)
                AppLog.i(TAG, "VPN started from tile")
            } catch (e: Exception) {
                AppLog.e(TAG, "Tile start failed: ${e.message}")
                launchActivity()
            }
        }
    }

    private fun launchActivity() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(status: Status) {
        val tile = qsTile ?: return
        tile.state = when (status) {
            Status.Started -> Tile.STATE_ACTIVE
            Status.Starting, Status.Stopping -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "SBoxy VPN"
        tile.contentDescription = when (status) {
            Status.Started -> "VPN включён"
            Status.Starting -> "VPN включается"
            Status.Stopping -> "VPN выключается"
            else -> "VPN выключен"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (status) {
                Status.Started -> "Подключено"
                Status.Starting -> "Подключение..."
                Status.Stopping -> "Отключение..."
                else -> "Отключено"
            }
        }
        tile.updateTile()
    }
}
