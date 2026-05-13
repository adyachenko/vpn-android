package com.sbcfg.manager.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ответ GET /api/meta/{token}. Содержит публичную мету серверов —
 * без хайстерии-пароля и naive-кредов. Используется клиентом для UI
 * выбора сервера и измерения скорости.
 */
@Serializable
data class ServersResponseDto(
    val servers: List<ServerMetaDto>,
)

@Serializable
data class ServerMetaDto(
    val tag: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("country_code") val countryCode: String = "",
    @SerialName("speedtest_url") val speedtestUrl: String,
)
