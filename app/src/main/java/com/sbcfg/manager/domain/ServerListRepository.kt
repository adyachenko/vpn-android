package com.sbcfg.manager.domain

import com.sbcfg.manager.data.preferences.AppPreferences
import com.sbcfg.manager.data.remote.ConfigApiClient
import com.sbcfg.manager.domain.model.VpnServer
import com.sbcfg.manager.util.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Источник списка VPN-серверов для UI. Кэширует ответ /api/meta в DataStore
 * (JSON-строкой) и отдаёт [Flow] для подписки.
 *
 * Стратегия обновления:
 *  - при первом обращении (пустой кэш) или старше [CACHE_TTL_MS] — refresh()
 *  - явный refresh() — для pull-to-refresh в UI
 */
@Singleton
class ServerListRepository @Inject constructor(
    private val apiClient: ConfigApiClient,
    private val appPreferences: AppPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Текущий кэшированный список серверов. Пустой список означает «ещё не загружено». */
    val servers: Flow<List<VpnServer>> = appPreferences.serversMetaJson.map { raw ->
        if (raw.isNullOrBlank()) emptyList() else decode(raw)
    }

    /**
     * Обновить кэш, если он пустой или старше [CACHE_TTL_MS]. Возвращает true,
     * если был выполнен сетевой запрос (успешный или нет).
     */
    suspend fun refreshIfStale(): Boolean {
        val fetchedAt = appPreferences.serversMetaFetchedAt.first() ?: 0L
        val age = System.currentTimeMillis() - fetchedAt
        val cached = appPreferences.serversMetaJson.first()
        val needRefresh = cached.isNullOrBlank() || age > CACHE_TTL_MS
        if (!needRefresh) return false
        refresh()
        return true
    }

    /** Принудительный refresh — для pull-to-refresh / при изменении конфига. */
    suspend fun refresh(): Result<List<VpnServer>> {
        val configUrl = appPreferences.configUrl.first()
            ?: return Result.failure(IllegalStateException("URL конфига не задан"))
        AppLog.i(TAG, "Fetching servers meta...")
        val result = apiClient.fetchMeta(configUrl)
        result.onSuccess { servers ->
            val encoded = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(VpnServerSurrogate.serializer()),
                servers.map { it.toSurrogate() },
            )
            appPreferences.setServersMeta(encoded, System.currentTimeMillis())
            AppLog.i(TAG, "Servers meta updated: ${servers.size} entries")
        }.onFailure { e ->
            AppLog.w(TAG, "Servers meta fetch failed: ${e.message}")
        }
        return result
    }

    private fun decode(raw: String): List<VpnServer> = try {
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(VpnServerSurrogate.serializer()),
            raw,
        ).map { it.toModel() }
    } catch (e: Exception) {
        AppLog.w(TAG, "Failed to decode cached servers meta: ${e.message}")
        emptyList()
    }

    /** Сериализационный двойник [VpnServer] — модель domain-слоя сама по себе
     *  не сериализуется, чтобы не вытаскивать kotlinx-serialization в domain. */
    @Serializable
    private data class VpnServerSurrogate(
        val tag: String,
        val displayName: String,
        val countryCode: String,
        val speedtestUrl: String,
    ) {
        fun toModel() = VpnServer(tag, displayName, countryCode, speedtestUrl)
    }

    private fun VpnServer.toSurrogate() =
        VpnServerSurrogate(tag, displayName, countryCode, speedtestUrl)

    companion object {
        private const val TAG = "ServerList"
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000 // 24 hours
    }
}
