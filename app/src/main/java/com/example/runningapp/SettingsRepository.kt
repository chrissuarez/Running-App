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
    val targetZone: Int = HrZone.DEFAULT_TARGET.number,
    val voiceStyle: String = "detailed", // "short" or "detailed"
    val coachingEnabled: Boolean = true,
    val aiDataSharingEnabled: Boolean = true,
    val runMode: String = "treadmill", // "treadmill" or "outdoor"
    val splitAnnouncementsEnabled: Boolean = true,
    val autoPauseEnabled: Boolean = true,
    val savedDevices: List<SavedDevice> = emptyList(),
    val activeDeviceAddress: String? = null,
    val activePlanId: String? = null,
    val activeStageId: String? = null,
    val latestCoachMessage: String? = null,
    val aiRunIntervalSeconds: Int? = null,
    val aiWalkIntervalSeconds: Int? = null,
    val aiRepeats: Int? = null,
    val simulationEnabled: Boolean = false,
    val testingModeEnabled: Boolean = false
)

class SettingsRepository(private val context: Context) {

    private object PreferencesKeys {
        val MAX_HR = intPreferencesKey("max_hr")
        val TARGET_ZONE = intPreferencesKey("target_zone")
        val VOICE_STYLE = stringPreferencesKey("voice_style")
        val COACHING_ENABLED = booleanPreferencesKey("coaching_enabled")
        val AI_DATA_SHARING_ENABLED = booleanPreferencesKey("ai_data_sharing_enabled")
        val RUN_MODE = stringPreferencesKey("run_mode")
        val SPLIT_ANNOUNCEMENTS_ENABLED = booleanPreferencesKey("split_announcements_enabled")
        val AUTO_PAUSE_ENABLED = booleanPreferencesKey("auto_pause_enabled")
        val SAVED_DEVICES = stringSetPreferencesKey("saved_devices")
        val ACTIVE_DEVICE_ADDRESS = stringPreferencesKey("active_device_address")
        val ACTIVE_PLAN_ID = stringPreferencesKey("active_plan_id")
        val ACTIVE_STAGE_ID = stringPreferencesKey("active_stage_id")
        val LATEST_COACH_MESSAGE = stringPreferencesKey("latest_coach_message")
        val AI_RUN_INTERVAL_SECONDS = intPreferencesKey("ai_run_interval_seconds")
        val AI_WALK_INTERVAL_SECONDS = intPreferencesKey("ai_walk_interval_seconds")
        val AI_REPEATS = intPreferencesKey("ai_repeats")
        val SIMULATION_ENABLED = booleanPreferencesKey("simulation_enabled")
        val TESTING_MODE_ENABLED = booleanPreferencesKey("testing_mode_enabled")
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
                targetZone = HrZone.ofNumberOrDefault(preferences[PreferencesKeys.TARGET_ZONE]).number,
                voiceStyle = preferences[PreferencesKeys.VOICE_STYLE] ?: "detailed",
                coachingEnabled = preferences[PreferencesKeys.COACHING_ENABLED] ?: true,
                aiDataSharingEnabled = preferences[PreferencesKeys.AI_DATA_SHARING_ENABLED] ?: true,
                runMode = preferences[PreferencesKeys.RUN_MODE] ?: "treadmill",
                splitAnnouncementsEnabled = preferences[PreferencesKeys.SPLIT_ANNOUNCEMENTS_ENABLED] ?: true,
                autoPauseEnabled = preferences[PreferencesKeys.AUTO_PAUSE_ENABLED] ?: true,
                savedDevices = savedDevices,
                activeDeviceAddress = preferences[PreferencesKeys.ACTIVE_DEVICE_ADDRESS],
                activePlanId = preferences[PreferencesKeys.ACTIVE_PLAN_ID],
                activeStageId = preferences[PreferencesKeys.ACTIVE_STAGE_ID],
                latestCoachMessage = preferences[PreferencesKeys.LATEST_COACH_MESSAGE],
                aiRunIntervalSeconds = preferences[PreferencesKeys.AI_RUN_INTERVAL_SECONDS],
                aiWalkIntervalSeconds = preferences[PreferencesKeys.AI_WALK_INTERVAL_SECONDS],
                aiRepeats = preferences[PreferencesKeys.AI_REPEATS],
                simulationEnabled = preferences[PreferencesKeys.SIMULATION_ENABLED] ?: false,
                testingModeEnabled = preferences[PreferencesKeys.TESTING_MODE_ENABLED] ?: false
            )
        }

    suspend fun updateSettings(settings: UserSettings) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MAX_HR] = settings.maxHr
            preferences[PreferencesKeys.TARGET_ZONE] = HrZone.ofNumberOrDefault(settings.targetZone).number
            preferences[PreferencesKeys.VOICE_STYLE] = settings.voiceStyle
            preferences[PreferencesKeys.COACHING_ENABLED] = settings.coachingEnabled
            preferences[PreferencesKeys.AI_DATA_SHARING_ENABLED] = settings.aiDataSharingEnabled
            preferences[PreferencesKeys.RUN_MODE] = settings.runMode
            preferences[PreferencesKeys.SPLIT_ANNOUNCEMENTS_ENABLED] = settings.splitAnnouncementsEnabled
            preferences[PreferencesKeys.AUTO_PAUSE_ENABLED] = settings.autoPauseEnabled
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

            if (settings.latestCoachMessage != null) {
                preferences[PreferencesKeys.LATEST_COACH_MESSAGE] = settings.latestCoachMessage
            } else {
                preferences.remove(PreferencesKeys.LATEST_COACH_MESSAGE)
            }

            if (settings.aiRunIntervalSeconds != null) {
                preferences[PreferencesKeys.AI_RUN_INTERVAL_SECONDS] = settings.aiRunIntervalSeconds
            } else {
                preferences.remove(PreferencesKeys.AI_RUN_INTERVAL_SECONDS)
            }

            if (settings.aiWalkIntervalSeconds != null) {
                preferences[PreferencesKeys.AI_WALK_INTERVAL_SECONDS] = settings.aiWalkIntervalSeconds
            } else {
                preferences.remove(PreferencesKeys.AI_WALK_INTERVAL_SECONDS)
            }

            if (settings.aiRepeats != null) {
                preferences[PreferencesKeys.AI_REPEATS] = settings.aiRepeats
            } else {
                preferences.remove(PreferencesKeys.AI_REPEATS)
            }
            preferences[PreferencesKeys.SIMULATION_ENABLED] = settings.simulationEnabled
            preferences[PreferencesKeys.TESTING_MODE_ENABLED] = settings.testingModeEnabled
        }
    }

    suspend fun saveDevice(address: String, name: String, makeActive: Boolean = true) {
        context.dataStore.edit { preferences ->
            val current = preferences[PreferencesKeys.SAVED_DEVICES] ?: emptySet()
            val updated = current.toMutableSet()
            // Remove if already exists to update name if needed
            updated.removeIf { it.startsWith("$address|") }
            updated.add("$address|$name")
            preferences[PreferencesKeys.SAVED_DEVICES] = updated
            // Only user-chosen connects promote to active (the service passes makeActive=false
            // for background reconnects, so a dropout can't steal the slot from a newly chosen
            // strap or resurrect a forgotten one's active status).
            if (makeActive) {
                preferences[PreferencesKeys.ACTIVE_DEVICE_ADDRESS] = address
            }
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

    suspend fun setAiAdjustments(
        latestCoachMessage: String?,
        aiRunIntervalSeconds: Int?,
        aiWalkIntervalSeconds: Int?,
        aiRepeats: Int?
    ) {
        context.dataStore.edit { preferences ->
            if (latestCoachMessage != null) {
                preferences[PreferencesKeys.LATEST_COACH_MESSAGE] = latestCoachMessage
            } else {
                preferences.remove(PreferencesKeys.LATEST_COACH_MESSAGE)
            }

            if (aiRunIntervalSeconds != null) {
                preferences[PreferencesKeys.AI_RUN_INTERVAL_SECONDS] = aiRunIntervalSeconds
            } else {
                preferences.remove(PreferencesKeys.AI_RUN_INTERVAL_SECONDS)
            }

            if (aiWalkIntervalSeconds != null) {
                preferences[PreferencesKeys.AI_WALK_INTERVAL_SECONDS] = aiWalkIntervalSeconds
            } else {
                preferences.remove(PreferencesKeys.AI_WALK_INTERVAL_SECONDS)
            }

            if (aiRepeats != null) {
                preferences[PreferencesKeys.AI_REPEATS] = aiRepeats
            } else {
                preferences.remove(PreferencesKeys.AI_REPEATS)
            }
        }
    }

    suspend fun advanceStageAndClearAiIntervals(nextStageId: String?) {
        context.dataStore.edit { preferences ->
            if (nextStageId != null) {
                preferences[PreferencesKeys.ACTIVE_STAGE_ID] = nextStageId
            }
            preferences.remove(PreferencesKeys.AI_RUN_INTERVAL_SECONDS)
            preferences.remove(PreferencesKeys.AI_WALK_INTERVAL_SECONDS)
            preferences.remove(PreferencesKeys.AI_REPEATS)
        }
    }

    suspend fun setSimulationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SIMULATION_ENABLED] = enabled
        }
    }
}
