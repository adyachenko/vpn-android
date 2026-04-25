package com.sbcfg.manager.ui.speedtest

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sbcfg.manager.speedtest.DEFAULT_SERVERS
import com.sbcfg.manager.speedtest.SpeedTestDao
import com.sbcfg.manager.speedtest.SpeedTestEngine
import com.sbcfg.manager.speedtest.SpeedTestResult
import com.sbcfg.manager.util.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class SpeedTestViewModel @Inject constructor(
    private val dao: SpeedTestDao,
    client: OkHttpClient,
) : ViewModel() {

    private val engine = SpeedTestEngine(client)

    private val _recentResults = MutableStateFlow<List<SpeedTestResult>>(emptyList())
    val recentResults: StateFlow<List<SpeedTestResult>> = _recentResults.asStateFlow()

    private val _currentPhase = MutableStateFlow("idle")
    val currentPhase: StateFlow<String> = _currentPhase.asStateFlow()

    private val _currentSpeed = MutableStateFlow(0.0)
    val currentSpeed: StateFlow<Double> = _currentSpeed.asStateFlow()

    private val _lastResult = MutableStateFlow<SpeedTestResult?>(null)
    val lastResult: StateFlow<SpeedTestResult?> = _lastResult.asStateFlow()

    private val _selectedServerIndex = MutableStateFlow(0)
    val selectedServerIndex: StateFlow<Int> = _selectedServerIndex.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    init {
        loadRecent()
    }

    private fun loadRecent() {
        viewModelScope.launch {
            _recentResults.value = dao.getRecent(20)
        }
    }

    fun selectServer(index: Int) {
        _selectedServerIndex.value = index
    }

    fun runTest() {
        if (_isRunning.value) return
        val server = DEFAULT_SERVERS[_selectedServerIndex.value]

        viewModelScope.launch {
            _isRunning.value = true
            _currentSpeed.value = 0.0
            AppLog.i("SpeedTestScreen", "Starting test on ${server.name}")

            // Ping
            _currentPhase.value = "ping"
            val pingMs = engine.measurePing(server.pingUrl)
            AppLog.i("SpeedTestScreen", "Ping: $pingMs ms")

            // Download
            _currentPhase.value = "download"
            val downloadMbps = engine.measureDownload(
                url = server.downloadUrl,
                onProgress = { mbps -> _currentSpeed.value = mbps },
            )
            _currentSpeed.value = downloadMbps

            // Upload
            val uploadUrl = server.uploadUrl
            val uploadMbps: Double
            if (uploadUrl != null) {
                _currentPhase.value = "upload"
                _currentSpeed.value = 0.0
                uploadMbps = engine.measureUpload(
                    url = uploadUrl,
                    method = server.uploadMethod,
                    onProgress = { mbps -> _currentSpeed.value = mbps },
                )
            } else {
                uploadMbps = -1.0
            }

            // Save result
            val result = SpeedTestResult(
                serverName = server.name,
                pingMs = pingMs,
                downloadMbps = downloadMbps,
                uploadMbps = uploadMbps,
            )
            dao.insert(result)
            _lastResult.value = result
            _currentPhase.value = "done"
            _isRunning.value = false
            _currentSpeed.value = 0.0
            loadRecent()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

fun formatSpeed(mbps: Double): String =
    if (mbps < 0) "—" else "%.1f".format(mbps)

fun formatPing(ms: Double): String =
    if (ms < 0) "—" else ms.toLong().toString()

fun formatTimeAgo(timestamp: Long): String {
    val diffMs = System.currentTimeMillis() - timestamp
    val seconds = diffMs / 1_000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hour${if (hours == 1L) "" else "s"} ago"
        days == 1L -> "yesterday"
        else -> "$days days ago"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(
    onBack: () -> Unit,
    viewModel: SpeedTestViewModel = hiltViewModel(),
) {
    val phase by viewModel.currentPhase.collectAsStateWithLifecycle()
    val currentSpeed by viewModel.currentSpeed.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()
    val recentResults by viewModel.recentResults.collectAsStateWithLifecycle()
    val selectedServerIndex by viewModel.selectedServerIndex.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speed Test") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
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
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Server selector
            ServerSelector(
                selectedIndex = selectedServerIndex,
                onSelect = viewModel::selectServer,
                enabled = !isRunning,
            )

            // 2. Gauge area
            GaugeArea(phase = phase, currentSpeed = currentSpeed)

            // 3. Start button
            Button(
                onClick = viewModel::runTest,
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = if (isRunning) "Testing..." else "Start Test",
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            // 4. Last result card
            if (lastResult != null) {
                LastResultCard(result = lastResult!!)
            }

            // 5. History
            if (recentResults.isNotEmpty()) {
                Text(
                    text = "History",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HistoryList(results = recentResults)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-composables
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSelector(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = DEFAULT_SERVERS[selectedIndex]

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = "${selected.name} · ${selected.location}",
            onValueChange = {},
            readOnly = true,
            label = { Text("Server") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
        ) {
            DEFAULT_SERVERS.forEachIndexed { index, server ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = server.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = server.location,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun GaugeArea(phase: String, currentSpeed: Double) {
    val phaseLabel = when (phase) {
        "ping" -> "Measuring ping..."
        "download" -> "Download test..."
        "upload" -> "Upload test..."
        "done" -> "Done"
        else -> ""
    }

    val speedText = when {
        phase == "idle" || phase == "done" -> "—"
        phase == "ping" -> "—"
        currentSpeed < 0 -> "—"
        else -> "%.1f".format(currentSpeed)
    }

    val unitText = when (phase) {
        "download", "upload" -> "Mbps"
        else -> ""
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = speedText,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                if (unitText.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = unitText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }
            if (phaseLabel.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = phaseLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LastResultCard(result: SpeedTestResult) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Last Result",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ResultMetric(label = "Ping", value = "${formatPing(result.pingMs)} ms")
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ArrowDownward,
                    contentDescription = "Download",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Download: ${formatSpeed(result.downloadMbps)} Mbps",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = "Upload",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Upload: ${formatSpeed(result.uploadMbps)} Mbps",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Server: ${result.serverName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResultMetric(label: String, value: String) {
    Row {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun HistoryList(results: List<SpeedTestResult>) {
    // Use LazyColumn inside a scrollable Column: give it a fixed height via
    // heightIn so it doesn't conflict with the outer scroll.
    // We render items directly to avoid nested scroll conflicts.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        results.forEach { result ->
            HistoryItem(result = result)
        }
    }
}

@Composable
private fun HistoryItem(result: SpeedTestResult) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = result.serverName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = formatTimeAgo(result.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = "Download",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = formatSpeed(result.downloadMbps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = "Upload",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = formatSpeed(result.uploadMbps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    text = "Ping ${formatPing(result.pingMs)} ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
