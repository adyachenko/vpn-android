package com.sbcfg.manager.domain.model

sealed interface ConfigState {
    data object NotConfigured : ConfigState
    data class Loaded(val serverInfo: ServerInfo) : ConfigState
    data class Error(val message: String) : ConfigState
}
