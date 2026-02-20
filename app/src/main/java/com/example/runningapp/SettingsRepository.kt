package com.example.runningapp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class SavedDevice(
    val address: String,
    val name: String
)

data class UserSettings(
    val maxHr: Int = 190,
    val zone2Low: Int = 120,
    val zone2High: Int = 140,
    val cooldownSeconds: Int = 75,
    val persistenceHighSeconds: Int = 30,
    val persistenceLowSeconds: Int = 45,
    val voiceStyle: String = "detailed", // "short" or "detailed"
    val coachingEnabled: Boolean = true,
    val warmUpDurationSeconds: Int = 480,
    val coolDownDurationSeconds: Int = 180,
    val runMode: String = "treadmill", // "treadmill" or "outdoor"
    val splitAnnouncementsEnabled: Boolean = true,
    val runWalkCoachEnabled: Boolean = false,
    val savedDevices: List<SavedDevice> = emptyList(),
    val activeDeviceAddress: String? = null,
    val activePlanId: String? = null,
    val activeStageId: String? = null
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
        val WARM_UP_DURATION = intPreferencesKey("warm_up_duration")
        val COOL_DOWN_DURATION = intPreferencesKey("cool_down_duration")
        val RUN_MODE = stringPreferencesKey("run_mode")
        val SPLIT_ANNOUNCEMENTS_ENABLED = booleanPreferencesKey("split_announcements_enabled")
        val RUN_WALK_COACH_ENABLED = booleanPreferencesKey("run_walk_coach_enabled")
        val SAVED_DEVICES = stringSetPreferencesKey("saved_devices")
        val ACTIVE_DEVICE_ADDRESS = stringPreferencesKey("active_device_address")
        val ACTIVE_PLAN_ID = stringPreferencesKey("active_plan_id")
        val ACTIVE_STAGE_ID = stringPreferencesKey("active_stage_id")
    }

    val userSettingsFlow: Flow<UserSettings> = context.dataStore.data
        .map { preferences ->
            val savedDevicesStrings = preferences[PreferencesKeys.SAVED_DEVICES] ?: emptySet()
            val savedDevices = savedDevicesStrings.mapNotNull {
                val parts = it.split("|")
                if (parts.size == 2) SavedDevice(parts[0], parts[1]) else null
            }

            UserSettings(
                maxHr = preferences[PreferencesKeys.MAX_HR] ?: 190,
                zone2Low = preferences[PreferencesKeys.ZONE2_LOW] ?: 120,
                zone2High = preferences[PreferencesKeys.ZONE2_HIGH] ?: 140,
                cooldownSeconds = preferences[PreferencesKeys.COOLDOWN_SECONDS] ?: 75,
                persistenceHighSeconds = preferences[PreferencesKeys.PERSISTENCE_HIGH_SECONDS] ?: 30,
                persistenceLowSeconds = preferences[PreferencesKeys.PERSISTENCE_LOW_SECONDS] ?: 45,
                voiceStyle = preferences[PreferencesKeys.VOICE_STYLE] ?: "detailed",
                coachingEnabled = preferences[PreferencesKeys.COACHING_ENABLED] ?: true,
                warmUpDurationSeconds = preferences[PreferencesKeys.WARM_UP_DURATION] ?: 480,
                coolDownDurationSeconds = preferences[PreferencesKeys.COOL_DOWN_DURATION] ?: 180,
                runMode = preferences[PreferencesKeys.RUN_MODE] ?: "treadmill",
                splitAnnouncementsEnabled = preferences[PreferencesKeys.SPLIT_ANNOUNCEMENTS_ENABLED] ?: true,
                runWalkCoachEnabled = preferences[PreferencesKeys.RUN_WALK_COACH_ENABLED] ?: false,
                savedDevices = savedDevices,
                activeDeviceAddress = preferences[PreferencesKeys.ACTIVE_DEVICE_ADDRESS],
                activePlanId = preferences[PreferencesKeys.ACTIVE_PLAN_ID],
                activeStageId = preferences[PreferencesKeys.ACTIVE_STAGE_ID]
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
            preferences[PreferencesKeys.WARM_UP_DURATION] = settings.warmUpDurationSeconds
            preferences[PreferencesKeys.COOL_DOWN_DURATION] = settings.coolDownDurationSeconds
            preferences[PreferencesKeys.RUN_MODE] = settings.runMode
            preferences[PreferencesKeys.SPLIT_ANNOUNCEMENTS_ENABLED] = settings.splitAnnouncementsEnabled
            preferences[PreferencesKeys.RUN_WALK_COACH_ENABLED] = settings.runWalkCoachEnabled
            preferences[PreferencesKeys.SAVED_DEVICES] = settings.savedDevices.map { "${it.address}|${it.name}" }.toSet()
            if (settings.activeDeviceAddress != null) {
                preferences[PreferencesKeys.ACTIVE_DEVICE_ADDRESS] = settings.activeDeviceAddress
            } else {
                preferences.remove(PreferencesKeys.ACTIVE_DEVICE_ADDRESS)
            }
            
            if (settings.activePlanId != null) {
                preferences[PreferencesKeys.ACTIVE_PLAN_ID] = settings.activePlanId
            } else {
                preferences.remove(PreferencesKeys.ACTIVE_PLAN_ID)
            }

            if (settings.activeStageId != null) {
                preferences[PreferencesKeys.ACTIVE_STAGE_ID] = settings.activeStageId
            } else {
                preferences.remove(PreferencesKeys.ACTIVE_STAGE_ID)
            }
        }
    }

    suspend fun saveDevice(address: String, name: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.SAVED_DEVICES] ?: emptySet()
            val updated = current.toMutableSet()
            // Remove if already exists to update name if needed
            updated.removeIf { it.startsWith("$address|") }
            updated.add("$address|$name")
            preferences[PreferencesKeys.SAVED_DEVICES] = updated
            preferences[PreferencesKeys.ACTIVE_DEVICE_ADDRESS] = address
        }
    }

    suspend fun removeDevice(address: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.SAVED_DEVICES] ?: emptySet()
            val updated = current.toMutableSet()
            updated.removeIf { it.startsWith("$address|") }
            preferences[PreferencesKeys.SAVED_DEVICES] = updated
            
            if (preferences[PreferencesKeys.ACTIVE_DEVICE_ADDRESS] == address) {
                preferences.remove(PreferencesKeys.ACTIVE_DEVICE_ADDRESS)
            }
        }
    }

    suspend fun setActiveDevice(address: String?) {
        context.dataStore.edit { preferences ->
            if (address != null) {
                preferences[PreferencesKeys.ACTIVE_DEVICE_ADDRESS] = address
            } else {
                preferences.remove(PreferencesKeys.ACTIVE_DEVICE_ADDRESS)
            }
        }
    }

    suspend fun setActivePlan(planId: String?, stageId: String?) {
        context.dataStore.edit { preferences ->
            if (planId != null) {
                preferences[PreferencesKeys.ACTIVE_PLAN_ID] = planId
            } else {
                preferences.remove(PreferencesKeys.ACTIVE_PLAN_ID)
            }

            if (stageId != null) {
                preferences[PreferencesKeys.ACTIVE_STAGE_ID] = stageId
            } else {
                preferences.remove(PreferencesKeys.ACTIVE_STAGE_ID)
            }
        }
    }
}
