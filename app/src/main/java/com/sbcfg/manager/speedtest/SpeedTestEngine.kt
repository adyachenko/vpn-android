package com.sbcfg.manager.speedtest

import com.sbcfg.manager.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class SpeedTestEngine(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "SpeedTest"
        private const val PING_COUNT = 5
        private const val DEFAULT_STREAMS = 4
        private const val DOWNLOAD_TIMEOUT_MS = 15_000L
        private const val UPLOAD_SIZE_BYTES = 10_000_000
        private const val BUFFER_SIZE = 8 * 1024 // 8 KB
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val PING_TIMEOUT_SEC = 5L
    }

    /**
     * Measures ping using HTTP HEAD requests (works for all servers including Hetzner/OVH that
     * serve large files — HEAD avoids downloading them while still measuring connect + TTFB).
     * Performs PING_COUNT sequential requests, then trims min/max if >= 3 results and returns
     * the mean of the remainder. Returns -1.0 on complete failure.
     */
    suspend fun measurePing(url: String): Double = withContext(Dispatchers.IO) {
        // Derive a short-timeout client from the provided one to preserve interceptors/dispatcher
        val pingClient = client.newBuilder()
            .connectTimeout(PING_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(PING_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(PING_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .head()
            .build()

        val timings = mutableListOf<Double>()

        repeat(PING_COUNT) { attempt ->
            try {
                val start = System.nanoTime()
                pingClient.newCall(request).execute().use { response ->
                    val elapsed = (System.nanoTime() - start) / 1_000_000.0
                    if (response.isSuccessful || response.code in 200..399) {
                        timings.add(elapsed)
                        AppLog.i(TAG, "Ping attempt ${attempt + 1}: ${elapsed.toInt()}ms")
                    } else {
                        AppLog.w(TAG, "Ping attempt ${attempt + 1}: HTTP ${response.code}")
                    }
                }
            } catch (e: IOException) {
                AppLog.w(TAG, "Ping attempt ${attempt + 1} failed: ${e.message}")
            }
        }

        if (timings.isEmpty()) {
            AppLog.w(TAG, "All ping attempts failed for $url")
            return@withContext -1.0
        }

        val sorted = timings.sorted()
        val trimmed = if (sorted.size >= 3) {
            sorted.subList(1, sorted.size - 1)
        } else {
            sorted
        }

        val avg = trimmed.average()
        AppLog.i(TAG, "Ping result: ${avg.toInt()}ms (${timings.size} samples, trimmed mean)")
        avg
    }

    /**
     * Measures download speed using [streams] parallel GET streams.
     * Reads response bodies in 8KB chunks and accumulates bytes via AtomicLong.
     * Reports progress every 500ms via [onProgress]. Times out after DOWNLOAD_TIMEOUT_MS.
     * Returns speed in Mbps, or -1.0 on failure.
     */
    suspend fun measureDownload(
        url: String,
        streams: Int = DEFAULT_STREAMS,
        onProgress: (currentMbps: Double) -> Unit = {},
    ): Double = withContext(Dispatchers.IO) {
        val totalBytes = AtomicLong(0L)
        val startTime = System.nanoTime()

        AppLog.i(TAG, "Starting download: $streams streams from $url")

        val result = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
            supervisorScope {
                // Progress reporter coroutine
                val progressJob = launch {
                    while (isActive) {
                        delay(PROGRESS_INTERVAL_MS)
                        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
                        if (elapsed > 0) {
                            val mbps = totalBytes.get() * 8.0 / 1_000_000.0 / elapsed
                            onProgress(mbps)
                        }
                    }
                }

                try {
                    val deferred = (1..streams).map {
                        async {
                            val request = Request.Builder().url(url).get().build()
                            try {
                                client.newCall(request).execute().use { response ->
                                    response.body?.byteStream()?.use { stream ->
                                        val buffer = ByteArray(BUFFER_SIZE)
                                        var read: Int
                                        while (stream.read(buffer).also { read = it } != -1) {
                                            totalBytes.addAndGet(read.toLong())
                                        }
                                    }
                                }
                            } catch (e: IOException) {
                                AppLog.w(TAG, "Stream error: ${e.message}")
                            }
                        }
                    }
                    deferred.forEach { it.await() }
                } finally {
                    progressJob.cancel()
                }
            }
        }

        val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0

        if (result == null) {
            AppLog.i(TAG, "Download timed out after ${DOWNLOAD_TIMEOUT_MS}ms — using partial result")
        }

        if (elapsed <= 0 || totalBytes.get() == 0L) {
            AppLog.w(TAG, "Download failed: no bytes received")
            return@withContext -1.0
        }

        val mbps = totalBytes.get() * 8.0 / 1_000_000.0 / elapsed
        AppLog.i(TAG, "Download: ${"%.2f".format(mbps)} Mbps (${totalBytes.get()} bytes in ${"%.2f".format(elapsed)}s)")
        mbps
    }

    /**
     * Measures upload speed by POSTing (or PUTting) [sizeBytes] random bytes to [url].
     * Measures elapsed time from request start to response completion.
     * Returns speed in Mbps, or -1.0 on failure.
     */
    suspend fun measureUpload(
        url: String,
        sizeBytes: Int = UPLOAD_SIZE_BYTES,
        method: String = "POST",
        onProgress: (currentMbps: Double) -> Unit = {},
    ): Double = withContext(Dispatchers.IO) {
        AppLog.i(TAG, "Starting upload: ${sizeBytes / 1_000_000}MB via $method to $url")

        val data = ByteArray(sizeBytes).also { Random.nextBytes(it) }
        val body = data.toRequestBody("application/octet-stream".toMediaType())

        val request = Request.Builder()
            .url(url)
            .method(method, body)
            .build()

        val startTime = System.nanoTime()

        return@withContext try {
            client.newCall(request).execute().use { response ->
                val elapsed = (System.nanoTime() - startTime) / 1_000_000_000.0
                if (elapsed <= 0) {
                    AppLog.w(TAG, "Upload elapsed time is zero")
                    return@withContext -1.0
                }
                val mbps = sizeBytes * 8.0 / 1_000_000.0 / elapsed
                AppLog.i(TAG, "Upload: ${"%.2f".format(mbps)} Mbps (HTTP ${response.code}, ${"%.2f".format(elapsed)}s)")
                onProgress(mbps)
                mbps
            }
        } catch (e: IOException) {
            AppLog.e(TAG, "Upload failed: ${e.message}", e)
            -1.0
        }
    }
}
