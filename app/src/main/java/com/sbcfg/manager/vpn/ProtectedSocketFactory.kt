package com.sbcfg.manager.vpn

import com.sbcfg.manager.util.AppLog
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * [SocketFactory] that calls [android.net.VpnService.protect] on every socket
 * it produces so the socket bypasses the VPN tunnel and goes through the
 * underlying physical interface instead.
 *
 * Used by all app-level HTTP clients (config fetch, GitHub update check,
 * APK download) to replace the old process-wide
 * `addDisallowedApplication(packageName)` bypass — see [VpnServiceHolder]
 * for why we flipped the default.
 *
 * If no VpnService is active (VPN off), protect() is a no-op from our
 * perspective — the socket just routes normally. If protect() itself
 * fails (race on service teardown, OS refusal), we log and let the socket
 * through; worst case it goes via tun, which is still a valid route, just
 * not the one we wanted.
 */
class ProtectedSocketFactory(
    private val delegate: SocketFactory = getDefault(),
) : SocketFactory() {

    private fun protect(socket: Socket): Socket {
        val service = VpnServiceHolder.get() ?: return socket
        try {
            if (!service.protect(socket)) {
                AppLog.w(TAG, "protect() returned false for $socket")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "protect() threw: ${e.message}")
        }
        return socket
    }

    override fun createSocket(): Socket = protect(delegate.createSocket())

    override fun createSocket(host: String, port: Int): Socket =
        protect(createUnconnected()).also { it.connect(java.net.InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        protect(createUnconnected()).also {
            it.bind(java.net.InetSocketAddress(localHost, localPort))
            it.connect(java.net.InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        protect(createUnconnected()).also { it.connect(java.net.InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        protect(createUnconnected()).also {
            it.bind(java.net.InetSocketAddress(localAddress, localPort))
            it.connect(java.net.InetSocketAddress(address, port))
        }

    // OkHttp always calls createSocket() (the no-arg form) and then connects
    // itself, but we implement the other overloads defensively so any caller
    // that uses them — including Retrofit/Java-stdlib code paths — still gets
    // the protected socket. The protect() call must happen BEFORE connect(),
    // otherwise the SYN already left via tun.
    private fun createUnconnected(): Socket = Socket()

    companion object {
        private const val TAG = "ProtectedSocketFactory"
    }
}
