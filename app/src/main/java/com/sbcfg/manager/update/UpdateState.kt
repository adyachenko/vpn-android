package com.sbcfg.manager.update

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpdateAvailable(val info: UpdateInfo) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Error(val message: String) : UpdateState
}
