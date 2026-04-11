package com.sbcfg.manager.update

import android.content.Context
import com.sbcfg.manager.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    suspend fun download(url: String): File = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "updates")
        updateDir.mkdirs()
        val apkFile = File(updateDir, "update.apk")

        AppLog.i(TAG, "Downloading APK from $url")
        _progress.value = 0f

        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw RuntimeException("Download failed: ${response.code}")
        }

        val body = response.body ?: throw RuntimeException("Empty response body")
        val totalBytes = body.contentLength()

        body.byteStream().use { input ->
            apkFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Long = 0
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesRead += read
                    if (totalBytes > 0) {
                        _progress.value = bytesRead.toFloat() / totalBytes.toFloat()
                    }
                }
            }
        }

        if (!apkFile.exists() || apkFile.length() == 0L) {
            throw RuntimeException("Downloaded APK is empty")
        }

        _progress.value = 1f
        AppLog.i(TAG, "APK downloaded: ${apkFile.length()} bytes")
        apkFile
    }

    companion object {
        private const val TAG = "ApkDownloader"
    }
}
