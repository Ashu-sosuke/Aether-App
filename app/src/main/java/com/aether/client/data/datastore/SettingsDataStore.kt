package com.aether.client.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val SERVER_URL_KEY = stringPreferencesKey("server_url")
    private val ALWAYS_CONFIRM_KEY = booleanPreferencesKey("always_confirm")
    private val USER_ID_KEY = stringPreferencesKey("user_id")

    // SET DEFAULT URL HERE
    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SERVER_URL_KEY] ?: "https://aether-rl.onrender.com"
    }

    val alwaysConfirm: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[ALWAYS_CONFIRM_KEY] ?: false
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[SERVER_URL_KEY] = url }
    }

    suspend fun setAlwaysConfirm(value: Boolean) {
        context.dataStore.edit { it[ALWAYS_CONFIRM_KEY] = value }
    }

    suspend fun ensureUserId(): String {
        var id = ""
        context.dataStore.edit { prefs ->
            val existing = prefs[USER_ID_KEY]
            if (existing == null) {
                val newId = UUID.randomUUID().toString()
                prefs[USER_ID_KEY] = newId
                id = newId
            } else {
                id = existing
            }
        }
        return id
    }
}