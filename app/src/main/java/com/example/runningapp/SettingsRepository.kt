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
    val maxHr: Int = DEFAULT_MAX_HR,
    // Whether Max HR has ever been deliberately set, as opposed to standing at the default. The
    // first deliberate set recomputes all history against the true number (#112); every change
    // after it is future-only. Distinct from any dismissal flag: keeping the current value is
    // still a deliberate set, dismissing a card is not.
    val maxHrEverSet: Boolean = false,
    val targetZone: Int = HrZone.DEFAULT_TARGET.number,
    val coachingEnabled: Boolean = true,
    val aiDataSharingEnabled: Boolean = true,
    val runMode: String = "treadmill", // "treadmill" or "outdoor"
    val splitAnnouncementsEnabled: Boolean = true,
    val autoPauseEnabled: Boolean = true,
    val savedDevices: List<SavedDevice> = emptyList(),
    val activeDeviceAddress: String? = null,
    val activePlanId: String? = null,
    val activeStageId: String? = null,
    // The coach's debrief of the run just finished — text the app renders and nothing reads. Its
    // prescription for the *next* run is not here and is not a setting; see [CoachPrescription].
    val latestCoachMessage: String? = null,
    val simulationEnabled: Boolean = false,
    val testingModeEnabled: Boolean = false
)

/**
 * Whether a write setting AI training data sharing to [enabled] may land.
 *
 * Testing mode outranks the setting: while it is on, sharing cannot be turned back on. The
 * recording side already reads the pair as `aiDataSharingEnabled && !testingModeEnabled`, so a
 * `true` stored underneath testing mode never feeds the coach *during* testing — the damage is
 * what it leaves behind. Turning testing mode off would resume sharing off the back of a tap made
 * while it was suppressed, with no fresh opt-in. Refusing the write is what keeps consent
 * something the runner stated in a state where it meant anything.
 *
 * Turning sharing *off* is always allowed: no state makes withdrawing consent invalid.
 */
fun aiSharingChangeAllowed(enabled: Boolean, testingModeEnabled: Boolean): Boolean =
    !enabled || !testingModeEnabled

/**
 * Whether Max HR counts as deliberately set, for a store that may predate the flag recording it.
 *
 * The flag arrived with #112; before it, Max HR was a field on a screen with a Save button. So an
 * upgrading runner who typed their number has [storedMaxHr] and no [flag], and reading that as
 * "never set" would let their next edit rewrite history that was already recorded against the
 * number they chose — the opposite of the future-only rule.
 *
 * The evidence is the value, not the key: the old Save wrote Max HR on every save, whether or not
 * it had been touched, so the key is present for anyone who ever changed any setting. A stored
 * value *differing* from [DEFAULT_MAX_HR] is the part nobody gets by accident.
 *
 * This leaves one gap by choice: someone who deliberately set exactly [DEFAULT_MAX_HR] before the
 * flag existed still reads as unset. Indistinguishable from the placeholder by construction, and
 * the cost is one retally against a number that produces the same tally anyway.
 */
fun maxHrEverSet(flag: Boolean?, storedMaxHr: Int?): Boolean =
    flag ?: (storedMaxHr != null && storedMaxHr != DEFAULT_MAX_HR)

/**
 * File-level and `internal` rather than private to the repository so the coach's prescription store
 * — a different thing sharing one DataStore — can read [PreferencesKeys.TESTING_MODE_ENABLED] in
 * its own writes without a second copy of the key string. One spelling of a key, one meaning.
 */
internal object PreferencesKeys {
    val MAX_HR = intPreferencesKey("max_hr")
    val MAX_HR_EVER_SET = booleanPreferencesKey("max_hr_ever_set")
    val TARGET_ZONE = intPreferencesKey("target_zone")
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
    val SIMULATION_ENABLED = booleanPreferencesKey("simulation_enabled")
    val TESTING_MODE_ENABLED = booleanPreferencesKey("testing_mode_enabled")
}

class SettingsRepository(private val context: Context) {

    val userSettingsFlow: Flow<UserSettings> = context.dataStore.data
        .map { preferences ->
            val savedDevicesStrings = preferences[PreferencesKeys.SAVED_DEVICES] ?: emptySet()
            val savedDevices = savedDevicesStrings.mapNotNull {
                val parts = it.split("|")
                if (parts.size == 2) SavedDevice(parts[0], parts[1]) else null
            }

            UserSettings(
                maxHr = preferences[PreferencesKeys.MAX_HR] ?: DEFAULT_MAX_HR,
                maxHrEverSet = maxHrEverSet(
                    flag = preferences[PreferencesKeys.MAX_HR_EVER_SET],
                    storedMaxHr = preferences[PreferencesKeys.MAX_HR]
                ),
                // Sanitized on read, not only on write: an edge-zone target stored before #117
                // closed the picker would otherwise keep overstating "In Target" forever.
                targetZone = HrZone.coachingTargetOfNumberOrDefault(preferences[PreferencesKeys.TARGET_ZONE]).number,
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
                simulationEnabled = preferences[PreferencesKeys.SIMULATION_ENABLED] ?: false,
                testingModeEnabled = preferences[PreferencesKeys.TESTING_MODE_ENABLED] ?: false
            )
        }

    /**
     * Records a deliberate Max HR, and that one has now been made.
     *
     * The point of the flag is that `190` is a placeholder nobody chose, so history sitting on it
     * is stranded until someone states the real number. Nothing should call this directly: go
     * through `SessionRepository.setMaxHr`, the one door where stating the number and recomputing
     * the history it invalidates happen together. A surface that trips the flag on its own is a
     * back door stranding history on the placeholder forever (#103).
     *
     * Setting the value it already holds still counts: the runner has confirmed the number, which
     * is exactly the statement the flag records.
     */
    suspend fun setMaxHrDeliberately(maxHr: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MAX_HR] = effectiveMaxHr(maxHr)
            preferences[PreferencesKeys.MAX_HR_EVER_SET] = true
        }
    }

    /**
     * One setting, written on its own. Settings apply the moment they are touched (#112), so a
     * write must never carry a snapshot of everything else along with it — that is how one
     * screen's stale copy overwrites a change made somewhere else.
     */
    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { preferences -> preferences[key] = value }
    }

    suspend fun setTargetZone(zone: HrZone) = put(PreferencesKeys.TARGET_ZONE, zone.number)

    suspend fun setCoachingEnabled(enabled: Boolean) = put(PreferencesKeys.COACHING_ENABLED, enabled)

    suspend fun setSplitAnnouncementsEnabled(enabled: Boolean) =
        put(PreferencesKeys.SPLIT_ANNOUNCEMENTS_ENABLED, enabled)

    suspend fun setAutoPauseEnabled(enabled: Boolean) = put(PreferencesKeys.AUTO_PAUSE_ENABLED, enabled)

    /**
     * Guarded by [aiSharingChangeAllowed]. [setTestingModeEnabled] forces sharing off, but that
     * only holds the rule at the instant testing mode is switched on; this holds it for as long as
     * testing mode stays on, which is what the removed save path used to do.
     *
     * Read inside the same `edit` as the write, so the check cannot be raced by testing mode being
     * switched on between deciding and storing.
     */
    suspend fun setAiDataSharingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            val testingModeEnabled = preferences[PreferencesKeys.TESTING_MODE_ENABLED] == true
            if (!aiSharingChangeAllowed(enabled, testingModeEnabled)) return@edit
            preferences[PreferencesKeys.AI_DATA_SHARING_ENABLED] = enabled
        }
    }

    /**
     * Turning testing mode on also stops this device feeding the AI coach and drops what the coach
     * has already written, so a test run can't shape the next real one.
     *
     * The prescription is *erased*, not ignored on read: with nothing stored and
     * [CoachPrescriptionRepository.prescribe] refusing to write while testing mode is on, no reader
     * needs a testing-mode branch to run the plan as written (#113).
     */
    suspend fun setTestingModeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TESTING_MODE_ENABLED] = enabled
            if (enabled) {
                preferences[PreferencesKeys.AI_DATA_SHARING_ENABLED] = false
                preferences.remove(PreferencesKeys.LATEST_COACH_MESSAGE)
                preferences.clearCoachPrescription()
            }
        }
    }

    /** Storage, not a setting (#112): how the record screen's toggle pre-fills next time. */
    suspend fun setRunMode(runMode: String) = put(PreferencesKeys.RUN_MODE, runMode)

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

    /**
     * The coach's debrief of the run just finished — displayed text, never a knob.
     *
     * Refused under testing mode for the same reason [CoachPrescriptionRepository.prescribe] is,
     * and checked inside the same `edit`: an evaluation already in flight when testing mode came on
     * would otherwise leave a debrief behind that the erase was meant to take.
     */
    suspend fun setLatestCoachMessage(message: String) {
        context.dataStore.edit { preferences ->
            if (preferences[PreferencesKeys.TESTING_MODE_ENABLED] == true) return@edit
            preferences[PreferencesKeys.LATEST_COACH_MESSAGE] = message
        }
    }

    /**
     * Moves the plan on, dropping the coach's prescription with it: those numbers were written for
     * the stage just left, and the new stage's own workout is where the next progression starts.
     * One write, so no run can start against the new stage carrying the old stage's intervals.
     */
    suspend fun advanceStageAndClearPrescription(nextStageId: String?) {
        context.dataStore.edit { preferences ->
            if (nextStageId != null) {
                preferences[PreferencesKeys.ACTIVE_STAGE_ID] = nextStageId
            }
            preferences.clearCoachPrescription()
        }
    }

    suspend fun setSimulationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SIMULATION_ENABLED] = enabled
        }
    }
}
