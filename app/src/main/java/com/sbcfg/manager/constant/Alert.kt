package com.sbcfg.manager.constant

enum class Alert {
    RequestVPNPermission,
    EmptyConfiguration,
    StartCommandServer,
    CreateService,
    StartService;

    companion object {
        fun fromInt(value: Int) = entries[value]
    }
}
