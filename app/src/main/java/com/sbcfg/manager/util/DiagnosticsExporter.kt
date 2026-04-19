package com.sbcfg.manager.util

import android.content.Context
import android.os.Build
import android.os.Process
import com.sbcfg.manager.BuildConfig
import com.sbcfg.manager.vpn.BoxService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects a single plain-text diagnostics bundle that can be shared via
 * Android's share sheet. The user triggers this from the Logs tab so we
 * don't have to ask them to hook up ADB for every issue report.
 *
 * Contents:
 *   - Device / build / Android info
 *   - VPN status + uptime + last probe summary
 *   - Last 20 TX/RX watcher snapshots (calibration data)
 *   - AppLog buffer (last 500 entries)
 *   - logcat for this process (best-effort, Android 7+ restricts to own pid)
 *   - Optional: sing-box config with credentials masked
 *
 * Writes to cacheDir/diagnostics/ so FileProvider's <cache-path> grants
 * read access when we hand the URI to the share intent.
 */
object DiagnosticsExporter {

    private const val TAG = "DiagnosticsExporter"
    private const val DIAG_DIR = "diagnostics"
    private const val LOGCAT_MAX_LINES = 2000

    private val fileTimestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val isoTimestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    suspend fun collect(context: Context, includeConfig: Boolean): File =
        withContext(Dispatchers.IO) {
            val now = Date()
            val dir = File(context.cacheDir, DIAG_DIR).apply { mkdirs() }
            // Clean up old exports so we don't leak cache space over time.
            dir.listFiles()?.sortedByDescending { it.lastModified() }
                ?.drop(5)
                ?.forEach { runCatching { it.delete() } }

            val file = File(dir, "vpn-diag-${fileTimestamp.format(now)}.txt")
            val text = buildReport(now, includeConfig)
            file.writeText(text)
            AppLog.i(TAG, "Diagnostics written: ${file.name} (${file.length()}B, includeConfig=$includeConfig)")
            file
        }

    private fun buildReport(now: Date, includeConfig: Boolean): String = buildString {
        appendLine("=== VPN Diagnostics ===")
        appendLine("Timestamp: ${isoTimestamp.format(now)}")
        appendLine("App: ${BuildConfig.VERSION_NAME} (code=${BuildConfig.VERSION_CODE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine()

        appendLine("=== VPN state ===")
        appendLine("Status: ${BoxService.status.value}")
        val startedAt = BoxService.startedAt
        if (startedAt != null) {
            val uptimeMs = System.currentTimeMillis() - startedAt
            appendLine("Started at: ${isoTimestamp.format(Date(startedAt))}")
            appendLine("Uptime: ${uptimeMs / 1000}s")
        } else {
            appendLine("Started at: —")
        }
        HealthMetrics.probe.value?.let { p ->
            appendLine("Last probe: selector=${p.selectorSelected} hysteria=${p.hysteriaDelayMs}ms naive=${p.naiveDelayMs}ms (at ${isoTimestamp.format(Date(p.timestampMs))})")
        }
        HealthMetrics.snapshot.value?.let { s ->
            appendLine("Last TX/RX: up=${s.uplinkBps}Bps down=${s.downlinkBps}Bps totals=${s.uplinkTotal}/${s.downlinkTotal}B state=${s.stallState} susp=${s.suspiciousCount}")
        }
        appendLine()

        appendLine("=== TX/RX history (last ${HealthMetrics.historySnapshot().size} ticks) ===")
        HealthMetrics.historySnapshot().forEach { s ->
            appendLine(
                "${isoTimestamp.format(Date(s.timestampMs))} " +
                    "up=${s.uplinkBps}Bps down=${s.downlinkBps}Bps " +
                    "totals=${s.uplinkTotal}/${s.downlinkTotal}B " +
                    "state=${s.stallState} susp=${s.suspiciousCount}"
            )
        }
        appendLine()

        appendLine("=== AppLog (last 500 entries) ===")
        appendLine(AppLog.exportSnapshot())
        appendLine()

        appendLine("=== logcat (pid=${Process.myPid()}, last $LOGCAT_MAX_LINES lines) ===")
        appendLine(readOwnLogcat())
        appendLine()

        if (includeConfig) {
            appendLine("=== sing-box config (credentials masked) ===")
            val cfg = BoxService.currentConfigSnapshot()
            if (cfg.isNullOrEmpty()) {
                appendLine("(no config loaded)")
            } else {
                appendLine(CredentialMasker.mask(cfg))
            }
            appendLine()
        }
    }

    private fun readOwnLogcat(): String {
        return try {
            val pid = Process.myPid().toString()
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-t", LOGCAT_MAX_LINES.toString(), "--pid", pid)
            )
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                // Mask creds inline in case libbox logged anything sensitive.
                CredentialMasker.mask(reader.readText())
            }
        } catch (e: Exception) {
            "logcat read failed: ${e.message}"
        }
    }
}
