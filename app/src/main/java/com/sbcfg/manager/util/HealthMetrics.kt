package com.sbcfg.manager.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped state exposed by VpnHealthCheck to the UI (Logs tab) and to
 * DiagnosticsExporter. Kept outside BoxService so UI doesn't have to know
 * about the VPN service plumbing.
 */
object HealthMetrics {

    enum class StallState { HEALTHY, SUSPICIOUS, DEAD }

    data class RxSnapshot(
        /** Bytes/sec uplink, from the latest StatusMessage delta. */
        val uplinkBps: Long,
        /** Bytes/sec downlink, from the latest StatusMessage delta. */
        val downlinkBps: Long,
        /** Cumulative uplink bytes since engine start. */
        val uplinkTotal: Long,
        /** Cumulative downlink bytes since engine start. */
        val downlinkTotal: Long,
        val stallState: StallState,
        /** Count of consecutive suspicious ticks (tx>0, rx<threshold). */
        val suspiciousCount: Int,
        val timestampMs: Long,
    )

    /**
     * Single result of a probe() run — last known outbound tags and delays.
     * Populated by VpnHealthCheck.probe() so DiagnosticsExporter can dump
     * current routing state without re-running a probe.
     */
    data class ProbeSummary(
        val selectorSelected: String?,
        val hysteriaDelayMs: Int,
        val naiveDelayMs: Int,
        val timestampMs: Long,
    )

    private const val HISTORY_SIZE = 20

    private val _snapshot = MutableStateFlow<RxSnapshot?>(null)
    val snapshot: StateFlow<RxSnapshot?> = _snapshot.asStateFlow()

    private val _probe = MutableStateFlow<ProbeSummary?>(null)
    val probe: StateFlow<ProbeSummary?> = _probe.asStateFlow()

    private val history = ArrayDeque<RxSnapshot>(HISTORY_SIZE)

    fun update(snapshot: RxSnapshot) {
        _snapshot.value = snapshot
        synchronized(history) {
            history.addLast(snapshot)
            while (history.size > HISTORY_SIZE) history.removeFirst()
        }
    }

    fun updateProbe(summary: ProbeSummary) {
        _probe.value = summary
    }

    /** Snapshot history copy for diagnostics export. */
    fun historySnapshot(): List<RxSnapshot> = synchronized(history) { history.toList() }

    fun clear() {
        _snapshot.value = null
        _probe.value = null
        synchronized(history) { history.clear() }
    }
}
