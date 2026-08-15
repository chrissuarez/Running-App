package com.example.runningapp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.runningapp.archive.ArchivedSettings
import com.example.runningapp.training.PlanCompletion
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    // The one upgrade this store needs: the pre-#175 global prescription and its debrief, taken away
    // before anything can read them. See [dropLegacyCoachWork].
    produceMigrations = { listOf(dropLegacyCoachWork) }
)

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
    // Whether the Progress screen's one-time "confirm your Max HR" card has been put away (#65).
    // A separate fact from [maxHrEverSet] and never a substitute for it: closing the card states
    // nothing, and confirming a number is a statement whether or not the number changed. Two
    // events, two flags — one flag would either re-ask a runner who answered or spend the one-shot
    // recompute on a card someone swiped away.
    val maxHrCardDismissed: Boolean = false,
    // The other end of the reserve the zones are sliced from (#172). Unstated by default, which
    // is not a gap to fill in but a value in its own right: it reproduces the Max-HR-only model
    // exactly, so nobody's zones move until they measure and state a number.
    val restingHr: Int = RESTING_HR_UNSTATED,
    // The Max HR every finished Run's zone times are banded against, which is *not* always [maxHr].
    // The first deliberate set re-bands all history and every change after it is future-only
    // (#112), so a runner who set 181 and later corrected to 195 has history on 181 and zones on
    // 195 — deliberately, because a correction must not rewrite runs already read. Anything that
    // re-bands history has to use this, or a resting-HR statement would drag the whole of history
    // onto the later maximum by a side door (#172).
    val historyMaxHr: Int = DEFAULT_MAX_HR,
    val targetZone: Int = HrZone.DEFAULT_TARGET.number,
    val coachingEnabled: Boolean = true,
    val aiDataSharingEnabled: Boolean = true,
    val runMode: String = "treadmill", // "treadmill" or "outdoor"
    val splitAnnouncementsEnabled: Boolean = true,
    // The halfway "turn around" cue (#208). See RunControls.turnaroundCueEnabled for why it is on.
    val turnaroundCueEnabled: Boolean = true,
    val autoPauseEnabled: Boolean = true,
    val savedDevices: List<SavedDevice> = emptyList(),
    val activeDeviceAddress: String? = null,
    val activePlanId: String? = null,
    // The Stage the runner is actually in, not the string the preference happens to hold: resolved
    // against the attached plan on the way out of storage, so a preference naming no Stage or one
    // the plan does not hold reads as the plan's first — the Stage the card shows and the Workouts
    // come from. Resolved here rather than by each reader because a Stage the readers can disagree
    // about is how a Run got stamped with one it was never shown (#234), and every disagreement
    // after that is the same bug wearing a different reader's clothes.
    val activeStageId: String? = null,
    // The Plan the runner has finished, if they have finished one (#294). Recorded by the
    // graduation rule at the moment it grants on a Plan's last Stage, and never worked out from
    // history afterwards — see [PlanCompletion]. Null is "no Plan has been finished", which is the
    // truth about every runner until one is.
    val planCompletion: PlanCompletion? = null,
    // The debrief of the run just finished — text the app renders and nothing reads. The coach's
    // prescription for the *next* run is not here and is not a setting; see [CoachPrescription].
    val latestDebrief: String? = null,
    // Who wrote that debrief (#296). The app writes into the same slot — a graduation, a Plan
    // finished, a Test missed — and the screen puts a heading over whatever is there, so the name
    // has to be stored with the text rather than guessed at from it. Defaults to the coach, which
    // is what every debrief stored before this stamp existed was.
    val latestDebriefAuthor: DebriefAuthor = DebriefAuthor.COACH,
    val simulationEnabled: Boolean = false,
    val testingModeEnabled: Boolean = false,
    // The folder the runner picked for full archives, as a Storage Access Framework tree Uri, and
    // when one was last written there (#85). Null and null until they pick one and a backup lands —
    // and a last-backup time is a claim that there is a backup, so it is only ever written after a
    // complete archive has been promoted into place.
    val backupFolderUri: String? = null,
    val lastBackupAtEpochMillis: Long? = null,
    // Whether every Run already in history has been put to the record book (#50). False until the
    // one-off seeding pass has completed, and false again whenever different history arrives — see
    // [SessionRepository.seedRecordsFromHistory]. Scoring history means measuring every stored
    // track, which is minutes of work and must not happen at every launch; this is what stops it.
    val historyRecordsSeeded: Boolean = false
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
 * The heart rates as they will be stored, given what is there now and what is being stated.
 *
 * Pure and separate from the write for the reason [coachWriteAllowed] is: the rule is worth being
 * able to read and test without a DataStore behind it.
 *
 * A null *stated* number means "not stated now" and leaves that one alone. A null [StoredHeartRates.restingHr]
 * out means there is no resting heart rate stored and none is being stated, so nothing should be
 * written under that key — distinct from [RESTING_HR_UNSTATED], which is a stored, deliberate
 * "no resting heart rate".
 *
 * **Both numbers are clamped against each other as stored**, so storage can never hold a pair with
 * no reserve between them: a resting heart rate is only unusable *relative* to a maximum. That is
 * what makes lowering the maximum bring a stranded resting heart rate back into range even when
 * only the maximum was stated.
 *
 * A backstop rather than the rule. The settings screen *refuses* a pair with no usable reserve
 * ([parseMaxHr], [parseRestingHr]), because rewriting a measured number the runner never retyped
 * is the silent replacement #172 exists to delete, so nothing on that path should reach it. It
 * stays because storage must hold a usable pair whatever calls it, and a clamp that never fires
 * costs nothing — but it is not permission to strand the number.
 */
data class StoredHeartRates(val maxHr: Int, val restingHr: Int?)

/**
 * A statement of one or both heart rates. Null means "not stated" — not [RESTING_HR_UNSTATED],
 * which is a resting heart rate deliberately withdrawn.
 */
data class StatedHeartRates(val maxHr: Int?, val restingHr: Int?)

fun storedHeartRates(
    statedMaxHr: Int?,
    statedRestingHr: Int?,
    storedMaxHr: Int?,
    storedRestingHr: Int?
): StoredHeartRates {
    val maxHr = effectiveMaxHr(statedMaxHr ?: storedMaxHr ?: DEFAULT_MAX_HR)
    val restingHr = statedRestingHr ?: storedRestingHr
    return StoredHeartRates(maxHr, restingHr?.let { effectiveRestingHr(it, maxHr) })
}

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
 * — a different thing sharing one DataStore — can reach [PreferencesKeys.TESTING_MODE_ENABLED]
 * through [editUnlessTestingMode] without a second copy of the key string. One spelling of a key,
 * one meaning.
 */
internal object PreferencesKeys {
    val MAX_HR = intPreferencesKey("max_hr")
    val MAX_HR_EVER_SET = booleanPreferencesKey("max_hr_ever_set")
    // Whether the Progress screen's confirmation card has been put away (#65). See
    // UserSettings.maxHrCardDismissed.
    val MAX_HR_CARD_DISMISSED = booleanPreferencesKey("max_hr_card_dismissed")
    // The maximum every finished Run's zone times are currently banded against — not necessarily
    // the one in force. See UserSettings.historyMaxHr.
    val HISTORY_MAX_HR = intPreferencesKey("history_max_hr")
    val RESTING_HR = intPreferencesKey("resting_hr")
    val TARGET_ZONE = intPreferencesKey("target_zone")
    val COACHING_ENABLED = booleanPreferencesKey("coaching_enabled")
    val AI_DATA_SHARING_ENABLED = booleanPreferencesKey("ai_data_sharing_enabled")
    val RUN_MODE = stringPreferencesKey("run_mode")
    val SPLIT_ANNOUNCEMENTS_ENABLED = booleanPreferencesKey("split_announcements_enabled")
    val TURNAROUND_CUE_ENABLED = booleanPreferencesKey("turnaround_cue_enabled")
    val AUTO_PAUSE_ENABLED = booleanPreferencesKey("auto_pause_enabled")
    val SAVED_DEVICES = stringSetPreferencesKey("saved_devices")
    val ACTIVE_DEVICE_ADDRESS = stringPreferencesKey("active_device_address")
    val ACTIVE_PLAN_ID = stringPreferencesKey("active_plan_id")
    val ACTIVE_STAGE_ID = stringPreferencesKey("active_stage_id")
    // The Plan the runner has finished, and what finished it (#294). Three keys because a
    // completion is three facts, written and read as one — see [planCompletionOf].
    val PLAN_COMPLETE_PLAN_ID = stringPreferencesKey("plan_complete_plan_id")
    val PLAN_COMPLETE_DAY = longPreferencesKey("plan_complete_day")
    val PLAN_COMPLETE_SECONDS = intPreferencesKey("plan_complete_seconds")
    val LATEST_COACH_MESSAGE = stringPreferencesKey("latest_coach_message")
    // The debrief belonging to the word the coach said before the standing one, kept so that a
    // rollback moves the text and the numbers together (#156). Never read by the card: only the
    // standing debrief is ever shown.
    val PREVIOUS_COACH_MESSAGE = stringPreferencesKey("previous_coach_message")
    val SIMULATION_ENABLED = booleanPreferencesKey("simulation_enabled")
    val TESTING_MODE_ENABLED = booleanPreferencesKey("testing_mode_enabled")
    // A statement of the heart rates that has started moving history but has not yet been stored.
    // See SettingsRepository.beginStatement.
    val STATEMENT_IN_FLIGHT = booleanPreferencesKey("hr_statement_in_flight")
    val STATEMENT_MAX_HR = intPreferencesKey("hr_statement_max_hr")
    val STATEMENT_RESTING_HR = intPreferencesKey("hr_statement_resting_hr")
    val BACKUP_FOLDER_URI = stringPreferencesKey("backup_folder_uri")
    val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
    // Whether the history already recorded has been scored against the record book (#50). See
    // UserSettings.historyRecordsSeeded.
    val HISTORY_RECORDS_SEEDED = booleanPreferencesKey("history_records_seeded")
}

/**
 * The plan and stage an evaluation reasoned about, carried so its writes can be refused if the
 * ground moved while the coach was thinking (#113).
 */
data class CoachWriteScope(val planId: String?, val stageId: String?)

/**
 * Everything the AI coach writes, gated in one place (#113).
 *
 * Asking the coach is a network round trip — seconds, not milliseconds — so anything read before
 * it is a snapshot that can be stale by the time the reply lands. Both conditions are therefore
 * re-checked inside the same `edit` as the write, where nothing can change between the check and
 * the store:
 *
 * - **Testing mode**, which erases the coach's work and forbids more of it. An evaluation already
 *   in flight would otherwise land just after the erase that was meant to stop it.
 * - **The plan and stage the evaluation was about.** Choosing a different plan clears the outgoing
 *   prescription ([SettingsRepository.setActivePlan]), but a reply still in flight would land after
 *   that and overwrite day one of the plan just chosen — the one workout the runner picked the plan
 *   *for* — with intervals reasoned about against the plan they left.
 *
 * A reply that fails either check is discarded whole rather than partly applied: the debrief
 * narrates the prescription, so storing one without the other would leave the runner reading about
 * a change that never happened.
 *
 * Every write the coach makes goes through here, so this is one rule with one implementation rather
 * than a habit each new write has to copy.
 */
internal suspend fun DataStore<Preferences>.editCoachWrite(
    scope: CoachWriteScope,
    block: (MutablePreferences) -> Unit
) {
    edit { preferences ->
        val allowed = coachWriteAllowed(
            testingModeEnabled = preferences[PreferencesKeys.TESTING_MODE_ENABLED],
            activePlanId = preferences[PreferencesKeys.ACTIVE_PLAN_ID],
            activeStageId = preferences[PreferencesKeys.ACTIVE_STAGE_ID],
            scope = scope
        )
        if (allowed) block(preferences)
    }
}

/**
 * Whether the coach's reply may still be written, given what is stored *now*.
 *
 * Pure and separate from [editCoachWrite] so the rule can be read and tested without a DataStore —
 * same reason [aiSharingChangeAllowed] and [maxHrEverSet] are. The caller supplies the stored
 * values from inside its own `edit`, which is what makes the decision unraceable.
 *
 * An unset testing-mode key is off, not unknown: absent means never turned on.
 *
 * [activeStageId] arrives raw from storage while [scope] carries a resolved one
 * ([UserSettings.activeStageId]), so it is resolved here before the two are compared. Comparing
 * them as they stand would refuse every coach write on a plan whose stage preference names nothing
 * — the runner has not moved at all, which is exactly the case this is meant to let through.
 */
internal fun coachWriteAllowed(
    testingModeEnabled: Boolean?,
    activePlanId: String?,
    activeStageId: String?,
    scope: CoachWriteScope
): Boolean =
    testingModeEnabled != true &&
        activePlanId == scope.planId &&
        TrainingPlanProvider.resolveActiveStage(activePlanId, activeStageId)?.id == scope.stageId

/**
 * Everything the coach left behind, dropped together (#113).
 *
 * The debrief explains the prescription, so the two are one thing to invalidate — keeping the text
 * after the numbers are gone leaves the runner reading about a workout that is not what is queued.
 * Named once so the settings that invalidate the coach's work cannot drop half of it.
 *
 * Both generations go, debriefs and provenance included — [clearCoachPrescriptions] takes the
 * previous word whole, and the standing debrief is the one line added here (#156).
 */
internal fun MutablePreferences.clearCoachWork() {
    clearCoachPrescriptions()
    // The debrief and the name on it, together: a stamp left behind is a heading waiting over words
    // that are gone (#296).
    removeStandingDebrief()
}

/**
 * The Plan the runner has finished, out of the three keys that hold it (#294), or null where no Plan
 * has been finished.
 *
 * All three or none. They are only ever written together, in one edit, so a partial trio cannot
 * arise from this app — and reading one anyway would be inventing the missing part of a fact that
 * exists precisely once and cannot be taken back. A completion missing its day is not a completion.
 */
internal fun planCompletionOf(preferences: Preferences): PlanCompletion? {
    val planId = preferences[PreferencesKeys.PLAN_COMPLETE_PLAN_ID] ?: return null
    val day = preferences[PreferencesKeys.PLAN_COMPLETE_DAY] ?: return null
    val seconds = preferences[PreferencesKeys.PLAN_COMPLETE_SECONDS] ?: return null
    return PlanCompletion(planId = planId, completedOnEpochDay = day, seconds = seconds)
}

/**
 * The days a Plan can plausibly have been finished on: after the epoch, and inside the range a
 * calendar day can be read back in.
 *
 * The floor is 1 rather than 0 because a completion is the day of a *Run*, and this app recorded no
 * Runs in 1970 — a zero there is a field a document never carried, not an afternoon.
 */
private val PLAN_COMPLETION_DAYS = 1L..LocalDate.MAX.toEpochDay()

/**
 * Stores a Plan the runner has finished, or takes all three keys away where there is none — the
 * whole fact in one write, which is what makes [planCompletionOf]'s all-or-none read true.
 */
internal fun MutablePreferences.writePlanCompletion(completion: PlanCompletion?) {
    // A completion missing any of its three parts is no completion, the same reading
    // [planCompletionOf] gives half a trio of keys. It cannot be built in Kotlin, but it can
    // arrive: an archive is JSON read by Gson, which fills a field a truncated document never
    // mentioned — with null whatever a reference type says, and with a silent 0 for a Long or an
    // Int, which is why the day and the time are checked and not only the Plan. A completion stored
    // out of those defaults would put the runner's finest afternoon on 1 January 1970 in 0:00, and
    // a day far enough out of range would make the card throw rather than read wrong.
    //
    // Refused here rather than at the read, so one malformed field costs a restore a fact it never
    // really had instead of costing it the whole archive.
    @Suppress("SENSELESS_COMPARISON")
    if (completion == null ||
        completion.planId == null ||
        completion.planId.isBlank() ||
        completion.completedOnEpochDay !in PLAN_COMPLETION_DAYS ||
        completion.seconds <= 0
    ) {
        remove(PreferencesKeys.PLAN_COMPLETE_PLAN_ID)
        remove(PreferencesKeys.PLAN_COMPLETE_DAY)
        remove(PreferencesKeys.PLAN_COMPLETE_SECONDS)
        return
    }
    this[PreferencesKeys.PLAN_COMPLETE_PLAN_ID] = completion.planId
    this[PreferencesKeys.PLAN_COMPLETE_DAY] = completion.completedOnEpochDay
    this[PreferencesKeys.PLAN_COMPLETE_SECONDS] = completion.seconds
}

/**
 * A finished Plan recorded and the runner told so, or nothing at all where this Plan is already
 * recorded as finished (#294).
 *
 * Pure and separate from the write around it, for the reason [coachWriteAllowed] is: "once" is the
 * rule this holds, and a rule is worth reading and testing without a DataStore behind it.
 *
 * The congratulation is stamped [DebriefAuthor.APP] — always, with no author to pass in — because
 * it is written from the Plan's own numbers, offline and with no Gemini key (#296).
 */
internal fun MutablePreferences.completePlanOnce(completion: PlanCompletion, message: String) {
    if (planCompletionOf(this)?.planId == completion.planId) return
    writePlanCompletion(completion)
    writeStandingDebrief(message, DebriefAuthor.APP)
}

/**
 * Everything stored, read as what it means (#234).
 *
 * Pure and separate from the flow that publishes it, for the reason [coachWriteAllowed] is: the
 * rules applied on the way out — an edge target zone snapped, a maximum inferred, the Stage
 * resolved against its plan — are the settings the app actually runs on, and they should be
 * readable and testable without a DataStore.
 */
internal fun userSettingsOf(preferences: Preferences): UserSettings {
    val savedDevicesStrings = preferences[PreferencesKeys.SAVED_DEVICES] ?: emptySet()
    val savedDevices = savedDevicesStrings.mapNotNull {
        val parts = it.split("|")
        if (parts.size == 2) SavedDevice(parts[0], parts[1]) else null
    }

    return UserSettings(
        maxHr = preferences[PreferencesKeys.MAX_HR] ?: DEFAULT_MAX_HR,
        maxHrEverSet = maxHrEverSet(
            flag = preferences[PreferencesKeys.MAX_HR_EVER_SET],
            storedMaxHr = preferences[PreferencesKeys.MAX_HR]
        ),
        maxHrCardDismissed = preferences[PreferencesKeys.MAX_HR_CARD_DISMISSED] ?: false,
        restingHr = preferences[PreferencesKeys.RESTING_HR] ?: RESTING_HR_UNSTATED,
        // Absent for anyone whose history was last banded before this key existed. Their stored
        // maximum is the best evidence available: if they set it once and never changed it — much
        // the commonest case — it is exactly right, and if they changed it twice the value it was
        // banded against is simply not recorded anywhere. Either way this is no worse than the
        // behaviour it replaces.
        historyMaxHr = preferences[PreferencesKeys.HISTORY_MAX_HR]
            ?: (preferences[PreferencesKeys.MAX_HR] ?: DEFAULT_MAX_HR),
        // Sanitized on read, not only on write: an edge-zone target stored before #117 closed the
        // picker would otherwise keep overstating "In Target" forever.
        targetZone = HrZone.coachingTargetOfNumberOrDefault(preferences[PreferencesKeys.TARGET_ZONE]).number,
        coachingEnabled = preferences[PreferencesKeys.COACHING_ENABLED] ?: true,
        aiDataSharingEnabled = preferences[PreferencesKeys.AI_DATA_SHARING_ENABLED] ?: true,
        runMode = preferences[PreferencesKeys.RUN_MODE] ?: "treadmill",
        splitAnnouncementsEnabled = preferences[PreferencesKeys.SPLIT_ANNOUNCEMENTS_ENABLED] ?: true,
        turnaroundCueEnabled = preferences[PreferencesKeys.TURNAROUND_CUE_ENABLED] ?: true,
        autoPauseEnabled = preferences[PreferencesKeys.AUTO_PAUSE_ENABLED] ?: true,
        savedDevices = savedDevices,
        activeDeviceAddress = preferences[PreferencesKeys.ACTIVE_DEVICE_ADDRESS],
        activePlanId = preferences[PreferencesKeys.ACTIVE_PLAN_ID],
        // Resolved on read, the same way the target zone is sanitized on read above: the stored
        // string is where the Stage is kept, and the Stage the runner is in is what everything
        // downstream asks for. See [UserSettings.activeStageId].
        activeStageId = TrainingPlanProvider.resolveActiveStage(
            preferences[PreferencesKeys.ACTIVE_PLAN_ID],
            preferences[PreferencesKeys.ACTIVE_STAGE_ID]
        )?.id,
        planCompletion = planCompletionOf(preferences),
        latestDebrief = preferences[PreferencesKeys.LATEST_COACH_MESSAGE],
        latestDebriefAuthor = debriefAuthorOf(preferences),
        simulationEnabled = preferences[PreferencesKeys.SIMULATION_ENABLED] ?: false,
        testingModeEnabled = preferences[PreferencesKeys.TESTING_MODE_ENABLED] ?: false,
        backupFolderUri = preferences[PreferencesKeys.BACKUP_FOLDER_URI],
        lastBackupAtEpochMillis = preferences[PreferencesKeys.LAST_BACKUP_AT],
        historyRecordsSeeded = preferences[PreferencesKeys.HISTORY_RECORDS_SEEDED] ?: false
    )
}

class SettingsRepository(private val context: Context) {

    val userSettingsFlow: Flow<UserSettings> = context.dataStore.data
        .map { preferences -> userSettingsOf(preferences) }

    /**
     * Records a statement of the heart rates the runner's zones are sliced from — either number,
     * or both — in **one** write.
     *
     * One write because the pair bounds one reserve. Published separately, a collector sees the
     * new maximum beside the old resting heart rate, and a Run started in that gap pins a profile
     * that was never anyone's (ADR 0002 pins it at START and never revisits it).
     *
     * Null means "not stated now" and leaves that number alone. Nothing should call this directly:
     * go through `SessionRepository.setStatedProfile`, the one door where stating the numbers and
     * re-banding the history they move happen together, and which owns the reasoning about what
     * that re-banding is against. A surface writing here on its own is a back door stranding
     * history on a profile nobody chose (#103, #172).
     *
     * What actually gets stored is [storedHeartRates] — pure, so the rule can be read and tested
     * without a DataStore. It is applied inside the same `edit` that reads the current values, so
     * nothing can move between the decision and the write.
     *
     * [rebandedHistoryAgainst] has no default on purpose: taken by omission it would quietly mean
     * "moved no history", and the caller that did move some would leave a note nothing ever clears
     * and a maximum nothing ever records.
     */
    suspend fun setStatedHeartRates(maxHr: Int?, restingHr: Int?, rebandedHistoryAgainst: Int?) {
        if (maxHr == null && restingHr == null) return
        context.dataStore.edit { preferences ->
            // Only a statement that actually re-banded history finishes the note, and it records
            // the maximum it banded against while it does.
            //
            // Both halves matter. Recording it is what stops the *next* resting-HR statement
            // dragging history onto a later, future-only maximum. And clearing it only here is
            // what stops a statement that moved no history — a future-only Max HR change — wiping
            // a note left by an interrupted re-tally, which would strand that history for good.
            //
            // Load-bearing rather than tidy-up: never clearing would have every launch re-band the
            // whole of history again for ever, and nothing would fail to say so.
            if (rebandedHistoryAgainst != null) {
                preferences[PreferencesKeys.HISTORY_MAX_HR] = rebandedHistoryAgainst
                preferences.remove(PreferencesKeys.STATEMENT_IN_FLIGHT)
                preferences.remove(PreferencesKeys.STATEMENT_MAX_HR)
                preferences.remove(PreferencesKeys.STATEMENT_RESTING_HR)
            }
            val stored = storedHeartRates(
                statedMaxHr = maxHr,
                statedRestingHr = restingHr,
                storedMaxHr = preferences[PreferencesKeys.MAX_HR],
                storedRestingHr = preferences[PreferencesKeys.RESTING_HR]
            )
            // The maximum carries a flag and the resting heart rate does not: `190` is a
            // placeholder nobody chose, so history sitting on it is stranded until someone states
            // the real number, and stating the value already held still counts — confirming it is
            // exactly the statement the flag records. Only written when the maximum was actually
            // stated, so a resting-only statement can never spend the one-shot.
            if (maxHr != null) {
                preferences[PreferencesKeys.MAX_HR] = stored.maxHr
                preferences[PreferencesKeys.MAX_HR_EVER_SET] = true
            }
            stored.restingHr?.let { preferences[PreferencesKeys.RESTING_HR] = it }
        }
    }

    /**
     * Notes a statement that is about to move history, before it moves any.
     *
     * History lives in the database and the profile lives here, so a statement that touches both
     * cannot be one transaction. Re-banding commits first; if this process dies — or the write
     * below throws — in the gap before the profile lands, every finished run is banded against a
     * profile the settings do not hold and no future run will use. Nothing would ever repair it,
     * because nothing would know: the split #172 exists to prevent, silent and permanent.
     *
     * So the intent is recorded first and cleared only by the statement landing
     * ([setStatedHeartRates]). Anything left behind is an interruption, and replaying it is safe
     * because a re-tally is a pure re-derivation from stored per-second samples, which are never
     * pruned — repeating one costs time and changes nothing. See
     * `SessionRepository.interruptedStatement`, which `StatedHeartRateQueue` applies before anything else.
     *
     * The numbers are carried rather than re-read from storage on the way back, because what
     * history is re-banded against is not simply what ends up stored: Max HR's future-only rule
     * means a maximum stated beside a resting heart rate does not move history at all.
     */
    suspend fun beginStatement(maxHr: Int?, restingHr: Int?) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.STATEMENT_IN_FLIGHT] = true
            if (maxHr != null) preferences[PreferencesKeys.STATEMENT_MAX_HR] = maxHr
            else preferences.remove(PreferencesKeys.STATEMENT_MAX_HR)
            if (restingHr != null) preferences[PreferencesKeys.STATEMENT_RESTING_HR] = restingHr
            else preferences.remove(PreferencesKeys.STATEMENT_RESTING_HR)
        }
    }

    /**
     * A statement that began moving history and never landed, or null if none did.
     *
     * The flag is what says one exists, not the presence of a number: either number may be absent,
     * because a statement of one heart rate leaves the other alone. Both absent is meaningless and
     * unreachable from [beginStatement] — [discardStatement] is how a corrupt one gets cleared,
     * because a note nothing can replay would otherwise be found again on every launch for ever.
     */
    suspend fun interruptedStatement(): StatedHeartRates? {
        val preferences = context.dataStore.data.first()
        if (preferences[PreferencesKeys.STATEMENT_IN_FLIGHT] != true) return null
        return StatedHeartRates(
            maxHr = preferences[PreferencesKeys.STATEMENT_MAX_HR],
            restingHr = preferences[PreferencesKeys.STATEMENT_RESTING_HR]
        )
    }

    /** Drops an in-flight note without applying anything. See [interruptedStatement]. */
    suspend fun discardStatement() {
        context.dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.STATEMENT_IN_FLIGHT)
            preferences.remove(PreferencesKeys.STATEMENT_MAX_HR)
            preferences.remove(PreferencesKeys.STATEMENT_RESTING_HR)
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

    suspend fun setTurnaroundCueEnabled(enabled: Boolean) =
        put(PreferencesKeys.TURNAROUND_CUE_ENABLED, enabled)

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
                preferences.clearCoachWork()
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

    /**
     * Attaching a plan drops the coach's prescription **and its debrief** along with it, in the
     * same write.
     *
     * Those numbers were reasoned about against the plan being left. Carried across, they would
     * overwrite day one of the plan just chosen — target zone included — which is the one workout
     * the runner picked the plan *for*. Same rule as [advanceStageAndClearPrescriptions], since
     * "the stage under it changed" is the same event either way.
     *
     * The debrief goes because it exists to explain the prescription, so it cannot outlive one:
     * left behind it narrates intervals the new plan is not running. That also makes the coach's
     * two writes safe to land separately — each is refused once the plan has moved
     * (`editCoachWrite`), and a debrief that got in just before the change is taken by this. So
     * there is no ordering between them to get right, which is the only reason they need not share
     * a single edit.
     *
     * Graduating is the exception and keeps its message: [advanceStageAndClearPrescriptions] is the
     * coach moving the runner on, and "you have finished this stage" is the one thing it had to say.
     */
    suspend fun setActivePlan(planId: String?, stageId: String?) {
        context.dataStore.edit { preferences ->
            preferences.clearCoachWork()
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
     * The debrief of the run just finished — displayed text, never a knob.
     *
     * [author] is required rather than defaulted because this slot has two writers and the screen
     * names one of them over what it finds (#296): a caller that does not say is a caller whose
     * words get somebody else's name. The app's own writes — a graduation (#290), a Test missed
     * (#292) — pass [DebriefAuthor.APP]; only text that came back from Gemini is the coach's.
     */
    suspend fun setLatestDebrief(message: String, author: DebriefAuthor, scope: CoachWriteScope) {
        context.dataStore.editCoachWrite(scope) { preferences ->
            preferences.writeStandingDebrief(message, author)
        }
    }

    /**
     * Moves the plan on, dropping every standing prescription with it: those numbers were written
     * for the stage just left, and the new stage's own workouts are where the next progression
     * starts. One write for the move and all three slots (#175), so no run can start against the new
     * stage carrying the old stage's intervals.
     *
     * Graduating is the coach moving the runner on, so it goes through [editCoachWrite] like its
     * other writes: [scope] is the stage it decided to graduate *from*, and a runner who changed
     * plans while it was thinking must not be advanced to a stage of the plan they left.
     */
    suspend fun advanceStageAndClearPrescriptions(nextStageId: String?, scope: CoachWriteScope) {
        context.dataStore.editCoachWrite(scope) { preferences ->
            if (nextStageId != null) {
                preferences[PreferencesKeys.ACTIVE_STAGE_ID] = nextStageId
            }
            preferences.clearCoachPrescriptions()
        }
    }

    /**
     * Records that the runner has finished a whole Plan, and tells them so — one write, once (#294).
     *
     * **One write** because the two halves are one event. A congratulation stored without the
     * completion is the bug #294 exists to fix, read out loud: the runner is told they have finished
     * and the screen goes on asking them for the time they have just run. A completion stored
     * without the congratulation is the same failure with the message missing.
     *
     * **Once**, and decided inside the edit where nothing can change between the check and the
     * store: a Plan already recorded as complete is left exactly as it stands, so a second
     * qualifying Run does not move the day, does not move the time, and does not congratulate the
     * runner again. This records the day the Plan was finished, not the runner's best — the record
     * book already owns that.
     *
     * Only a completion of *this* Plan stops it. A completion belonging to another Plan is
     * overwritten rather than obeyed: one slot holds the fact, and the fact is about a Plan.
     *
     * Goes through [editCoachWrite] like every other write of the graduation rule's, for the same
     * reason [advanceStageAndClearPrescriptions] does: a runner who changed plans while this was
     * being decided must not have the plan they left declared finished.
     */
    suspend fun completePlan(completion: PlanCompletion, message: String, scope: CoachWriteScope) {
        context.dataStore.editCoachWrite(scope) { preferences ->
            preferences.completePlanOnce(completion, message)
        }
    }

    /**
     * Remembers the folder the runner picked for archives (#85).
     *
     * The Uri alone is not the permission — that is taken separately and persistently, at the
     * moment the picker returns, by whoever owns the Activity result. Stored here it is only the
     * address; a grant that was never taken, or has since been revoked, shows up as the folder
     * failing to open rather than as a wrong address.
     *
     * Choosing a new folder deliberately leaves [PreferencesKeys.LAST_BACKUP_AT] alone: the last
     * backup did happen, and when it happened is the fact the runner is being told. Where it went
     * is a separate question, and one the new folder answers only from the next backup on.
     */
    suspend fun setBackupFolderUri(uri: String) = put(PreferencesKeys.BACKUP_FOLDER_URI, uri)

    /** Written only once a complete archive has been promoted into place. See [Archiver]. */
    suspend fun setLastBackupAt(atEpochMillis: Long) = put(PreferencesKeys.LAST_BACKUP_AT, atEpochMillis)

    /**
     * Marks the history already recorded as scored against the record book (#50).
     *
     * Written only after the book has been rebuilt and committed, so a seeding pass killed
     * part-way through is simply run again at the next launch.
     */
    suspend fun setHistoryRecordsSeeded() = put(PreferencesKeys.HISTORY_RECORDS_SEEDED, true)

    /**
     * Puts the Max HR confirmation card away for good (#65).
     *
     * Written on both ways out of the card — the number confirmed, and the card simply closed —
     * because the runner has been asked and has answered, and asking again is the nagging #65
     * exists to avoid. Only ever `true`: nothing puts the card back, so there is no setter to.
     *
     * Deliberately *not* a statement about anyone's heart. Confirming a number goes to
     * `SessionRepository.setStatedProfile` as every other statement does, and this is written
     * beside it; a card that recorded the answer here alone would leave the zones on the default
     * the runner had just been asked to confirm.
     */
    suspend fun setMaxHrCardDismissed() = put(PreferencesKeys.MAX_HR_CARD_DISMISSED, true)

    /**
     * Forgets that history has been scored, because the history it described is being replaced (#50).
     *
     * Every restore has to come through here, not only the archive one below: a bare `.db` backup
     * carries no settings at all, so nothing else on that path would clear the mark — and a backup
     * written before the record book existed would then sit permanently unscored, the seeding pass
     * standing down at every launch over a mark left by history that is gone.
     *
     * Called *before* the swap rather than after it, so there is no window to be killed in. The
     * cost of clearing it for a restore that then fails is one re-measure of unchanged history,
     * which produces the same book; the cost of the other order is a history that is never scored.
     */
    suspend fun clearHistoryRecordsSeeded() {
        context.dataStore.edit { it.remove(PreferencesKeys.HISTORY_RECORDS_SEEDED) }
    }

    /**
     * Puts back the settings an archive was written with, beside the history from the same archive
     * (#86). Written verbatim, in one edit, and deliberately **not** through [setStatedHeartRates].
     *
     * A restore is not a statement about the runner's heart. [setStatedHeartRates] exists to keep a
     * newly stated maximum and the history banded against it in step, and applies a future-only
     * rule to get there — correct for someone typing a new number, wrong here. What arrives here is
     * a *pair that was already in step*: the archive's `historyMaxHr` says what the archive's runs
     * were banded against, and those very runs are being restored from the same file in the same
     * act. Re-deriving either half would strand every restored run on a profile nobody chose, which
     * is the split #172 exists to prevent — reached from the other direction.
     *
     * Any statement left half-finished by the install being wiped is dropped rather than carried
     * over: it describes a re-band of history that no longer exists, and replaying it against
     * restored history would band runs against a maximum from a different phone's afternoon.
     *
     * The coach's work goes with it, for the same reason and by the same rule as everywhere else it
     * is dropped (#113): a prescription is read back by run type without asking which plan or stage
     * produced it, so one left over from the history being replaced would quietly modify the first
     * restored workout, and its debrief would go on explaining a run nobody has any more.
     */
    suspend fun restoreArchivedSettings(settings: ArchivedSettings) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.MAX_HR] = settings.maxHr
            preferences[PreferencesKeys.MAX_HR_EVER_SET] = settings.maxHrEverSet
            // Restored so the one-shot card stays asked-once across a lost phone: for a runner who
            // closed it without stating anything, this is the only record they were ever asked.
            preferences[PreferencesKeys.MAX_HR_CARD_DISMISSED] = settings.maxHrCardDismissed
            preferences[PreferencesKeys.HISTORY_MAX_HR] = settings.historyMaxHr
            preferences[PreferencesKeys.RESTING_HR] = settings.restingHr
            preferences[PreferencesKeys.TARGET_ZONE] = settings.targetZone
            preferences[PreferencesKeys.COACHING_ENABLED] = settings.coachingEnabled
            preferences[PreferencesKeys.SPLIT_ANNOUNCEMENTS_ENABLED] =
                settings.splitAnnouncementsEnabled
            preferences[PreferencesKeys.AUTO_PAUSE_ENABLED] = settings.autoPauseEnabled
            preferences[PreferencesKeys.AI_DATA_SHARING_ENABLED] = settings.aiDataSharingEnabled
            preferences[PreferencesKeys.RUN_MODE] = settings.runMode
            settings.activePlanId
                ?.let { preferences[PreferencesKeys.ACTIVE_PLAN_ID] = it }
                ?: preferences.remove(PreferencesKeys.ACTIVE_PLAN_ID)
            settings.activeStageId
                ?.let { preferences[PreferencesKeys.ACTIVE_STAGE_ID] = it }
                ?: preferences.remove(PreferencesKeys.ACTIVE_STAGE_ID)
            // Where the runner is in their training, which is what the two keys above are, includes
            // having reached the end of it (#294). An archive written before the field existed
            // carries none and reads back as no Plan finished, which is the truth about it — and it
            // is taken away rather than left standing, because everything else here is being
            // replaced by the archive's answer and a completion held over would be a claim about a
            // Plan this phone's history no longer holds.
            preferences.writePlanCompletion(settings.planCompletion)
            // The seeding mark describes history that has just been replaced, so it goes with it
            // (#50): the archive may have been written before the record book existed, and its runs
            // deserve the same one-off scoring any other unscored history gets at the next launch.
            preferences.remove(PreferencesKeys.HISTORY_RECORDS_SEEDED)
            preferences.remove(PreferencesKeys.STATEMENT_IN_FLIGHT)
            preferences.remove(PreferencesKeys.STATEMENT_MAX_HR)
            preferences.remove(PreferencesKeys.STATEMENT_RESTING_HR)
            preferences.clearCoachWork()
        }
    }

    suspend fun setSimulationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SIMULATION_ENABLED] = enabled
        }
    }
}
