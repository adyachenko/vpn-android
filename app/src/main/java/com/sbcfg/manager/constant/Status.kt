package com.sbcfg.manager.constant

enum class Status {
    Stopped,
    Starting,
    Started,
    Stopping;

    companion object {
        fun fromInt(value: Int) = entries[value]
    }
}
