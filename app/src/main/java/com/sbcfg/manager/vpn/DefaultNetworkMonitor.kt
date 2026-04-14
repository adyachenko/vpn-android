package com.sbcfg.manager.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.sbcfg.manager.util.AppLog
import io.nekohasekai.libbox.InterfaceUpdateListener
import java.net.NetworkInterface

class DefaultNetworkMonitor(
    private val connectivity: ConnectivityManager
) {
    companion object {
        private const val TAG = "DefaultNetworkMonitor"
    }

    private var listener: InterfaceUpdateListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultNetwork: Network? = null

    // Skip the first onAvailable callback: it's the baseline, not a change.
    // Any subsequent onAvailable (including after onLost→recovery) IS a change
    // that must trigger outbound socket rebind.
    private var initialized = false

    /** Called when the active network changes (wifi↔mobile, reconnect after sleep). */
    var onNetworkChanged: (() -> Unit)? = null

    fun start(listener: InterfaceUpdateListener) {
        this.listener = listener
        this.initialized = false

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previousNetwork = defaultNetwork
                defaultNetwork = network
                checkDefaultInterfaceUpdate(network)

                if (!initialized) {
                    initialized = true
                    AppLog.d(TAG, "Initial default network observed: $network")
                    return
                }

                if (previousNetwork != network) {
                    AppLog.i(TAG, "Default network changed ($previousNetwork -> $network) — notifying")
                    onNetworkChanged?.invoke()
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (network == defaultNetwork) {
                    checkDefaultInterfaceUpdate(network)
                }
            }

            override fun onLost(network: Network) {
                if (network == defaultNetwork) {
                    defaultNetwork = null
                    listener.updateDefaultInterface("", -1, false, false)
                    AppLog.d(TAG, "Default network lost")
                }
            }
        }

        networkCallback = callback

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()

        val mainHandler = Handler(Looper.getMainLooper())

        // Use requestNetwork (not registerNetworkCallback) to get the actual default network
        // registerDefaultNetworkCallback returns VPN on Android P+
        if (Build.VERSION.SDK_INT >= 31) {
            connectivity.registerBestMatchingNetworkCallback(request, callback, mainHandler)
        } else {
            // minSdk = 28, so API 28–30: requestNetwork with Handler
            connectivity.requestNetwork(request, callback, mainHandler)
        }

        // Fire initial update with active network
        defaultNetwork = connectivity.activeNetwork
        defaultNetwork?.let { checkDefaultInterfaceUpdate(it) }
    }

    fun stop() {
        networkCallback?.let {
            try {
                connectivity.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
        listener = null
        defaultNetwork = null
        initialized = false
    }

    private fun checkDefaultInterfaceUpdate(network: Network) {
        try {
            val linkProperties = connectivity.getLinkProperties(network) ?: return
            val interfaceName = linkProperties.interfaceName ?: return

            var interfaceIndex = 0
            for (attempt in 0 until 10) {
                try {
                    interfaceIndex = NetworkInterface.getByName(interfaceName)?.index ?: 0
                    if (interfaceIndex > 0) break
                } catch (_: Exception) {
                    Thread.sleep(100)
                }
            }

            AppLog.d(TAG, "updateDefaultInterface: $interfaceName idx=$interfaceIndex")
            listener?.updateDefaultInterface(interfaceName, interfaceIndex, false, false)
        } catch (e: Exception) {
            AppLog.e(TAG, "Error checking default interface", e)
        }
    }
}
