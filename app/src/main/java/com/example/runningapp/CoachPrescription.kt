package com.example.runningapp

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The work the coach has standing, one slot per Run Type (#175).
 *
 * One global prescription worked while only one Workout was ever queued. With three Runs to choose
 * between it becomes a hazard: intervals reasoned about for a Long Run would land on a session built
 * from twenty-second strides. So which session a prescription is about is *storage*, not a check —
 * [get] is the only way to reach one, and it can only be asked by Run Type.
 *
 * Empty is a whole answer, not a missing one: [NONE] means the plan runs as written.
 */
data class CoachPrescriptions(val byRunType: Map<RunType, CoachPrescription>) {

    /** What the coach wrote for [runType], or null when it has written nothing for that kind. */
    operator fun get(runType: RunType): CoachPrescription? = byRunType[runType]

    companion object {
        val NONE = CoachPrescriptions(emptyMap())
    }
}

/**
 * Today's work, as written by the AI coach (#113).
 *
 * Exactly [WorkoutTemplate]'s four prescribable fields and nothing else — the coach prescribes a
 * workout, it does not configure the app. It cannot reach the cue switches, Max HR, or
 * `coachingEnabled`, because none of them are here to reach.
 *
 * It carries [targetZone] because a coach that can lengthen your intervals but cannot say "today is
 * easier, drop to Z2" is crippled at its one job; the plan already varies target zone.
 *
 * Deliberately *not* a `UserSettings` field. The three globals this replaces
 * (`aiRunIntervalSeconds`/`aiWalkIntervalSeconds`/`aiRepeats`) were per-run prescriptions stored as
 * permanent settings, which is why testing mode had to special-case around them at read time. A
 * prescription with a date on it needs no such special case: it is superseded by the coach's next
 * one, dropped when the stage advances, erased when testing mode comes on, and — see
 * [isFreshAt] — expires on its own if neither happens.
 */
data class CoachPrescription(
    val targetZone: Int,
    val runDurationSeconds: Int,
    val walkDurationSeconds: Int,
    val totalRepeats: Int,
    val prescribedAtEpochMillis: Long
)

/**
 * How long a prescription stands before it stops being about you (#113).
 *
 * The coach writes after a run and the next run is days away, so a prescription has to survive the
 * gap between runs — expiring at midnight would mean it almost never applied. But a plan's own
 * numbers never change within a stage, so a prescription left standing would quietly become the
 * plan: two weeks off, and the numbers waiting for you were computed from a body that has since
 * detrained. Past this, the stage's own workout is the honest starting point.
 */
const val COACH_PRESCRIPTION_MAX_AGE_DAYS = 14

private const val MAX_AGE_MILLIS = COACH_PRESCRIPTION_MAX_AGE_DAYS * 24L * 60L * 60L * 1000L

/**
 * Whether this prescription still applies at [nowEpochMillis].
 *
 * A stamp in the future counts as fresh: it means the clock moved backwards (a timezone fix, a
 * manual set), and throwing away a real prescription over that would be the app inventing a reason
 * to ignore the coach.
 */
fun CoachPrescription.isFreshAt(nowEpochMillis: Long): Boolean =
    nowEpochMillis - prescribedAtEpochMillis <= MAX_AGE_MILLIS

/**
 * One Run Type's five keys, spelled from the type itself so a slot cannot be added without its
 * storage (#175).
 *
 * `internal` rather than private so a test can assert on the stored keys without a second copy of
 * the key strings — same reason [PreferencesKeys] is. One spelling of a key, one meaning.
 */
internal class CoachPrescriptionKeys private constructor(runType: RunType) {
    private val suffix = runType.name.lowercase()
    val targetZone = intPreferencesKey("coach_target_zone_$suffix")
    val runSeconds = intPreferencesKey("coach_run_seconds_$suffix")
    val walkSeconds = intPreferencesKey("coach_walk_seconds_$suffix")
    val repeats = intPreferencesKey("coach_repeats_$suffix")
    val prescribedAt = longPreferencesKey("coach_prescribed_at_$suffix")

    companion object {
        private val slots = RunType.entries.associateWith { CoachPrescriptionKeys(it) }

        fun of(runType: RunType): CoachPrescriptionKeys = slots.getValue(runType)
    }
}

/**
 * The unsuffixed keys the single global prescription used before the split (#175).
 *
 * Read by nothing: a global prescription cannot say which kind of session it was about, and guessing
 * is the mistake the slots exist to make impossible. Named here only so the next clear takes them
 * away instead of leaving them in storage for good.
 */
private val LEGACY_GLOBAL_KEYS = listOf(
    intPreferencesKey("coach_target_zone"),
    intPreferencesKey("coach_run_seconds"),
    intPreferencesKey("coach_walk_seconds"),
    intPreferencesKey("coach_repeats"),
    longPreferencesKey("coach_prescribed_at")
)

/**
 * Everything standing, read out of one snapshot of the preferences.
 *
 * A slot missing any of its five keys stands for nothing: all four numbers were reasoned about
 * together, so half a prescription is not a lighter one. Freshness is *not* applied here — whoever
 * runs a workout decides against its own clock, so the expiry cannot drift between the card and the
 * run.
 */
internal fun Preferences.coachPrescriptions(): CoachPrescriptions = CoachPrescriptions(
    RunType.entries.mapNotNull { runType ->
        coachPrescription(runType)?.let { runType to it }
    }.toMap()
)

private fun Preferences.coachPrescription(runType: RunType): CoachPrescription? {
    val keys = CoachPrescriptionKeys.of(runType)
    return CoachPrescription(
        targetZone = this[keys.targetZone] ?: return null,
        runDurationSeconds = this[keys.runSeconds] ?: return null,
        walkDurationSeconds = this[keys.walkSeconds] ?: return null,
        totalRepeats = this[keys.repeats] ?: return null,
        prescribedAtEpochMillis = this[keys.prescribedAt] ?: return null
    )
}

/** Stores [prescription] in [runType]'s slot, leaving the other two exactly as they were. */
internal fun MutablePreferences.writeCoachPrescription(
    runType: RunType,
    prescription: CoachPrescription
) {
    val keys = CoachPrescriptionKeys.of(runType)
    this[keys.targetZone] = prescription.targetZone
    this[keys.runSeconds] = prescription.runDurationSeconds
    this[keys.walkSeconds] = prescription.walkDurationSeconds
    this[keys.repeats] = prescription.totalRepeats
    this[keys.prescribedAt] = prescription.prescribedAtEpochMillis
}

/**
 * Drops every standing prescription, in whatever edit the caller is already making.
 *
 * An extension on the preferences rather than a repository call so the settings changes that
 * invalidate a prescription — testing mode coming on, the stage advancing — can drop it in the same
 * atomic write. Two writes could interleave with a run starting between them, which is the one
 * moment the guarantee matters. All three slots go here for the same reason: three writes would
 * leave a window where a run could start on a stage it had half-left.
 */
internal fun MutablePreferences.clearCoachPrescriptions() {
    RunType.entries.forEach { runType ->
        val keys = CoachPrescriptionKeys.of(runType)
        remove(keys.targetZone)
        remove(keys.runSeconds)
        remove(keys.walkSeconds)
        remove(keys.repeats)
        remove(keys.prescribedAt)
    }
    LEGACY_GLOBAL_KEYS.forEach { remove(it) }
}

class CoachPrescriptionRepository(private val context: Context) {

    /** Every Run Type's standing prescription; [CoachPrescriptions.NONE] when the coach is silent. */
    val prescriptionsFlow: Flow<CoachPrescriptions> =
        context.dataStore.data.map { it.coachPrescriptions() }

    /**
     * Records what the coach wants run next for [runType], replacing anything it wrote for that kind
     * before and touching no other kind.
     *
     * [scope] is the plan and stage the prescription was reasoned about against; the write is
     * refused if they are no longer the active ones. See `editCoachWrite`.
     */
    suspend fun prescribe(
        runType: RunType,
        prescription: CoachPrescription,
        scope: CoachWriteScope
    ) {
        context.dataStore.editCoachWrite(scope) { preferences ->
            preferences.writeCoachPrescription(runType, prescription)
        }
    }
}
