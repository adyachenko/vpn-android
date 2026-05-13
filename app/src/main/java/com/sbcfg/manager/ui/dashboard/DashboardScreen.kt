package com.sbcfg.manager.ui.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Link
import androidx.compose.runtime.livedata.observeAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sbcfg.manager.constant.Status
import com.sbcfg.manager.domain.model.ConfigState
import com.sbcfg.manager.ui.main.MainViewModel
import com.sbcfg.manager.ui.main.SideEffect
import com.sbcfg.manager.vpn.BoxService
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onOpenSettings: () -> Unit = {},
    onOpenSpeedTest: () -> Unit = {},
    onOpenConnections: () -> Unit = {},
    onOpenServers: () -> Unit = {},
    onStartVpn: (configJson: String?) -> Unit = {},
    onStopVpn: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val vpnStatus by BoxService.status.observeAsState(Status.Stopped)
    LaunchedEffect(vpnStatus) {
        val running = vpnStatus == Status.Started || vpnStatus == Status.Starting
        viewModel.onVpnStatusChanged(running)
    }

    LaunchedEffect(Unit) {
        viewModel.sideEffect.collect { effect ->
            when (effect) {
                is SideEffect.RequestVpnPermission -> onStartVpn(viewModel.pendingConfigJson)
                is SideEffect.StartVpn -> onStartVpn(viewModel.pendingConfigJson)
                is SideEffect.StopVpn -> onStopVpn()
                is SideEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
                is SideEffect.ShowSnackbar -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "SBOXY",
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenServers) {
                        Icon(
                            imageVector = Icons.Filled.Public,
                            contentDescription = "Серверы",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Настройки",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            PowerButton(
                running = state.vpnRunning,
                generating = state.isGenerating || vpnStatus == Status.Starting,
                onClick = { viewModel.onToggleVpn() }
            )

            Spacer(Modifier.height(16.dp))

            if (state.hasMultipleServers) {
                ServerPill(
                    selectedServerName = state.selectedServer?.displayName,
                    selectedCountryCode = state.selectedServer?.countryCode ?: "",
                    onClick = onOpenServers,
                )
                Spacer(Modifier.height(16.dp))
            } else {
                Spacer(Modifier.height(24.dp))
            }

            StatusBlock(
                status = vpnStatus,
                isGenerating = state.isGenerating,
                vpnStartedAt = state.vpnStartedAt
            )

            Spacer(Modifier.height(32.dp))

            val configState = state.configState
            if (configState is ConfigState.Loaded) {
                if (state.hasMultipleServers) {
                    // В multi-server режиме ServerInfoCard.serverName из конфига
                    // указывает на первый outbound (hysteria2-primary) и не
                    // отражает реальный выбор пользователя. Показываем выбранный
                    // сервер (или «Авто» при автовыборе).
                    val selected = state.selectedServer
                    ServerInfoCard(
                        serverName = selected?.displayName?.ifBlank { selected.tag }
                            ?: "Авто (urltest)",
                        protocol = if (selected == null) "выбор sing-box"
                                   else configState.serverInfo.protocol,
                    )
                } else {
                    ServerInfoCard(
                        serverName = configState.serverInfo.serverName,
                        protocol = configState.serverInfo.protocol
                    )
                }
            } else {
                Text(
                    text = "Конфигурация не загружена",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            if (vpnStatus == Status.Started) {
                Spacer(Modifier.height(12.dp))
                TrafficWidget(onClick = onOpenConnections)
            }

            Spacer(Modifier.height(12.dp))
            SpeedWidget(onOpenSpeedTest = onOpenSpeedTest)
        }
    }
}

@Composable
private fun TrafficWidget(onClick: () -> Unit = {}) {
    val data by BoxService.trafficSnapshot.observeAsState()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        val snapshot = data
        if (snapshot != null) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TrafficStat(
                        icon = Icons.Filled.ArrowUpward,
                        label = "Upload",
                        value = formatBytes(snapshot.uploadTotal),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    TrafficStat(
                        icon = Icons.Filled.ArrowDownward,
                        label = "Download",
                        value = formatBytes(snapshot.downloadTotal),
                        color = MaterialTheme.colorScheme.primary
                    )
                    TrafficStat(
                        icon = Icons.Filled.Link,
                        label = "Conns",
                        value = snapshot.activeConnections.toString(),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Loading traffic...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TrafficStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SpeedWidget(onOpenSpeedTest: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSpeedTest),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Speed,
                    contentDescription = "Speed Test",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Speed Test",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Tap to test",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
    if (bytes < 1024 * 1024 * 1024) return "%.1f MB".format(bytes / (1024.0 * 1024))
    return "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

@Composable
private fun PowerButton(
    running: Boolean,
    generating: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val outline = MaterialTheme.colorScheme.outlineVariant

    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    // Auto-focus the power button on Android TV so the user can press the D-pad
    // center immediately to toggle the VPN ("включил и забыл" scenario).
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(260.dp)
    ) {
        if (running) {
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                primary.copy(alpha = pulseAlpha),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
        }
        Box(
            modifier = Modifier
                .size(220.dp)
                .shadow(
                    elevation = if (running || isFocused) 24.dp else 0.dp,
                    shape = CircleShape,
                    ambientColor = primary,
                    spotColor = primary
                )
                .focusRequester(focusRequester)
                .focusable(interactionSource = interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .then(
                    if (isFocused) {
                        Modifier.border(
                            width = 4.dp,
                            color = primary,
                            shape = CircleShape
                        )
                    } else {
                        Modifier
                    }
                )
                .background(
                    brush = if (running) {
                        Brush.linearGradient(
                            colors = listOf(primary, MaterialTheme.colorScheme.primaryContainer)
                        )
                    } else {
                        Brush.linearGradient(
                            colors = listOf(
                                outline.copy(alpha = 0.4f),
                                outline.copy(alpha = 0.2f)
                            )
                        )
                    },
                    shape = CircleShape
                )
                .padding(4.dp)
                .background(surfaceContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .border(
                        width = 1.dp,
                        color = outline.copy(alpha = 0.3f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (generating) {
                    CircularProgressIndicator(
                        color = primary,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(64.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PowerSettingsNew,
                        contentDescription = if (running) "Выключить" else "Включить",
                        tint = if (running) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(96.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(
    status: Status,
    isGenerating: Boolean,
    vpnStartedAt: Long?
) {
    val (label, color) = when {
        isGenerating || status == Status.Starting -> "ПОДКЛЮЧЕНИЕ" to MaterialTheme.colorScheme.tertiary
        status == Status.Started -> "CONNECTED" to MaterialTheme.colorScheme.primary
        status == Status.Stopping -> "ОТКЛЮЧЕНИЕ" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "DISCONNECTED" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Text(
                text = label,
                color = color,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(8.dp))

        if (status == Status.Started && vpnStartedAt != null) {
            UptimeText(startedAt = vpnStartedAt)
        } else {
            Text(
                text = "00:00:00",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 40.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun UptimeText(startedAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = ((now - startedAt) / 1000).coerceAtLeast(0)
    val hours = elapsed / 3600
    val minutes = (elapsed % 3600) / 60
    val seconds = elapsed % 60
    Text(
        text = "%02d:%02d:%02d".format(hours, minutes, seconds),
        fontWeight = FontWeight.ExtraBold,
        fontSize = 40.sp,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun ServerInfoCard(
    serverName: String,
    protocol: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text(
                    text = "СЕРВЕР",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = serverName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = protocol.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ServerPill(
    selectedServerName: String?,
    selectedCountryCode: String,
    onClick: () -> Unit,
) {
    val isAuto = selectedServerName == null
    val label = if (isAuto) "Авто" else selectedServerName
    val flag = if (isAuto) "" else flagFromCountry(selectedCountryCode)
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isAuto) {
                Icon(
                    imageVector = Icons.Filled.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
            } else {
                Text(flag, fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label ?: "Авто",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun flagFromCountry(cc: String): String {
    if (cc.length != 2) return "🏳"
    val up = cc.uppercase()
    val a = Character.toChars(0x1F1E6 + (up[0].code - 'A'.code))
    val b = Character.toChars(0x1F1E6 + (up[1].code - 'A'.code))
    return String(a) + String(b)
}
