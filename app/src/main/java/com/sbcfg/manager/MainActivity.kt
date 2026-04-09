package com.sbcfg.manager

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.sbcfg.manager.integration.DeepLinkHandler
import com.sbcfg.manager.integration.QrScannerActivity
import com.sbcfg.manager.ui.navigation.NavGraph
import com.sbcfg.manager.ui.theme.SbcfgTheme
import com.sbcfg.manager.util.AppLog
import com.sbcfg.manager.vpn.BoxService
import com.sbcfg.manager.vpn.ServiceConnection
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Config JSON to pass to BoxService after VPN permission is granted */
    private var pendingConfigJson: String? = null

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

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val url = result.data?.getStringExtra(QrScannerActivity.RESULT_URL)
            if (url != null) {
                pendingDeepLinkUrl = url
            }
        }
    }

    private var serviceConnection: ServiceConnection? = null
    private var pendingDeepLinkUrl: String? = null

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

        // Handle deep link from intent
        handleDeepLink(intent)

        setContent {
            SbcfgTheme {
                NavGraph(
                    startDestination = "dashboard",
                    onScanQr = { launchQrScanner() },
                    deepLinkUrl = pendingDeepLinkUrl?.also { pendingDeepLinkUrl = null },
                    onStartVpn = { configJson ->
                        AppLog.i("Activity", "onStartVpn callback, configJson=${configJson?.length}")
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
        pendingDeepLinkUrl = result.configUrl
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

    private fun launchQrScanner() {
        val intent = Intent(this, QrScannerActivity::class.java)
        qrScannerLauncher.launch(intent)
    }
}
