package com.example.runningapp

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
 * `internal` rather than private so a test can assert on the stored keys without a second copy of
 * the key strings — same reason [PreferencesKeys] is. One spelling of a key, one meaning.
 */
internal object CoachPrescriptionKeys {
    val TARGET_ZONE = intPreferencesKey("coach_target_zone")
    val RUN_SECONDS = intPreferencesKey("coach_run_seconds")
    val WALK_SECONDS = intPreferencesKey("coach_walk_seconds")
    val REPEATS = intPreferencesKey("coach_repeats")
    val PRESCRIBED_AT = longPreferencesKey("coach_prescribed_at")
}

/**
 * Drops the standing prescription, in whatever edit the caller is already making.
 *
 * An extension on the preferences rather than a repository call so the settings changes that
 * invalidate a prescription — testing mode coming on, the stage advancing — can drop it in the same
 * atomic write. Two writes could interleave with a run starting between them, which is the one
 * moment the guarantee matters.
 */
internal fun MutablePreferences.clearCoachPrescription() {
    remove(CoachPrescriptionKeys.TARGET_ZONE)
    remove(CoachPrescriptionKeys.RUN_SECONDS)
    remove(CoachPrescriptionKeys.WALK_SECONDS)
    remove(CoachPrescriptionKeys.REPEATS)
    remove(CoachPrescriptionKeys.PRESCRIBED_AT)
}

class CoachPrescriptionRepository(private val context: Context) {

    /**
     * The standing prescription, or null when the coach has not written one. Freshness is *not*
     * applied here: whoever runs a workout decides against its own clock, so the expiry cannot
     * drift between the card and the run.
     */
    val prescriptionFlow: Flow<CoachPrescription?> = context.dataStore.data.map { preferences ->
        val prescribedAt = preferences[CoachPrescriptionKeys.PRESCRIBED_AT] ?: return@map null
        CoachPrescription(
            targetZone = preferences[CoachPrescriptionKeys.TARGET_ZONE] ?: return@map null,
            runDurationSeconds = preferences[CoachPrescriptionKeys.RUN_SECONDS] ?: return@map null,
            walkDurationSeconds = preferences[CoachPrescriptionKeys.WALK_SECONDS] ?: return@map null,
            totalRepeats = preferences[CoachPrescriptionKeys.REPEATS] ?: return@map null,
            prescribedAtEpochMillis = prescribedAt
        )
    }

    /**
     * Records what the coach wants run next, replacing anything it wrote before.
     *
     * [scope] is the plan and stage the prescription was reasoned about against; the write is
     * refused if they are no longer the active ones. See `editCoachWrite`.
     */
    suspend fun prescribe(prescription: CoachPrescription, scope: CoachWriteScope) {
        context.dataStore.editCoachWrite(scope) { preferences ->
            preferences[CoachPrescriptionKeys.TARGET_ZONE] = prescription.targetZone
            preferences[CoachPrescriptionKeys.RUN_SECONDS] = prescription.runDurationSeconds
            preferences[CoachPrescriptionKeys.WALK_SECONDS] = prescription.walkDurationSeconds
            preferences[CoachPrescriptionKeys.REPEATS] = prescription.totalRepeats
            preferences[CoachPrescriptionKeys.PRESCRIBED_AT] = prescription.prescribedAtEpochMillis
        }
    }
}
