package com.sbcfg.manager.vpn

import android.os.RemoteCallbackList
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.sbcfg.manager.aidl.IService
import com.sbcfg.manager.aidl.IServiceCallback
import com.sbcfg.manager.constant.Alert
import com.sbcfg.manager.constant.Status

class ServiceBinder(private val status: LiveData<Status>) : IService.Stub() {

    private val callbacks = RemoteCallbackList<IServiceCallback>()

    private val statusObserver = Observer<Status> { newStatus ->
        broadcast(newStatus)
    }

    init {
        status.observeForever(statusObserver)
    }

    override fun getStatus(): Int = status.value?.ordinal ?: 0

    override fun registerCallback(callback: IServiceCallback) {
        callbacks.register(callback)
    }

    override fun unregisterCallback(callback: IServiceCallback) {
        callbacks.unregister(callback)
    }

    fun broadcast(status: Status) {
        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    callbacks.getBroadcastItem(i).onServiceStatusChanged(status.ordinal)
                } catch (_: Exception) {}
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    fun broadcastAlert(type: Alert, message: String) {
        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    callbacks.getBroadcastItem(i).onServiceAlert(type.ordinal, message)
                } catch (_: Exception) {}
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    fun destroy() {
        status.removeObserver(statusObserver)
        callbacks.kill()
    }
}
