package com.sbcfg.manager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sbcfg.manager.domain.ConfigManager
import com.sbcfg.manager.integration.DeepLinkHandler
import com.sbcfg.manager.ui.navigation.NavGraph
import com.sbcfg.manager.ui.theme.SbcfgTheme
import com.sbcfg.manager.util.AppLog
import com.sbcfg.manager.vpn.BoxService
import com.sbcfg.manager.vpn.ServiceConnection
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var configManager: ConfigManager

    /** Config JSON to pass to BoxService after VPN permission is granted */
    private var pendingConfigJson: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AppLog.i("Activity", "Notification permission ${if (granted) "granted" else "denied"}")
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            AppLog.i("Activity", "VPN permission granted by user, starting VPN")
            doStartVpn()
        } else {
            AppLog.w("Activity", "VPN permission denied by user")
            Toast.makeText(this, "VPN-разрешение не предоставлено", Toast.LENGTH_SHORT).show()
        }
    }

    private var serviceConnection: ServiceConnection? = null
    private val pendingDeepLinkUrl: MutableState<String?> = mutableStateOf(null)

    override fun onResume() {
        super.onResume()
        // Reapply edge-to-edge on resume — some OEM ROMs (Vivo) reset window decor
        // when the activity is brought back from the recents stack, hiding the
        // TopAppBar icons under the system status bar.
        enableEdgeToEdge()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()

        // Handle deep link from intent
        handleDeepLink(intent)

        setContent {
            SbcfgTheme {
                var startDestination by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    val hasConfig = configManager.hasConfig()
                    startDestination = when {
                        pendingDeepLinkUrl.value != null -> "setup"
                        hasConfig -> "dashboard"
                        else -> "setup"
                    }
                    AppLog.i(
                        "Activity",
                        "Start destination=$startDestination (hasConfig=$hasConfig, " +
                            "deepLink=${pendingDeepLinkUrl.value != null})"
                    )
                }

                val dest = startDestination
                if (dest != null) {
                    NavGraph(
                        startDestination = dest,
                        deepLinkUrl = pendingDeepLinkUrl.value,
                        onStartVpn = { configJson ->
                            AppLog.i(
                                "Activity",
                                "onStartVpn callback, configJson=${configJson?.length}"
                            )
                            pendingConfigJson = configJson
                            requestVpnPermission()
                        },
                        onStopVpn = {
                            AppLog.i("Activity", "onStopVpn callback")
                            BoxService.stop()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onDestroy() {
        serviceConnection?.disconnect(this)
        serviceConnection = null
        super.onDestroy()
    }

    private fun handleDeepLink(intent: Intent?) {
        val result = DeepLinkHandler.parse(intent?.data) ?: return
        pendingDeepLinkUrl.value = result.configUrl
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestVpnPermission() {
        AppLog.i("Activity", "requestVpnPermission() called")
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            AppLog.i("Activity", "VPN permission needed, launching system dialog")
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            AppLog.i("Activity", "VPN permission already granted, starting VPN")
            doStartVpn()
        }
    }

    private fun doStartVpn() {
        val config = pendingConfigJson
        if (config == null) {
            AppLog.e("Activity", "doStartVpn() but pendingConfigJson is null!")
            return
        }
        AppLog.i("Activity", "doStartVpn() calling BoxService.start(), config length=${config.length}")
        BoxService.start(this, config)
    }
}
