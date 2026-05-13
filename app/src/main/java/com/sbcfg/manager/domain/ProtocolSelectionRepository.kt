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
 * Ручной override выбора протокола (hysteria2 vs naive) на всех серверах.
 * Хранит выбор в DataStore и переключает per-server selectors (`server-<tag>`)
 * через Clash API, пока VPN запущен.
 *
 * Контракт значения:
 *  - `null` → «Авто», каждый `server-<tag>` selector держит свой default
 *    (`hysteria2-<tag>` по серверной топологии).
 *  - `"hysteria2"` / `"naive"` → принудительно `<protocol>-<tag>` на каждом
 *    `server-<tag>`. Применяется ко всем известным серверам из кэша
 *    [ServerListRepository], чтобы выбор был сквозным независимо от
 *    переключения сервера.
 */
@Singleton
class ProtocolSelectionRepository @Inject constructor(
    private val appPreferences: AppPreferences,
    private val serverList: ServerListRepository,
) {
    /** Текущий выбор. null = авто. */
    val selectedProtocol: Flow<String?> = appPreferences.selectedProtocol

    suspend fun selectProtocol(protocol: String?) {
        appPreferences.setSelectedProtocol(protocol)
        AppLog.i(TAG, "Selected protocol: ${protocol ?: "auto"}")
        BoxService.clashClient?.let { applyToClient(it, protocol) }
    }

    /** Применить сохранённый выбор к свежеподнятому Clash API после старта VPN. */
    suspend fun applyToCurrentClient(client: ClashApiClient) {
        val protocol = appPreferences.selectedProtocol.first()
        applyToClient(client, protocol)
    }

    private suspend fun applyToClient(client: ClashApiClient, protocol: String?) {
        val tags = serverList.servers.first().map { it.tag }
        if (tags.isEmpty()) {
            AppLog.w(TAG, "No servers known yet — protocol override skipped")
            return
        }
        for (tag in tags) {
            val group = "server-$tag"
            val target = when (protocol) {
                PROTOCOL_HYSTERIA2 -> "hysteria2-$tag"
                PROTOCOL_NAIVE -> "naive-$tag"
                else -> "hysteria2-$tag"
            }
            try {
                client.selectOutbound(group, target)
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to apply $group → $target: ${e.message}")
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Holder {
        fun protocolRepository(): ProtocolSelectionRepository
    }

    companion object {
        private const val TAG = "ProtocolSelection"
        const val PROTOCOL_HYSTERIA2 = "hysteria2"
        const val PROTOCOL_NAIVE = "naive"

        fun get(context: Context): ProtocolSelectionRepository =
            EntryPointAccessors.fromApplication(context, Holder::class.java)
                .protocolRepository()

        fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun applyAfterVpnStart(context: Context, client: ClashApiClient) {
            scope().launch {
                try {
                    get(context).applyToCurrentClient(client)
                } catch (e: Exception) {
                    AppLog.w("ProtocolSelection", "applyAfterVpnStart failed: ${e.message}")
                }
            }
        }
    }
}
