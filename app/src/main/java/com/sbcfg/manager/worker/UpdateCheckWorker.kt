package com.sbcfg.manager.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sbcfg.manager.MainActivity
import com.sbcfg.manager.R
import com.sbcfg.manager.update.GitHubUpdateChecker
import com.sbcfg.manager.util.AppLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val updateChecker: GitHubUpdateChecker
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        AppLog.i(TAG, "Starting periodic update check")
        return try {
            val updateInfo = updateChecker.check()
            if (updateInfo != null) {
                AppLog.i(TAG, "Update available: ${updateInfo.versionName}")
                showUpdateNotification(updateInfo.versionName)
            } else {
                AppLog.i(TAG, "No update available")
            }
            Result.success()
        } catch (e: java.io.IOException) {
            AppLog.w(TAG, "Network error, will retry: ${e.message}")
            Result.retry()
        } catch (e: Exception) {
            AppLog.e(TAG, "Update check error", e)
            Result.failure()
        }
    }

    private fun showUpdateNotification(versionName: String) {
        val notificationManager = NotificationManagerCompat.from(appContext)

        // Create channel (required for API 26+)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Обновления приложения",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_vpn)
            .setContentTitle("Доступно обновление")
            .setContentText("Версия $versionName доступна для загрузки")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"
        const val WORK_NAME = "UpdateCheck"
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFICATION_ID = 1001
    }
}
