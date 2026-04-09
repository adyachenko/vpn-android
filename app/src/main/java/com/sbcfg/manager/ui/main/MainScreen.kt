package com.sbcfg.manager.ui.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.sbcfg.manager.util.AppLog
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sbcfg.manager.constant.Status
import com.sbcfg.manager.domain.model.ConfigState
import com.sbcfg.manager.vpn.BoxService
import kotlinx.coroutines.launch
import androidx.compose.runtime.livedata.observeAsState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    onRequestVpnPermission: () -> Unit = {},
    onStartVpn: (configJson: String?) -> Unit = {},
    onStopVpn: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Observe VPN status from BoxService
    val vpnStatus by BoxService.status.observeAsState(Status.Stopped)
    LaunchedEffect(vpnStatus) {
        val running = vpnStatus == Status.Started || vpnStatus == Status.Starting
        viewModel.onVpnStatusChanged(running)
    }

    LaunchedEffect(Unit) {
        AppLog.i("MainScreen", "Started collecting side effects")
        viewModel.sideEffect.collect { effect ->
            AppLog.i("MainScreen", "Received side effect: ${effect::class.simpleName}")
            when (effect) {
                is SideEffect.RequestVpnPermission -> {
                    // Pass config to Activity, which will handle permission + start
                    onStartVpn(viewModel.pendingConfigJson)
                }
                is SideEffect.StartVpn -> onStartVpn(viewModel.pendingConfigJson)
                is SideEffect.StopVpn -> onStopVpn()
                is SideEffect.ShowError -> snackbarHostState.showSnackbar(effect.message)
                is SideEffect.ShowSnackbar -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    val tabs = listOf("Домены", "Приложения", "Настройки", "Логи")
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Config Manager") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Status Card
            val configState = state.configState
            if (configState is ConfigState.Loaded) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = configState.serverInfo.serverName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = configState.serverInfo.protocol.uppercase(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = viewModel::onToggleVpn,
                            enabled = !state.isGenerating,
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (state.vpnRunning) {
                                ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            }
                        ) {
                            if (state.isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                when {
                                    state.isGenerating -> "Подключение..."
                                    state.vpnRunning -> "Выключить VPN"
                                    else -> "Включить VPN"
                                }
                            )
                        }
                    }
                }
            }

            // TabRow
            TabRow(
                selectedTabIndex = pagerState.currentPage
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        text = { Text(title) }
                    )
                }
            }

            // Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> DomainsTab()
                    1 -> AppsTab()
                    2 -> SettingsTab()
                    3 -> LogsTab()
                }
            }
        }
    }
}
