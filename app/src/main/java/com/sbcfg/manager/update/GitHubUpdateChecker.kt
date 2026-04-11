package com.sbcfg.manager.update

import android.os.Build
import com.sbcfg.manager.BuildConfig
import com.sbcfg.manager.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubUpdateChecker @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLog.w(TAG, "GitHub API error: ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val release = JSONObject(body)

            if (release.optBoolean("draft", false)) return@withContext null

            val tagName = release.optString("tag_name", "")
            val versionName = tagName.removePrefix("v")

            if (!isNewer(versionName, BuildConfig.VERSION_NAME)) {
                AppLog.i(TAG, "No update: current=${BuildConfig.VERSION_NAME}, latest=$versionName")
                return@withContext null
            }

            val assets = release.optJSONArray("assets") ?: JSONArray()
            val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val apkAsset = findMatchingApk(assets, deviceAbi) ?: run {
                AppLog.w(TAG, "No APK found for ABI $deviceAbi")
                return@withContext null
            }

            UpdateInfo(
                versionName = versionName,
                downloadUrl = apkAsset.optString("browser_download_url"),
                releaseUrl = release.optString("html_url"),
                releaseNotes = release.optString("body").takeIf { it.isNotBlank() },
                fileSize = apkAsset.optLong("size", 0)
            ).also {
                AppLog.i(TAG, "Update available: $versionName (${it.fileSize} bytes)")
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Update check failed", e)
            null
        }
    }

    private fun findMatchingApk(assets: JSONArray, deviceAbi: String): JSONObject? {
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name.endsWith(".apk") && name.contains(deviceAbi)) {
                return asset
            }
        }
        return null
    }

    companion object {
        private const val TAG = "GitHubUpdateChecker"
        private const val OWNER = "adyachenko"
        private const val REPO = "vpn-android"
        private const val RELEASES_URL =
            "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

        fun isNewer(remote: String, current: String): Boolean {
            val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
            for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }
    }
}
