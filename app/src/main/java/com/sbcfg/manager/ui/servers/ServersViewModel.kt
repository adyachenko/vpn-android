package com.sbcfg.manager.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbcfg.manager.domain.ServerListRepository
import com.sbcfg.manager.domain.ServerSelectionRepository
import com.sbcfg.manager.domain.model.VpnServer
import com.sbcfg.manager.speedtest.ServerPingScheduler
import com.sbcfg.manager.speedtest.ServerSpeedRunner
import com.sbcfg.manager.util.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServersViewModel @Inject constructor(
    private val repository: ServerListRepository,
    private val pingScheduler: ServerPingScheduler,
    private val selectionRepository: ServerSelectionRepository,
    private val speedRunner: ServerSpeedRunner,
) : ViewModel() {

    data class UiState(
        val servers: List<VpnServer> = emptyList(),
        val pings: Map<String, Long?> = emptyMap(),
        val selectedTag: String? = null,
        val speed: ServerSpeedRunner.UiState = ServerSpeedRunner.UiState(),
        val isRefreshing: Boolean = false,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.servers,
                pingScheduler.pings,
                selectionRepository.selectedTag,
                speedRunner.state,
            ) { servers, pings, selected, speed ->
                _uiState.value.copy(
                    servers = servers,
                    pings = pings,
                    selectedTag = selected,
                    speed = speed,
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
        // Подтянуть кэш и обновить, если он устарел / пуст.
        viewModelScope.launch {
            val refreshed = try {
                repository.refreshIfStale()
            } catch (e: Exception) {
                AppLog.w(TAG, "refreshIfStale failed: ${e.message}")
                false
            }
            if (refreshed) {
                // первичный пинг — сразу, не ждём 30 сек цикла
                pingScheduler.pingNow()
            }
        }
    }

    /** Запускается из UI при появлении экрана. */
    fun onScreenAppear() {
        pingScheduler.start()
    }

    /** Останавливается при уходе с экрана, чтобы не жечь батарею. */
    fun onScreenDispose() {
        pingScheduler.stop()
    }

    fun onRunSpeedTest() {
        speedRunner.start()
    }

    fun onCancelSpeedTest() {
        speedRunner.cancel()
    }

    /** Выбрать сервер вручную. tag=null → авто (proxy-auto urltest). */
    fun onSelectServer(tag: String?) {
        viewModelScope.launch {
            try {
                selectionRepository.selectServer(tag)
            } catch (e: Exception) {
                AppLog.w(TAG, "selectServer failed: ${e.message}")
                _uiState.update { it.copy(error = e.message ?: "Не удалось переключить") }
            }
        }
    }

    fun onPullToRefresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            repository.refresh()
                .onSuccess { pingScheduler.pingNow() }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message ?: "Ошибка обновления") }
                }
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private companion object {
        const val TAG = "ServersVM"
    }
}
