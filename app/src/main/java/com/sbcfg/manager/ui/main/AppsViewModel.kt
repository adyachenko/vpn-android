package com.sbcfg.manager.ui.main

import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbcfg.manager.domain.AppResolver
import com.sbcfg.manager.domain.ConfigManager
import com.sbcfg.manager.domain.model.AppMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val configManager: ConfigManager,
    private val appResolver: AppResolver
) : ViewModel() {

    data class AppWithRule(
        val packageName: String,
        val appName: String,
        val icon: Drawable?,
        val mode: AppMode,
        val isFromServer: Boolean
    )

    /** Item shown inside the "add app" picker dialog. */
    data class PickerItem(
        val packageName: String,
        val appName: String,
        val icon: Drawable
    )

    data class UiState(
        val rules: List<AppWithRule> = emptyList(),
        val isLoading: Boolean = true,
        val showPicker: Boolean = false,
        val pickerSearch: String = "",
        val pickerItems: List<PickerItem> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var allInstalled: List<AppResolver.AppInfo> = emptyList()
    private val installedByPackage: MutableMap<String, AppResolver.AppInfo> = mutableMapOf()

    init {
        viewModelScope.launch {
            allInstalled = withContext(Dispatchers.IO) { appResolver.getInstalledApps() }
            installedByPackage.clear()
            installedByPackage.putAll(allInstalled.associateBy { it.packageName })

            configManager.observeAppRules().collect { rules ->
                val mapped = rules.map { rule ->
                    val info = installedByPackage[rule.packageName]
                    AppWithRule(
                        packageName = rule.packageName,
                        appName = info?.appName ?: rule.appName.takeIf { it.isNotBlank() }
                            ?: rule.packageName,
                        icon = info?.icon,
                        mode = AppMode.valueOf(rule.mode.uppercase()),
                        isFromServer = rule.isFromServer
                    )
                }
                _uiState.update { it.copy(rules = mapped, isLoading = false) }
            }
        }
    }

    fun onSetAppMode(packageName: String, appName: String, mode: AppMode) {
        viewModelScope.launch {
            configManager.setAppMode(packageName, appName, mode)
        }
    }

    fun onRemoveApp(packageName: String) {
        viewModelScope.launch {
            configManager.removeAppRule(packageName)
        }
    }

    fun onShowPicker() {
        val existing = _uiState.value.rules.map { it.packageName }.toSet()
        val items = allInstalled
            .filter { it.packageName !in existing }
            .map { PickerItem(it.packageName, it.appName, it.icon) }
        _uiState.update {
            it.copy(showPicker = true, pickerSearch = "", pickerItems = items)
        }
    }

    fun onDismissPicker() {
        _uiState.update { it.copy(showPicker = false, pickerSearch = "") }
    }

    fun onPickerSearchChanged(query: String) {
        _uiState.update { it.copy(pickerSearch = query) }
    }

    fun onPickApp(packageName: String, appName: String) {
        viewModelScope.launch {
            // Default new apps to "direct" — through the tunnel with domain-based routing.
            configManager.setAppMode(packageName, appName, AppMode.DIRECT)
        }
        onDismissPicker()
    }
}
