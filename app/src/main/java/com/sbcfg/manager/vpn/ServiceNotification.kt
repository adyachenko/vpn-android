package com.sbcfg.manager.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sbcfg.manager.MainActivity
import com.sbcfg.manager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ServiceNotification(private val service: Service) {

    companion object {
        private const val CHANNEL_ID = "vpn_service"
        private const val NOTIFICATION_ID = 1
        private const val DISMISS_DELAY_MS = 3_000L
    }

    private val notificationManager = service.getSystemService(NotificationManager::class.java)
    private var dismissJob: Job? = null

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private val contentIntent by lazy {
        PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun build(text: String, ongoing: Boolean = true) =
        NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle("Config Manager")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(ongoing)
            .build()

    fun show(text: String) {
        dismissJob?.cancel()
        service.startForeground(NOTIFICATION_ID, build(text))
    }

    fun update(text: String) {
        notificationManager.notify(NOTIFICATION_ID, build(text))
    }

    fun close() {
        service.stopForeground(Service.STOP_FOREGROUND_DETACH)
    }

    fun closeStopped() {
        service.stopForeground(Service.STOP_FOREGROUND_DETACH)
        notificationManager.notify(NOTIFICATION_ID, build("VPN остановлен", ongoing = false))
        dismissJob = GlobalScope.launch(Dispatchers.Main) {
            delay(DISMISS_DELAY_MS)
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    fun remove() {
        dismissJob?.cancel()
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }
}
