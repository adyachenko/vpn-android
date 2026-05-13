package com.sbcfg.manager.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class AppPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private companion object {
        val AUTO_START = booleanPreferencesKey("auto_start")
        val VPN_WAS_RUNNING = booleanPreferencesKey("vpn_was_running")
        val CONFIG_URL = stringPreferencesKey("config_url")
        val SERVERS_META_JSON = stringPreferencesKey("servers_meta_json")
        val SERVERS_META_FETCHED_AT = longPreferencesKey("servers_meta_fetched_at")
        val SELECTED_SERVER_TAG = stringPreferencesKey("selected_server_tag")
        val SELECTED_PROTOCOL = stringPreferencesKey("selected_protocol")
    }

    // Default: on. The reboot-resume only fires if both autoStart and
    // vpnWasRunning are true, so turning this on by default is safe — fresh
    // installs without a config still won't start anything.
    val autoStart: Flow<Boolean> = dataStore.data.map { it[AUTO_START] ?: true }

    suspend fun setAutoStart(enabled: Boolean) {
        dataStore.edit { it[AUTO_START] = enabled }
    }

    // Tracks the last user intent for VPN state. Set to true when the user
    // starts the tunnel, false when they stop it. Default true so that
    // existing installs with autoStart=true keep resuming after a reboot.
    val vpnWasRunning: Flow<Boolean> = dataStore.data.map { it[VPN_WAS_RUNNING] ?: true }

    suspend fun setVpnWasRunning(running: Boolean) {
        dataStore.edit { it[VPN_WAS_RUNNING] = running }
    }

    val configUrl: Flow<String?> = dataStore.data.map { it[CONFIG_URL] }

    suspend fun setConfigUrl(url: String) {
        dataStore.edit { it[CONFIG_URL] = url }
    }

    /**
     * Кэшированный JSON-список VpnServer, полученный с /api/meta.
     * Парсится / сериализуется в [com.sbcfg.manager.domain.ServerListRepository].
     */
    val serversMetaJson: Flow<String?> = dataStore.data.map { it[SERVERS_META_JSON] }

    val serversMetaFetchedAt: Flow<Long?> = dataStore.data.map { it[SERVERS_META_FETCHED_AT] }

    suspend fun setServersMeta(json: String, fetchedAt: Long) {
        dataStore.edit {
            it[SERVERS_META_JSON] = json
            it[SERVERS_META_FETCHED_AT] = fetchedAt
        }
    }

    /**
     * Tag выбранного пользователем сервера (например "primary"). null/отсутствие
     * = «Авто» (sing-box urltest сам выбирает). Используется
     * [com.sbcfg.manager.domain.ServerSelectionRepository] для переключения
     * активного outbound в `proxy-select` через Clash API.
     */
    val selectedServerTag: Flow<String?> = dataStore.data.map { it[SELECTED_SERVER_TAG] }

    suspend fun setSelectedServerTag(tag: String?) {
        dataStore.edit {
            if (tag.isNullOrBlank()) it.remove(SELECTED_SERVER_TAG)
            else it[SELECTED_SERVER_TAG] = tag
        }
    }

    /**
     * Ручной выбор протокола (override): `"hysteria2"` или `"naive"`.
     * `null`/отсутствие = «Авто» — per-server selector держит default
     * (`hysteria2-<tag>`). Используется
     * [com.sbcfg.manager.domain.ProtocolSelectionRepository] для
     * переключения per-server selector через Clash API.
     */
    val selectedProtocol: Flow<String?> = dataStore.data.map { it[SELECTED_PROTOCOL] }

    suspend fun setSelectedProtocol(protocol: String?) {
        dataStore.edit {
            if (protocol.isNullOrBlank()) it.remove(SELECTED_PROTOCOL)
            else it[SELECTED_PROTOCOL] = protocol
        }
    }
}
