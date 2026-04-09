package com.sbcfg.manager.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sbcfg.manager.data.local.entity.CustomDomainEntity
import com.sbcfg.manager.domain.ConfigManager
import com.sbcfg.manager.domain.model.DomainMode
import com.sbcfg.manager.util.DomainValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DomainsViewModel @Inject constructor(
    private val configManager: ConfigManager
) : ViewModel() {

    data class UiState(
        val domains: List<CustomDomainEntity> = emptyList(),
        val showAddDialog: Boolean = false,
        val validationError: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            configManager.observeDomains().collect { domains ->
                _uiState.update { it.copy(domains = domains) }
            }
        }
    }

    fun onAddDomain(domain: String, mode: DomainMode) {
        if (!DomainValidator.isValid(domain)) {
            _uiState.update { it.copy(validationError = "Невалидный домен") }
            return
        }

        viewModelScope.launch {
            configManager.addDomainRule(domain, mode)
                .onSuccess {
                    _uiState.update { it.copy(showAddDialog = false, validationError = null) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(validationError = e.message) }
                }
        }
    }

    fun onDeleteDomain(domain: CustomDomainEntity) {
        viewModelScope.launch {
            configManager.removeDomainRule(domain)
        }
    }

    fun onShowAddDialog() {
        _uiState.update { it.copy(showAddDialog = true, validationError = null) }
    }

    fun onDismissAddDialog() {
        _uiState.update { it.copy(showAddDialog = false, validationError = null) }
    }
}
