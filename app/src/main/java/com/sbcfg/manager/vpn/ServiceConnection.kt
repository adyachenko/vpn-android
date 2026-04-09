package com.sbcfg.manager.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.sbcfg.manager.aidl.IService
import com.sbcfg.manager.aidl.IServiceCallback
import com.sbcfg.manager.constant.Alert
import com.sbcfg.manager.constant.Status

class ServiceConnection(
    private val onStatusChanged: (Status) -> Unit,
    private val onAlert: (Alert, String) -> Unit
) : android.content.ServiceConnection {

    private var service: IService? = null

    private val callback = object : IServiceCallback.Stub() {
        override fun onServiceStatusChanged(status: Int) {
            onStatusChanged(Status.fromInt(status))
        }

        override fun onServiceAlert(type: Int, message: String) {
            onAlert(Alert.fromInt(type), message)
        }
    }

    fun connect(context: Context) {
        val intent = Intent(context, VPNService::class.java)
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    fun disconnect(context: Context) {
        try {
            service?.unregisterCallback(callback)
        } catch (_: Exception) {}
        try {
            context.unbindService(this)
        } catch (_: Exception) {}
        service = null
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val iService = IService.Stub.asInterface(binder) ?: return
        service = iService
        iService.registerCallback(callback)
        onStatusChanged(Status.fromInt(iService.status))
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        onStatusChanged(Status.Stopped)
    }
}
