package com.example.runningapp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class UserSettings(
    val maxHr: Int = 190,
    val zone2Low: Int = 120,
    val zone2High: Int = 140,
    val cooldownSeconds: Int = 75,
    val persistenceHighSeconds: Int = 30,
    val persistenceLowSeconds: Int = 45,
    val voiceStyle: String = "detailed", // "short" or "detailed"
    val coachingEnabled: Boolean = true
)

class SettingsRepository(private val context: Context) {

    private object PreferencesKeys {
        val MAX_HR = intPreferencesKey("max_hr")
        val ZONE2_LOW = intPreferencesKey("zone2_low")
        val ZONE2_HIGH = intPreferencesKey("zone2_high")
        val COOLDOWN_SECONDS = intPreferencesKey("cooldown_seconds")
        val PERSISTENCE_HIGH_SECONDS = intPreferencesKey("persistence_high_seconds")
        val PERSISTENCE_LOW_SECONDS = intPreferencesKey("persistence_low_seconds")
        val VOICE_STYLE = stringPreferencesKey("voice_style")
        val COACHING_ENABLED = booleanPreferencesKey("coaching_enabled")
    }

    val userSettingsFlow: Flow<UserSettings> = context.dataStore.data
        .map { preferences ->
            UserSettings(
                maxHr = preferences[PreferencesKeys.MAX_HR] ?: 190,
                zone2Low = preferences[PreferencesKeys.ZONE2_LOW] ?: 120,
                zone2High = preferences[PreferencesKeys.ZONE2_HIGH] ?: 140,
                cooldownSeconds = preferences[PreferencesKeys.COOLDOWN_SECONDS] ?: 75,
                persistenceHighSeconds = preferences[PreferencesKeys.PERSISTENCE_HIGH_SECONDS] ?: 30,
                persistenceLowSeconds = preferences[PreferencesKeys.PERSISTENCE_LOW_SECONDS] ?: 45,
                voiceStyle = preferences[PreferencesKeys.VOICE_STYLE] ?: "detailed",
                coachingEnabled = preferences[PreferencesKeys.COACHING_ENABLED] ?: true
            )
        }

    suspend fun updateSettings(settings: UserSettings) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MAX_HR] = settings.maxHr
            preferences[PreferencesKeys.ZONE2_LOW] = settings.zone2Low
            preferences[PreferencesKeys.ZONE2_HIGH] = settings.zone2High
            preferences[PreferencesKeys.COOLDOWN_SECONDS] = settings.cooldownSeconds
            preferences[PreferencesKeys.PERSISTENCE_HIGH_SECONDS] = settings.persistenceHighSeconds
            preferences[PreferencesKeys.PERSISTENCE_LOW_SECONDS] = settings.persistenceLowSeconds
            preferences[PreferencesKeys.VOICE_STYLE] = settings.voiceStyle
            preferences[PreferencesKeys.COACHING_ENABLED] = settings.coachingEnabled
        }
    }
}
