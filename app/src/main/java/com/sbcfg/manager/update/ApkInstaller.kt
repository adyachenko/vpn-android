package com.sbcfg.manager.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.sbcfg.manager.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun install(apkFile: File) {
        AppLog.i(TAG, "Installing APK: ${apkFile.absolutePath} (${apkFile.length()} bytes)")

        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        params.setSize(apkFile.length())

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        session.use { s ->
            apkFile.inputStream().use { input ->
                s.openWrite("update.apk", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    s.fsync(output)
                }
            }

            val intent = Intent(context, InstallResultReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            s.commit(pendingIntent.intentSender)
        }

        AppLog.i(TAG, "Install session committed (sessionId=$sessionId)")
    }

    companion object {
        private const val TAG = "ApkInstaller"
    }
}
