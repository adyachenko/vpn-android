package com.sbcfg.manager.ui.servers

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sbcfg.manager.domain.model.VpnServer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    onBack: () -> Unit,
    viewModel: ServersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Запускаем периодический пинг при появлении экрана, останавливаем при уходе.
    DisposableEffect(Unit) {
        viewModel.onScreenAppear()
        onDispose { viewModel.onScreenDispose() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Серверы") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (state.speed.isRunning) {
                        IconButton(onClick = viewModel::onCancelSpeedTest) {
                            Icon(Icons.Filled.Close, contentDescription = "Отменить тест")
                        }
                    } else {
                        IconButton(
                            onClick = viewModel::onRunSpeedTest,
                            enabled = state.servers.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.Speed, contentDescription = "Speedtest")
                        }
                    }
                    IconButton(
                        onClick = viewModel::onPullToRefresh,
                        enabled = !state.isRefreshing,
                    ) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(4.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.error != null && state.servers.isEmpty() -> {
                    ErrorPlaceholder(message = state.error!!)
                }
                state.servers.isEmpty() && state.isRefreshing -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.servers.isEmpty() -> {
                    EmptyPlaceholder()
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "__auto__") {
                            AutoRow(
                                isSelected = state.selectedTag == null,
                                onClick = { viewModel.onSelectServer(null) },
                            )
                        }
                        items(
                            items = state.servers,
                            key = { it.tag },
                        ) { server ->
                            val result = state.speed.results[server.tag]
                            val isTesting = state.speed.isRunning &&
                                state.speed.currentTag == server.tag
                            ServerRow(
                                server = server,
                                pingMs = state.pings[server.tag],
                                downloadMbps = result?.downloadMbps,
                                isSelected = state.selectedTag == server.tag,
                                isTesting = isTesting,
                                testingPhase = if (isTesting) state.speed.currentPhase
                                               else null,
                                liveMbps = if (isTesting && state.speed.currentPhase ==
                                        com.sbcfg.manager.speedtest.ServerSpeedRunner
                                            .Phase.DOWNLOAD)
                                    state.speed.currentMbps else 0.0,
                                onClick = { viewModel.onSelectServer(server.tag) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoRow(isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.AutoMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column {
                    Text(
                        text = "Авто",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = "sing-box urltest — лучший по пингу",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Выбрано",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: VpnServer,
    pingMs: Long?,
    downloadMbps: Double?,
    isSelected: Boolean,
    isTesting: Boolean,
    testingPhase: com.sbcfg.manager.speedtest.ServerSpeedRunner.Phase?,
    liveMbps: Double,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = flagEmoji(server.countryCode),
                        fontSize = 28.sp,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Column {
                        Text(
                            text = server.displayName.ifBlank { server.tag },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                        if (server.countryCode.isNotBlank()) {
                            Text(
                                text = server.countryCode,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        PingBadge(pingMs)
                        SpeedBadge(downloadMbps = downloadMbps, liveMbps = liveMbps, isTesting = isTesting)
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Выбрано",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            if (isTesting) {
                Spacer(Modifier.height(8.dp))
                val label = when (testingPhase) {
                    com.sbcfg.manager.speedtest.ServerSpeedRunner.Phase.PING -> "Тест пинга..."
                    com.sbcfg.manager.speedtest.ServerSpeedRunner.Phase.DOWNLOAD -> "Тест скорости..."
                    else -> "Тестирование..."
                }
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SpeedBadge(downloadMbps: Double?, liveMbps: Double, isTesting: Boolean) {
    val text = when {
        isTesting && liveMbps > 0 -> "%.1f Mbps".format(liveMbps)
        downloadMbps != null -> "%.1f Mbps".format(downloadMbps)
        else -> return  // ничего не показываем
    }
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PingBadge(pingMs: Long?) {
    val text: String
    val color: androidx.compose.ui.graphics.Color
    when {
        pingMs == null -> {
            text = "—"
            color = MaterialTheme.colorScheme.onSurfaceVariant
        }
        pingMs < 80 -> {
            text = "$pingMs ms"
            color = androidx.compose.ui.graphics.Color(0xFF2E7D32) // green
        }
        pingMs < 200 -> {
            text = "$pingMs ms"
            color = androidx.compose.ui.graphics.Color(0xFFEF6C00) // orange
        }
        else -> {
            text = "$pingMs ms"
            color = androidx.compose.ui.graphics.Color(0xFFC62828) // red
        }
    }
    Text(text = text, fontWeight = FontWeight.Medium, color = color)
}

@Composable
private fun EmptyPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Список серверов пуст", fontWeight = FontWeight.Medium)
            Text(
                "Потяните для обновления",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorPlaceholder(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp),
        ) {
            Text("Не удалось загрузить", fontWeight = FontWeight.Medium)
            Text(
                text = message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * ISO-3166 alpha-2 → emoji флаг. RU → "RU" → 🇷🇺.
 * Каждая буква мапится в Regional Indicator Symbol (U+1F1E6 + offset),
 * пара таких символов рендерится как один флаг.
 */
private fun flagEmoji(countryCode: String): String {
    if (countryCode.length != 2) return "🏳"
    val cc = countryCode.uppercase()
    val first = Character.toChars(0x1F1E6 + (cc[0].code - 'A'.code))
    val second = Character.toChars(0x1F1E6 + (cc[1].code - 'A'.code))
    return String(first) + String(second)
}
