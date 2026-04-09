package com.sbcfg.manager.ui.main

sealed interface SideEffect {
    data object RequestVpnPermission : SideEffect
    data object StartVpn : SideEffect
    data object StopVpn : SideEffect
    data class ShowError(val message: String) : SideEffect
    data class ShowSnackbar(val message: String) : SideEffect
}
