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

class ServiceNotification(private val service: Service) {

    companion object {
        private const val CHANNEL_ID = "vpn_service"
        private const val NOTIFICATION_ID = 1
    }

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
            val manager = service.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun show(profileName: String) {
        val contentIntent = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle("Config Manager")
            .setContentText("VPN подключён — $profileName")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        service.startForeground(NOTIFICATION_ID, notification)
    }

    fun close() {
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }
}
