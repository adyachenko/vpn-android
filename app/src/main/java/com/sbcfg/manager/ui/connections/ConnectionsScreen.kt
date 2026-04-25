package com.sbcfg.manager.ui.connections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sbcfg.manager.util.AppLog
import com.sbcfg.manager.vpn.BoxService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// Domain models
// ─────────────────────────────────────────────────────────────────────────────

data class ConnectionUiModel(
    val key: String,           // "$host:$port:$chain"
    val host: String,
    val port: Int,
    val network: String,
    val chain: String,
    val rule: String,
    val process: String,
    val upload: Long,
    val download: Long,
    val duration: String,      // "2m 30s", "1h 5m"
    val isStale: Boolean,      // no traffic delta for 3+ polls
    val downloadRate: Long,    // bytes/sec
    val uploadRate: Long,      // bytes/sec
)

data class Summary(
    val totalUp: Long,
    val totalDown: Long,
    val activeCount: Int,
    val staleCount: Int,
)

enum class GroupMode { ByApp, ByOutbound, ByRule }

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "ConnectionsVM"
private const val POLL_INTERVAL_MS = 2_000L
private const val STALE_POLL_THRESHOLD = 3

@HiltViewModel
class ConnectionsViewModel @Inject constructor() : ViewModel() {

    private data class TrafficEntry(
        val upload: Long,
        val download: Long,
        val zeroCount: Int,   // consecutive zero-delta polls
    )

    private val _connections = MutableStateFlow<List<ConnectionUiModel>>(emptyList())
    val connections: StateFlow<List<ConnectionUiModel>> = _connections.asStateFlow()

    private val _summary = MutableStateFlow(Summary(0L, 0L, 0, 0))
    val summary: StateFlow<Summary> = _summary.asStateFlow()

    private val _groupMode = MutableStateFlow(GroupMode.ByOutbound)
    val groupMode: StateFlow<GroupMode> = _groupMode.asStateFlow()

    private val trafficHistory = mutableMapOf<String, TrafficEntry>()
    private var lastPollTime: Long = System.currentTimeMillis()
    private var pollJob: Job? = null

    init {
        startPolling()
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                poll()
                delay(POLL_INTERVAL_MS)
            }
        }
        AppLog.i(TAG, "Polling started")
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        AppLog.i(TAG, "Polling stopped")
    }

    fun setGroupMode(mode: GroupMode) {
        _groupMode.update { mode }
    }

    fun closeConnection(id: String) {
        viewModelScope.launch {
            try {
                val client = BoxService.clashClient
                if (client == null) {
                    AppLog.w(TAG, "closeConnection: clashClient is null")
                    return@launch
                }
                // Clash API: DELETE /connections/{id}
                // ClashApiClient doesn't expose this yet; log intent
                AppLog.i(TAG, "closeConnection requested for id=$id (not yet implemented in ClashApiClient)")
            } catch (e: Exception) {
                AppLog.w(TAG, "closeConnection failed: ${e.message}")
            }
        }
    }

    private suspend fun poll() {
        val client = BoxService.clashClient
        if (client == null) {
            AppLog.w(TAG, "poll: clashClient is null, skipping")
            return
        }

        val snapshot = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.fetchConnections()
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "poll: fetchConnections failed: ${e.message}")
            return
        }

        val now = System.currentTimeMillis()
        val deltaSeconds = ((now - lastPollTime) / 1_000L).coerceAtLeast(1L)
        lastPollTime = now

        val enriched = snapshot.connections.mapIndexed { idx, conn ->
            val key = "${conn.host}:${conn.destinationPort}:${conn.chain}:$idx"
            val prev = trafficHistory[key]

            val dlRate: Long
            val ulRate: Long
            val zeroCount: Int

            if (prev != null) {
                val dlDelta = (conn.download - prev.download).coerceAtLeast(0L)
                val ulDelta = (conn.upload - prev.upload).coerceAtLeast(0L)
                dlRate = dlDelta / deltaSeconds
                ulRate = ulDelta / deltaSeconds
                zeroCount = if (dlDelta == 0L && ulDelta == 0L) prev.zeroCount + 1 else 0
            } else {
                dlRate = 0L
                ulRate = 0L
                zeroCount = 0
            }

            trafficHistory[key] = TrafficEntry(conn.upload, conn.download, zeroCount)

            ConnectionUiModel(
                key = key,
                host = conn.host,
                port = conn.destinationPort,
                network = conn.network,
                chain = conn.chain,
                rule = conn.rule,
                process = conn.process,
                upload = conn.upload,
                download = conn.download,
                duration = formatDuration(conn.start),
                isStale = zeroCount >= STALE_POLL_THRESHOLD,
                downloadRate = dlRate,
                uploadRate = ulRate,
            )
        }

        // Purge history for connections that disappeared
        val activeKeys = enriched.map { it.key }.toSet()
        trafficHistory.keys.retainAll(activeKeys)

        // Sort: stale first, then by download desc
        val sorted = enriched.sortedWith(
            compareByDescending<ConnectionUiModel> { it.isStale }
                .thenByDescending { it.download },
        )

        val staleCount = sorted.count { it.isStale }
        _connections.update { sorted }
        _summary.update {
            Summary(
                totalUp = snapshot.uploadTotal,
                totalDown = snapshot.downloadTotal,
                activeCount = sorted.size,
                staleCount = staleCount,
            )
        }

        AppLog.i(TAG, "poll: ${sorted.size} connections, $staleCount stale")
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatDuration(isoStart: String): String {
    if (isoStart.isBlank()) return "?"
    return try {
        val startInstant = Instant.parse(isoStart)
        val nowInstant = Instant.now()
        val totalSeconds = (nowInstant.epochSecond - startInstant.epochSecond).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    } catch (_: DateTimeParseException) {
        "?"
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun formatRate(bytesPerSec: Long): String {
    return when {
        bytesPerSec < 1024L -> "$bytesPerSec B/s"
        bytesPerSec < 1024L * 1024L -> "%.1f KB/s".format(bytesPerSec / 1024.0)
        bytesPerSec < 1024L * 1024L * 1024L -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024.0))
        else -> "%.2f GB/s".format(bytesPerSec / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun groupKey(conn: ConnectionUiModel, mode: GroupMode): String = when (mode) {
    GroupMode.ByApp -> conn.process.ifBlank { "(unknown app)" }
    GroupMode.ByOutbound -> conn.chain.ifBlank { "(unknown outbound)" }
    GroupMode.ByRule -> conn.rule.ifBlank { "(unknown rule)" }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

private val ColorActive = Color(0xFF4CAF50)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConnectionsViewModel = hiltViewModel(),
) {
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val groupMode by viewModel.groupMode.collectAsStateWithLifecycle()

    // Pause polling when screen is not visible
    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connections") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Group mode filter chips
            GroupModeChipRow(
                current = groupMode,
                onSelect = viewModel::setGroupMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // Summary card
            SummaryCard(
                summary = summary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (connections.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No active connections",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val grouped = connections.groupBy { groupKey(it, groupMode) }
                    .entries
                    .sortedByDescending { entry ->
                        entry.value.sumOf { it.download + it.upload }
                    }
                val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    grouped.forEach { (groupName, groupConns) ->
                        val isExpanded = expandedGroups[groupName] ?: false
                        val groupUp = groupConns.sumOf { it.upload }
                        val groupDown = groupConns.sumOf { it.download }
                        val staleInGroup = groupConns.count { it.isStale }

                        item(key = "group_$groupName") {
                            GroupHeader(
                                name = groupName,
                                connCount = groupConns.size,
                                staleCount = staleInGroup,
                                totalUp = groupUp,
                                totalDown = groupDown,
                                isExpanded = isExpanded,
                                onClick = { expandedGroups[groupName] = !isExpanded },
                            )
                        }
                        if (isExpanded) {
                            items(items = groupConns, key = { it.key }) { conn ->
                                ConnectionItem(conn = conn)
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Group mode chips
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GroupModeChipRow(
    current: GroupMode,
    onSelect: (GroupMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GroupMode.entries.forEach { mode ->
            val label = when (mode) {
                GroupMode.ByApp -> "By App"
                GroupMode.ByOutbound -> "By Outbound"
                GroupMode.ByRule -> "By Rule"
            }
            FilterChip(
                selected = current == mode,
                onClick = { onSelect(mode) },
                label = { Text(label, fontSize = 12.sp) },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Summary card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SummaryCard(
    summary: Summary,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Upload / Download totals
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "↑ ${formatBytes(summary.totalUp)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "↓ ${formatBytes(summary.totalDown)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Active / Stale counts
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Active: ${summary.activeCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (summary.staleCount > 0) {
                    Text(
                        text = "Stale: ${summary.staleCount}",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = "Stale: 0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Collapsible group header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GroupHeader(
    name: String,
    connCount: Int,
    staleCount: Int,
    totalUp: Long,
    totalDown: Long,
    isExpanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (staleCount > 0) {
                Box(
                    modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name.ifEmpty { "unknown" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "↓ ${formatBytes(totalDown)}  ↑ ${formatBytes(totalUp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "$connCount conn" + if (staleCount > 0) " · $staleCount stale" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (staleCount > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Connection item card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConnectionItem(
    conn: ConnectionUiModel,
    modifier: Modifier = Modifier,
) {
    val containerAlpha = if (conn.isStale) 0.7f else 1.0f
    val statusDotColor = if (conn.isStale) MaterialTheme.colorScheme.error else ColorActive

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(containerAlpha),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // Row 1: status dot + host:port + network badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusDotColor),
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Host:port
                Text(
                    text = "${conn.host}:${conn.port}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Network badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = conn.network.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Row 2: traffic totals + duration
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "↓ ${formatBytes(conn.download)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "↑ ${formatBytes(conn.upload)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = conn.duration,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Row 3: chain · rule
            Text(
                text = "${conn.chain} · ${conn.rule}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Row 4: rates — shown if non-zero OR stale (to emphasize 0 B/s)
            val showRates = conn.isStale || conn.downloadRate > 0L || conn.uploadRate > 0L
            if (showRates) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "↓ ${formatRate(conn.downloadRate)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (conn.isStale)
                            MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        else
                            MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "↑ ${formatRate(conn.uploadRate)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (conn.isStale)
                            MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        else
                            MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
