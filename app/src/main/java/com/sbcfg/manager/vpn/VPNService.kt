package com.sbcfg.manager.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.sbcfg.manager.util.AppLog
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetAddress
import java.net.InetSocketAddress

class VPNService : VpnService(), PlatformInterface {

    companion object {
        private const val TAG = "VPNService"
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var defaultNetworkMonitor: DefaultNetworkMonitor? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        // Register ourselves so ProtectedSocketFactory can bypass the tunnel
        // for app-level HTTP clients. Must happen before any socket is created
        // (OkHttp is lazy, but workers may kick in immediately after startup).
        VpnServiceHolder.set(this)
        // Hysteria2 UDP dies after Doze strands the NAT binding (typically
        // ~60s of radio silence). No network-change callback fires on unlock
        // if wifi stayed the same, so we piggyback on ACTION_SCREEN_ON to
        // trigger a urltest refresh and, if the tunnel is actually dead, a
        // restart. SCREEN_ON is a protected system broadcast.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> BoxService.onScreenOn()
                    Intent.ACTION_SCREEN_OFF -> BoxService.onScreenOff()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = receiver
        AppLog.i(TAG, "Screen receiver registered (on+off)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i(TAG, "onStartCommand() called, hasConfig=${intent?.hasExtra("config")}")
        BoxService.onStartCommand(this, intent)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return BoxService.getBinder()
    }

    override fun onRevoke() {
        AppLog.i(TAG, "onRevoke() called — VPN permission revoked by system")
        BoxService.stop()
    }

    fun closeTun() {
        try {
            defaultNetworkMonitor?.stop()
            defaultNetworkMonitor = null
            AppLog.i(TAG, "Network monitor stopped")
        } catch (e: Exception) {
            AppLog.e(TAG, "Error stopping network monitor", e)
        }
        try {
            tunFd?.close()
            tunFd = null
            AppLog.i(TAG, "TUN fd closed")
        } catch (e: Exception) {
            AppLog.e(TAG, "Error closing TUN fd", e)
        }
    }

    override fun onDestroy() {
        AppLog.i(TAG, "onDestroy() called")
        try {
            screenReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            AppLog.w(TAG, "Error unregistering screen receiver: ${e.message}")
        }
        screenReceiver = null
        closeTun()
        BoxService.onServiceDestroy()
        VpnServiceHolder.clear(this)
        super.onDestroy()
    }

    // PlatformInterface implementation

    override fun openTun(options: TunOptions): Int {
        AppLog.i(TAG, "openTun() called, mtu=${options.mtu}, autoRoute=${options.autoRoute}")
        val builder = Builder()
            .setSession("sing-box Config Manager")
            .setMtu(options.mtu)

        // Add IPv4 addresses from RoutePrefixIterator
        val inet4Iter = options.inet4Address
        while (inet4Iter.hasNext()) {
            val prefix = inet4Iter.next()
            builder.addAddress(InetAddress.getByName(prefix.address()), prefix.prefix())
        }

        // Add IPv6 addresses from RoutePrefixIterator
        val inet6Iter = options.inet6Address
        while (inet6Iter.hasNext()) {
            val prefix = inet6Iter.next()
            builder.addAddress(InetAddress.getByName(prefix.address()), prefix.prefix())
        }

        // Add IPv4 routes
        if (options.autoRoute) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        } else {
            val inet4RouteIter = options.inet4RouteAddress
            while (inet4RouteIter.hasNext()) {
                val prefix = inet4RouteIter.next()
                builder.addRoute(InetAddress.getByName(prefix.address()), prefix.prefix())
            }
            val inet6RouteIter = options.inet6RouteAddress
            while (inet6RouteIter.hasNext()) {
                val prefix = inet6RouteIter.next()
                builder.addRoute(InetAddress.getByName(prefix.address()), prefix.prefix())
            }
        }

        // Add DNS servers
        try {
            val dnsBox = options.dnsServerAddress
            val dnsAddr = dnsBox.value
            AppLog.i(TAG, "DNS server address from options: '$dnsAddr'")
            if (!dnsAddr.isNullOrEmpty()) {
                builder.addDnsServer(dnsAddr)
                AppLog.i(TAG, "Added DNS server: $dnsAddr")
            } else {
                builder.addDnsServer("1.1.1.1")
                AppLog.w(TAG, "DNS address empty, using fallback 1.1.1.1")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "DNS address error: ${e.message}, using fallback 1.1.1.1")
            builder.addDnsServer("1.1.1.1")
        }

        // Exclude packages (per-app VPN)
        try {
            val iterator = options.excludePackage
            while (iterator.hasNext()) {
                try {
                    builder.addDisallowedApplication(iterator.next())
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Include packages (per-app VPN). In include-mode, only UIDs explicitly
        // added to the allow-list get routed through tun — every other UID
        // (including ours) goes straight to wlan0. We need to add our own
        // package here so VpnHealthCheck probe sockets actually reach
        // 172.19.0.2 via tun instead of being dropped on wlan0.
        var inIncludeMode = false
        try {
            val iterator = options.includePackage
            while (iterator.hasNext()) {
                inIncludeMode = true
                try {
                    builder.addAllowedApplication(iterator.next())
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        if (inIncludeMode) {
            try {
                builder.addAllowedApplication(packageName)
                AppLog.i(TAG, "Added own package to allow-list (for health probes): $packageName")
            } catch (_: Exception) {}
        }

        // NOTE: we intentionally do NOT call addDisallowedApplication(packageName)
        // here. Excluding the whole process prevents VpnHealthCheck from opening
        // real probe sockets through tun (EPERM on bindSocket). Instead, app-level
        // HTTP clients bypass the tunnel per-socket via ProtectedSocketFactory
        // (see AppModule.provideOkHttpClient). Health-probe sockets (DNS to
        // 172.19.0.2:53, HTTP to an in-tunnel health host) now route through tun
        // as intended. If you add a new network client, reuse the DI OkHttpClient
        // or it WILL route through the VPN.

        val pfd = builder.establish()
        if (pfd == null) {
            AppLog.e(TAG, "builder.establish() returned null — VPN permission denied")
            throw Exception("VPN permission denied")
        }
        tunFd = pfd
        AppLog.i(TAG, "TUN established, fd=${pfd.fd}")
        return pfd.fd
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun findConnectionOwner(
        protocol: Int,
        srcAddr: String,
        srcPort: Int,
        dstAddr: String,
        dstPort: Int
    ): ConnectionOwner {
        val owner = ConnectionOwner()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val uid = getConnectivityManager()
                    .getConnectionOwnerUid(
                        protocol,
                        InetSocketAddress(srcAddr, srcPort),
                        InetSocketAddress(dstAddr, dstPort)
                    )
                owner.userId = uid
                if (uid > 0) {
                    val packages = packageManager.getPackagesForUid(uid)?.toList().orEmpty()
                    if (packages.isNotEmpty()) {
                        owner.setAndroidPackageNames(object : StringIterator {
                            private val iter = packages.iterator()
                            override fun hasNext(): Boolean = iter.hasNext()
                            override fun next(): String = iter.next()
                            override fun len(): Int = packages.size
                        })
                    }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "findConnectionOwner error", e)
            }
        }
        return owner
    }

    override fun clearDNSCache() {
        // no-op
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultNetworkMonitor?.stop()
        defaultNetworkMonitor = null
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val monitor = DefaultNetworkMonitor(getConnectivityManager())
        monitor.onNetworkChanged = { BoxService.onNetworkChanged() }
        defaultNetworkMonitor = monitor
        monitor.start(listener)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val cm = getConnectivityManager()
        val networks = cm.allNetworks
        val javaInterfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        val result = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()

        for (network in networks) {
            val linkProps = cm.getLinkProperties(network) ?: continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val ifName = linkProps.interfaceName ?: continue
            val javaIf = javaInterfaces.find { it.name == ifName } ?: continue

            val boxIf = io.nekohasekai.libbox.NetworkInterface()
            boxIf.name = ifName
            boxIf.index = javaIf.index
            try { boxIf.mtu = javaIf.mtu } catch (_: Exception) {}
            boxIf.type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> io.nekohasekai.libbox.Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> io.nekohasekai.libbox.Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> io.nekohasekai.libbox.Libbox.InterfaceTypeEthernet
                else -> io.nekohasekai.libbox.Libbox.InterfaceTypeOther
            }

            // DNS servers
            val dnsAddrs = linkProps.dnsServers.mapNotNull { it.hostAddress }
            boxIf.dnsServer = object : StringIterator {
                private val iter = dnsAddrs.iterator()
                override fun hasNext(): Boolean = iter.hasNext()
                override fun next(): String = iter.next()
                override fun len(): Int = dnsAddrs.size
            }

            // Addresses — must include prefix length (CIDR notation)
            val addrs = linkProps.linkAddresses.mapNotNull { la ->
                val addr = la.address?.hostAddress ?: return@mapNotNull null
                "${addr}/${la.prefixLength}"
            }
            boxIf.addresses = object : StringIterator {
                private val iter = addrs.iterator()
                override fun hasNext(): Boolean = iter.hasNext()
                override fun next(): String = iter.next()
                override fun len(): Int = addrs.size
            }

            result.add(boxIf)
        }

        AppLog.i(TAG, "getInterfaces: ${result.map { "${it.name}(idx=${it.index})" }}")

        return object : NetworkInterfaceIterator {
            private val iter = result.iterator()
            override fun hasNext(): Boolean = iter.hasNext()
            override fun next(): io.nekohasekai.libbox.NetworkInterface = iter.next()
        }
    }

    override fun includeAllNetworks(): Boolean = false

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun readWIFIState(): WIFIState = WIFIState("", "")

    override fun sendNotification(notification: Notification) {
        // no-op: we handle notifications ourselves via ServiceNotification
    }

    override fun systemCertificates(): StringIterator? = null

    override fun underNetworkExtension(): Boolean = false

    // Android 10+ blocks /proc/net/* via SELinux. sing-box must rely on
    // findConnectionOwner() / Android's ConnectivityManager.getConnectionOwnerUid()
    // for per-package routing — otherwise package_name rules never match.
    override fun useProcFS(): Boolean = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q

    private fun getConnectivityManager(): ConnectivityManager {
        return getSystemService(ConnectivityManager::class.java)
    }
}
