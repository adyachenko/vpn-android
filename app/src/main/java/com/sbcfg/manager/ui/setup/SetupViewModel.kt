package com.sbcfg.manager.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbcfg.manager.domain.ConfigManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val configManager: ConfigManager
) : ViewModel() {

    data class UiState(
        val url: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
        val isConfigured: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onUrlChanged(url: String) {
        _uiState.update { it.copy(url = url, error = null) }
    }

    fun onSubmit() {
        val url = _uiState.value.url.trim()
        if (url.isBlank()) return

        if (!url.contains("/api/config/")) {
            _uiState.update { it.copy(error = "Неверный формат ссылки") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            configManager.fetchAndSaveConfig(url)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, isConfigured = true) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "Ошибка загрузки")
                    }
                }
        }
    }

    fun onUrlFromDeepLink(url: String) {
        _uiState.update { it.copy(url = url) }
        onSubmit()
    }
}
