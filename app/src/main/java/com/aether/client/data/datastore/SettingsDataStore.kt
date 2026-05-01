package com.aether.client.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("aether_settings")

class SettingsDataStore(private val context: Context) {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val ALWAYS_CONFIRM = booleanPreferencesKey("always_confirm")
        val USER_ID = stringPreferencesKey("user_id")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SERVER_URL] ?: DEFAULT_SERVER_URL
    }

    val alwaysConfirm: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ALWAYS_CONFIRM] ?: false
    }

    val userId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.USER_ID] ?: UUID.randomUUID().toString()
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[Keys.SERVER_URL] = url.trim().ifBlank { DEFAULT_SERVER_URL } }
    }

    suspend fun setAlwaysConfirm(value: Boolean) {
        context.dataStore.edit { it[Keys.ALWAYS_CONFIRM] = value }
    }

    suspend fun ensureUserId(): String {
        var id = ""
        context.dataStore.edit { prefs ->
            id = prefs[Keys.USER_ID] ?: UUID.randomUUID().toString().also { prefs[Keys.USER_ID] = it }
        }
        return id
    }

    companion object {
        const val DEFAULT_SERVER_URL = "ws://10.0.2.2:8000"
    }
}
