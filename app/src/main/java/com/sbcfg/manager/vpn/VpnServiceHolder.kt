package com.sbcfg.manager.vpn

import android.net.VpnService
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide reference to the currently active [VpnService].
 *
 * Needed because we no longer exclude our own package from the tunnel
 * (previously `addDisallowedApplication(packageName)` in [VPNService.openTun]).
 * After that change any socket our process creates routes through tun1 by
 * default. The [ProtectedSocketFactory] used by our OkHttp clients reads
 * this holder and calls [VpnService.protect] on every outgoing socket so
 * the config-fetch / GitHub-update / APK-download paths keep bypassing the
 * tunnel — that's the same direct-network behavior the old exclusion gave
 * us, but scoped to just those sockets instead of the whole process.
 *
 * The upside of flipping the default: VpnHealthCheck can now open a real
 * end-to-end probe socket (DNS query to 172.19.0.2:53, HTTP GET through
 * tun) without EPERM.
 */
object VpnServiceHolder {
    private val ref = AtomicReference<VpnService?>()

    fun set(service: VpnService) {
        ref.set(service)
    }

    fun clear(service: VpnService) {
        // Compare-and-set guards against the race where a new VpnService
        // instance has already registered itself before the previous one's
        // onDestroy runs — we don't want to null out the live reference.
        ref.compareAndSet(service, null)
    }

    fun get(): VpnService? = ref.get()
}
