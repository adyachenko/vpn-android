package com.sbcfg.manager.domain.model

/**
 * Один VPN-сервер из multi-server конфига. Получаем список через
 * GET /api/meta/{token}. Используется для UI выбора сервера и измерения
 * пинга/скорости прямого канала «клиент → VPS».
 */
data class VpnServer(
    val tag: String,
    val displayName: String,
    val countryCode: String,
    val speedtestUrl: String,
)
