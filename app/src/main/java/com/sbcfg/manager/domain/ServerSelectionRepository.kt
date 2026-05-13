package com.sbcfg.manager.domain

import android.content.Context
import com.sbcfg.manager.data.preferences.AppPreferences
import com.sbcfg.manager.util.AppLog
import com.sbcfg.manager.vpn.BoxService
import com.sbcfg.manager.vpn.ClashApiClient
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ручной override выбора сервера. Хранит выбор в DataStore и переключает
 * активный outbound в `proxy-select` через Clash API, пока VPN запущен.
 *
 * Контракт значения tag:
 *  - `null` → «Авто», т.е. selector default = `proxy-auto` (sing-box urltest)
 *  - `"<tag>"` → конкретный сервер, selector переключается на `server-<tag>`
 */
@Singleton
class ServerSelectionRepository @Inject constructor(
    private val appPreferences: AppPreferences,
) {
    /** Текущий выбор пользователя. null = авто. */
    val selectedTag: Flow<String?> = appPreferences.selectedServerTag

    /**
     * Сохранить новый выбор и применить его к активному Clash API клиенту
     * (если VPN запущен). Если VPN остановлен — изменение применится при
     * следующем старте через [applyToClient].
     */
    suspend fun selectServer(tag: String?) {
        appPreferences.setSelectedServerTag(tag)
        AppLog.i(TAG, "Selected server: ${tag ?: "auto"}")
        BoxService.clashClient?.let { applyToClient(it, tag) }
    }

    /**
     * Применить сохранённый выбор к только что созданному [client]. Вызывается
     * [BoxService] сразу после поднятия Clash API после старта VPN.
     */
    suspend fun applyToCurrentClient(client: ClashApiClient) {
        val tag = appPreferences.selectedServerTag.first()
        applyToClient(client, tag)
    }

    private fun applyToClient(client: ClashApiClient, tag: String?) {
        val option = tagToOutbound(tag)
        try {
            client.selectOutbound(SELECTOR_GROUP, option)
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to apply selector → $option: ${e.message}")
        }
    }

    private fun tagToOutbound(tag: String?): String =
        if (tag.isNullOrBlank()) AUTO_OUTBOUND else "server-$tag"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Holder {
        fun selectionRepository(): ServerSelectionRepository
    }

    companion object {
        private const val TAG = "ServerSelection"
        private const val SELECTOR_GROUP = "proxy-select"
        private const val AUTO_OUTBOUND = "proxy-auto"

        /** Достать singleton из не-Hilt-managed [BoxService]. */
        fun get(context: Context): ServerSelectionRepository =
            EntryPointAccessors.fromApplication(context, Holder::class.java)
                .selectionRepository()

        /** Удобный shorthand для использования из [BoxService] корутины. */
        fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Запустить применение сохранённого выбора в фоне. Используется
         * [BoxService] сразу после поднятия Clash API.
         */
        fun applyAfterVpnStart(context: Context, client: ClashApiClient) {
            scope().launch {
                try {
                    get(context).applyToCurrentClient(client)
                } catch (e: Exception) {
                    AppLog.w(TAG, "applyAfterVpnStart failed: ${e.message}")
                }
            }
        }
    }
}
