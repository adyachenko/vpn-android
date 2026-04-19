package com.sbcfg.manager.ui.main

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sbcfg.manager.util.AppLog
import com.sbcfg.manager.util.DiagnosticsExporter
import com.sbcfg.manager.util.HealthMetrics
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun LogsTab() {
    val entries by AppLog.entries.collectAsStateWithLifecycle()
    val rxSnapshot by HealthMetrics.snapshot.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showExportDialog by remember { mutableStateOf(false) }

    // Auto-scroll to bottom when new entries arrive
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    if (showExportDialog) {
        ExportDialog(
            onDismiss = { showExportDialog = false },
            onConfirm = { includeConfig ->
                showExportDialog = false
                scope.launch {
                    try {
                        val file = DiagnosticsExporter.collect(context, includeConfig)
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        )
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "VPN diagnostics — ${file.name}")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(share, "Отправить диагностику").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    } catch (e: Exception) {
                        AppLog.e("LogsTab", "Export failed", e)
                    }
                }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        RxStatusBanner(rxSnapshot)
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${entries.size} записей",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { showExportDialog = true }) {
                Text("Экспорт")
            }
            TextButton(onClick = { AppLog.clear() }) {
                Text("Очистить")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            if (entries.isEmpty()) {
                Text(
                    text = "Нет записей. Нажмите «Включить VPN» чтобы увидеть логи.",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(entries.size) { index ->
                        val entry = entries[index]
                        LogEntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun RxStatusBanner(snapshot: HealthMetrics.RxSnapshot?) {
    val stateLabel = when (snapshot?.stallState) {
        HealthMetrics.StallState.HEALTHY -> "healthy"
        HealthMetrics.StallState.SUSPICIOUS -> "suspicious (${snapshot.suspiciousCount}/3)"
        HealthMetrics.StallState.DEAD -> "DEAD"
        null -> "—"
    }
    val stateColor = when (snapshot?.stallState) {
        HealthMetrics.StallState.SUSPICIOUS -> Color(0xFFFF9800)
        HealthMetrics.StallState.DEAD -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "TX ${formatBps(snapshot?.uplinkBps)} ↑",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "RX ${formatBps(snapshot?.downlinkBps)} ↓",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stateLabel,
            style = MaterialTheme.typography.bodySmall,
            color = stateColor,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun formatBps(value: Long?): String {
    if (value == null) return "—"
    return when {
        value >= 1_000_000 -> String.format(Locale.US, "%.1f MB/s", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1f KB/s", value / 1_000.0)
        else -> "${value} B/s"
    }
}

@Composable
private fun ExportDialog(onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var includeConfig by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Экспорт диагностики") },
        text = {
            Column {
                Text(
                    text = "Будет собран файл с логами, метриками TX/RX и информацией об устройстве.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeConfig, onCheckedChange = { includeConfig = it })
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Включить конфиг (пароли маскируются)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(includeConfig) }) {
                Text("Отправить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}

@Composable
private fun LogEntryRow(entry: AppLog.Entry) {
    val color = when (entry.level) {
        "E" -> MaterialTheme.colorScheme.error
        "W" -> Color(0xFFFF9800)
        "I" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = entry.timestamp,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = entry.tag,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = entry.message,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}
