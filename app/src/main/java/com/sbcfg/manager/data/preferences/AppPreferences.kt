package com.sbcfg.manager.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
}
