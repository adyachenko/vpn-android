package com.sbcfg.manager.ui.main

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbcfg.manager.data.preferences.AppPreferences
import com.sbcfg.manager.domain.ConfigManager
import com.sbcfg.manager.domain.ServerListRepository
import com.sbcfg.manager.domain.ServerSelectionRepository
import com.sbcfg.manager.domain.model.ConfigState
import com.sbcfg.manager.domain.model.VpnServer
import com.sbcfg.manager.util.AppLog
import com.sbcfg.manager.vpn.BoxService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val configManager: ConfigManager,
    private val appPreferences: AppPreferences,
    private val app: Application,
    serverListRepository: ServerListRepository,
    selectionRepository: ServerSelectionRepository,
) : ViewModel() {

    data class UiState(
        val configState: ConfigState = ConfigState.NotConfigured,
        val isGenerating: Boolean = false,
        val vpnRunning: Boolean = false,
        val vpnStartedAt: Long? = null,
        /** null tag = «Авто», иначе выбранный сервер (если присутствует в списке). */
        val selectedServer: VpnServer? = null,
        /** true если в кэше есть >1 сервера — пилюля имеет смысл показывать. */
        val hasMultipleServers: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _sideEffect = Channel<SideEffect>(Channel.BUFFERED)
    val sideEffect = _sideEffect.receiveAsFlow()

    // Will be set after generating config, used by Activity to pass to BoxService
    var pendingConfigJson: String? = null
        private set

    init {
        viewModelScope.launch {
            configManager.observeConfigState().collect { state ->
                _uiState.update { it.copy(configState = state) }
            }
        }
        viewModelScope.launch {
            combine(
                serverListRepository.servers,
                selectionRepository.selectedTag,
            ) { servers, tag ->
                val selected = tag?.let { t -> servers.find { it.tag == t } }
                Pair(selected, servers.size > 1)
            }.collect { (selected, hasMany) ->
                _uiState.update {
                    it.copy(selectedServer = selected, hasMultipleServers = hasMany)
                }
            }
        }
    }

    fun onToggleVpn() {
        AppLog.i("ViewModel", "onToggleVpn() called, vpnRunning=${_uiState.value.vpnRunning}")
        if (_uiState.value.vpnRunning) {
            viewModelScope.launch {
                // Remember the user wants VPN off — so the next boot won't
                // auto-resume the tunnel.
                appPreferences.setVpnWasRunning(false)
                AppLog.i("ViewModel", "Sending SideEffect.StopVpn")
                _sideEffect.send(SideEffect.StopVpn)
            }
        } else {
            viewModelScope.launch {
                _uiState.update { it.copy(isGenerating = true) }
                try {
                    AppLog.i("ViewModel", "Generating config JSON...")
                    pendingConfigJson = configManager.generateConfigJson()
                    AppLog.i("ViewModel", "Config generated, length=${pendingConfigJson?.length}")
                    // Remember the user wants VPN on — the next boot will
                    // auto-resume the tunnel if autostart is enabled.
                    appPreferences.setVpnWasRunning(true)
                    AppLog.i("ViewModel", "Sending SideEffect.RequestVpnPermission")
                    _sideEffect.send(SideEffect.RequestVpnPermission)
                } catch (e: Exception) {
                    AppLog.e("ViewModel", "Config generation failed", e)
                    _sideEffect.send(SideEffect.ShowError(e.message ?: "Ошибка генерации конфига"))
                } finally {
                    _uiState.update { it.copy(isGenerating = false) }
                }
            }
        }
    }

    fun onVpnPermissionGranted() {
        AppLog.i("ViewModel", "onVpnPermissionGranted() called")
        viewModelScope.launch {
            AppLog.i("ViewModel", "Sending SideEffect.StartVpn")
            _sideEffect.send(SideEffect.StartVpn)
        }
    }

    fun onVpnStatusChanged(running: Boolean) {
        AppLog.i("ViewModel", "onVpnStatusChanged($running)")
        // Read the start timestamp from BoxService — it's the source of
        // truth and survives Activity/ViewModel recreation. Generating the
        // timestamp here would reset the uptime counter every time the user
        // reopens the app.
        _uiState.update {
            it.copy(
                vpnRunning = running,
                vpnStartedAt = if (running) BoxService.startedAt else null
            )
        }
    }
}
