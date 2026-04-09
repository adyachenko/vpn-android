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
        val CONFIG_URL = stringPreferencesKey("config_url")
    }

    val autoStart: Flow<Boolean> = dataStore.data.map { it[AUTO_START] ?: false }

    suspend fun setAutoStart(enabled: Boolean) {
        dataStore.edit { it[AUTO_START] = enabled }
    }

    val configUrl: Flow<String?> = dataStore.data.map { it[CONFIG_URL] }

    suspend fun setConfigUrl(url: String) {
        dataStore.edit { it[CONFIG_URL] = url }
    }
}
