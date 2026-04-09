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
        val icon: Drawable,
        val mode: AppMode,
        val isFromServer: Boolean
    )

    data class UiState(
        val apps: List<AppWithRule> = emptyList(),
        val searchQuery: String = "",
        val isLoading: Boolean = true
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var allApps: List<AppWithRule> = emptyList()

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            val installedApps = withContext(Dispatchers.IO) {
                appResolver.getInstalledApps()
            }

            configManager.observeAppRules().collect { rules ->
                val rulesMap = rules.associateBy { it.packageName }

                allApps = installedApps.map { appInfo ->
                    val rule = rulesMap[appInfo.packageName]
                    AppWithRule(
                        packageName = appInfo.packageName,
                        appName = appInfo.appName,
                        icon = appInfo.icon,
                        mode = rule?.let {
                            AppMode.valueOf(it.mode.uppercase())
                        } ?: AppMode.PROXY,
                        isFromServer = rule?.isFromServer ?: false
                    )
                }

                applyFilter()
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applyFilter()
    }

    fun onSetAppMode(packageName: String, appName: String, mode: AppMode) {
        viewModelScope.launch {
            configManager.setAppMode(packageName, appName, mode)
        }
    }

    private fun applyFilter() {
        val query = _uiState.value.searchQuery.lowercase()
        val filtered = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter {
                it.appName.lowercase().contains(query) ||
                    it.packageName.lowercase().contains(query)
            }
        }
        _uiState.update { it.copy(apps = filtered, isLoading = false) }
    }
}
