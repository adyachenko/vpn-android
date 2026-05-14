package com.sbcfg.manager.vpn

import com.sbcfg.manager.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Passive observer for stuck-active TCP connections through proxy outbounds.
 *
 * Watches the live `/connections` snapshot from Clash API and counts
 * connections that look stuck:
 *   - network == "tcp"
 *   - chain[0] != direct/dns/block (i.e. went through a proxy outbound)
 *   - download == 0 (server never sent a byte back)
 *   - age >= STUCK_AGE_THRESHOLD_MS (long enough to rule out slow handshake)
 *
 * This is the exact pattern visible in the Connections UI as "stale" rows
 * (orange dot, 0B/0B, hanging through a proxy). [ConnectionFailureDetector]
 * only sees connections after they close — apps like Telegram hold dead
 * sockets for 60+ seconds before reaping, so a stuck-but-still-open
 * connection slips past that detector entirely.
 *
 * Strictly observational: never triggers a restart. When the user explicitly
 * overrode the outbound (e.g. forced Naive), restart wouldn't help anyway —
 * same outbound, same sticky destination. The intent of this log is to make
 * the pattern visible in diagnostic dumps so we can spot which outbound /
 * which destinations actually misbehave.
 */
class StuckConnectionMonitor(
    private val clashClient: ClashApiClient,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "StuckConn"
        private const val INITIAL_DELAY_MS = 10_000L
        private const val POLL_INTERVAL_MS = 10_000L
        // Min connection age before we consider it stuck. Below this the
        // socket might be in handshake / slow start — not a problem yet.
        private const val STUCK_AGE_THRESHOLD_MS = 15_000L
        // Min count of stuck connections in one snapshot before we bother
        // logging. 2 matches the screenshot threshold we want to catch
        // (Telegram with 2-of-3 stale conns).
        private const val STUCK_MIN_COUNT = 2
        // Throttle repeated logs when the same pattern persists. A change
        // in the stuck-host set or recovery breaks through this floor.
        private const val LOG_INTERVAL_MS = 60_000L

        // chain[0] values that are NOT proxy outbounds — exclude from
        // counting. Anything else (hysteria2-*, naive-*, custom) counts.
        private val NON_PROXY_CHAINS = setOf(
            "",
            "direct",
            "direct-out",
            "dns-out",
            "block",
            "block-out",
        )
    }

    private var job: Job? = null

    @Volatile
    private var lastLoggedAt: Long = 0L

    @Volatile
    private var lastStuckSignature: String = ""

    fun start() {
        stop()
        job = scope.launch(Dispatchers.IO) {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    AppLog.w(TAG, "poll failed: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        lastLoggedAt = 0L
        lastStuckSignature = ""
    }

    private suspend fun pollOnce() {
        val snapshot = withContext(Dispatchers.IO) { clashClient.fetchConnections() }
        val now = System.currentTimeMillis()

        val stuck = snapshot.connections.filter { it.isStuck(now) }
        val signature = stuck
            .map { "${it.chain}:${it.host}:${it.destinationPort}" }
            .sorted()
            .joinToString(",")

        // Recovery edge: previously stuck, now empty — log once.
        if (lastStuckSignature.isNotEmpty() && stuck.isEmpty()) {
            AppLog.i(TAG, "stuck-watcher: recovered (was: $lastStuckSignature)")
            lastStuckSignature = ""
            lastLoggedAt = 0L
            return
        }

        if (stuck.size < STUCK_MIN_COUNT) {
            // Sub-threshold but non-empty state — still track signature so
            // a transition to/from logged state behaves consistently.
            lastStuckSignature = signature
            return
        }

        val signatureChanged = signature != lastStuckSignature
        val throttled = now - lastLoggedAt < LOG_INTERVAL_MS
        if (!signatureChanged && throttled) {
            lastStuckSignature = signature
            return
        }

        val byChain = stuck.groupBy { it.chain }
        val summary = byChain.entries.joinToString(" | ") { (chain, conns) ->
            val hosts = conns.joinToString(",") { c ->
                val host = c.host.ifEmpty { "?" }
                val age = ageMs(c.start, now) / 1_000L
                "$host:${c.destinationPort}(${age}s)"
            }
            "$chain×${conns.size}: $hosts"
        }
        AppLog.w(
            TAG,
            "stuck-watcher: ${stuck.size} stuck TCP conn (download=0, age>=15s) — $summary",
        )

        lastStuckSignature = signature
        lastLoggedAt = now
    }

    private fun ConnectionInfo.isStuck(now: Long): Boolean {
        if (network != "tcp") return false
        if (chain in NON_PROXY_CHAINS) return false
        if (download != 0L) return false
        val age = ageMs(start, now)
        return age >= STUCK_AGE_THRESHOLD_MS
    }

    private fun ageMs(startIso: String, now: Long): Long {
        if (startIso.isEmpty()) return 0L
        return try {
            now - Instant.parse(startIso).toEpochMilli()
        } catch (_: DateTimeParseException) {
            0L
        }
    }
}
