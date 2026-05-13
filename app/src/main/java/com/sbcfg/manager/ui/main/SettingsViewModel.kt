package com.sbcfg.manager.ui.main

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbcfg.manager.BuildConfig
import com.sbcfg.manager.data.preferences.AppPreferences
import com.sbcfg.manager.domain.ConfigManager
import com.sbcfg.manager.domain.ProtocolSelectionRepository
import com.sbcfg.manager.update.UpdateInfo
import com.sbcfg.manager.update.UpdateManager
import com.sbcfg.manager.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configManager: ConfigManager,
    private val appPreferences: AppPreferences,
    private val protocolSelection: ProtocolSelectionRepository,
    private val updateManager: UpdateManager,
    private val app: Application
) : ViewModel() {

    data class UiState(
        val configUrl: String? = null,
        val isRefreshing: Boolean = false,
        val autoStart: Boolean = false,
        val selectedProtocol: String? = null,
        val message: String? = null,
        val currentVersion: String = BuildConfig.VERSION_NAME,
        val updateState: UpdateState = UpdateState.Idle
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _sideEffect = Channel<SideEffect>(Channel.BUFFERED)
    val sideEffect = _sideEffect.receiveAsFlow()

    init {
        viewModelScope.launch {
            appPreferences.configUrl.collect { url ->
                _uiState.update { it.copy(configUrl = url) }
            }
        }
        viewModelScope.launch {
            appPreferences.autoStart.collect { enabled ->
                _uiState.update { it.copy(autoStart = enabled) }
            }
        }
        viewModelScope.launch {
            protocolSelection.selectedProtocol.collect { protocol ->
                _uiState.update { it.copy(selectedProtocol = protocol) }
            }
        }
        viewModelScope.launch {
            updateManager.state.collect { updateState ->
                _uiState.update { it.copy(updateState = updateState) }
            }
        }
    }

    fun onProtocolChanged(protocol: String?) {
        viewModelScope.launch {
            protocolSelection.selectProtocol(protocol)
        }
    }

    fun onRefreshConfig() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            configManager.refreshConfig()
                .onSuccess {
                    _uiState.update { it.copy(isRefreshing = false) }
                    _sideEffect.send(SideEffect.ShowSnackbar("Конфиг обновлён"))
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isRefreshing = false) }
                    _sideEffect.send(SideEffect.ShowError(e.message ?: "Ошибка обновления"))
                }
        }
    }

    fun onAutoStartChanged(enabled: Boolean) {
        viewModelScope.launch {
            appPreferences.setAutoStart(enabled)
        }
    }

    fun onExportConfig() {
        viewModelScope.launch {
            try {
                configManager.generateConfig(app)
                _sideEffect.send(SideEffect.ShowSnackbar("Конфиг экспортирован"))
            } catch (e: Exception) {
                _sideEffect.send(SideEffect.ShowError(e.message ?: "Ошибка экспорта"))
            }
        }
    }

    fun onCheckUpdate() {
        viewModelScope.launch {
            updateManager.checkForUpdate()
            val state = updateManager.state.value
            if (state is UpdateState.Idle) {
                _sideEffect.send(SideEffect.ShowSnackbar("Обновлений нет"))
            }
        }
    }

    fun onDownloadUpdate(info: UpdateInfo) {
        viewModelScope.launch {
            updateManager.downloadAndInstall(info)
        }
    }

    fun onDismissUpdateError() {
        updateManager.resetState()
    }
}
