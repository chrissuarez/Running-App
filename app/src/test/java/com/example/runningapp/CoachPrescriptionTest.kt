package com.example.runningapp

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Three independent slots, one per Run Type (#175): the coach's Long Run numbers can never land on a
 * stride session, because they are not stored anywhere the stride session reads.
 *
 * Storage is exercised through the pure preferences seam rather than a DataStore, for the reason
 * [coachWriteAllowed] is: the rule is then readable and testable where it is decided.
 */
class CoachPrescriptionTest {

    private val now = 1_700_000_000_000L

    private fun prescription(run: Int = 360, walk: Int = 90, repeats: Int = 5) =
        CoachPrescription(
            targetZone = 2,
            runDurationSeconds = run,
            walkDurationSeconds = walk,
            totalRepeats = repeats,
            prescribedAtEpochMillis = now
        )

    @Test
    fun `each run type keeps its own prescription`() {
        val long = prescription(run = 660)
        val quality = prescription(run = 25, walk = 90, repeats = 8)
        val preferences = mutablePreferencesOf()

        preferences.writeCoachPrescription(RunType.LONG, long)
        preferences.writeCoachPrescription(RunType.QUALITY, quality)

        val standing = preferences.coachPrescriptions()
        assertEquals(long, standing[RunType.LONG])
        assertEquals(quality, standing[RunType.QUALITY])
        // Never written, so nothing stands for it — an Easy Run is the plan's until the coach says
        // otherwise about the Easy Run itself.
        assertNull(standing[RunType.EASY])
    }

    @Test
    fun `prescribing for one run type replaces only that slot`() {
        val preferences = mutablePreferencesOf()
        preferences.writeCoachPrescription(RunType.LONG, prescription(run = 660))
        preferences.writeCoachPrescription(RunType.EASY, prescription(run = 1_260, walk = 0, repeats = 1))

        preferences.writeCoachPrescription(RunType.LONG, prescription(run = 720))

        val standing = preferences.coachPrescriptions()
        assertEquals(720, standing[RunType.LONG]?.runDurationSeconds)
        assertEquals(1_260, standing[RunType.EASY]?.runDurationSeconds)
    }

    @Test
    fun `a slot missing any one of its five keys stands for nothing`() {
        // Half a prescription is not a lighter prescription: all four numbers were reasoned about
        // together. The other slots are unaffected by the one that is unreadable.
        val preferences = mutablePreferencesOf()
        preferences.writeCoachPrescription(RunType.LONG, prescription(run = 660))
        preferences.writeCoachPrescription(RunType.EASY, prescription(run = 1_260, walk = 0, repeats = 1))
        preferences.remove(CoachPrescriptionKeys.of(RunType.LONG).repeats)

        val standing = preferences.coachPrescriptions()
        assertNull(standing[RunType.LONG])
        assertEquals(1_260, standing[RunType.EASY]?.runDurationSeconds)
    }

    @Test
    fun `nothing stored is nothing standing, for every run type`() {
        assertEquals(CoachPrescriptions.NONE, mutablePreferencesOf().coachPrescriptions())
        RunType.entries.forEach { assertNull(CoachPrescriptions.NONE[it]) }
    }

    @Test
    fun `clearing drops all three slots in the one edit`() {
        // The guarantee this preserves: graduating a stage and testing mode coming on each clear
        // the coach's work in a single write, so no run can start between two writes and pick up
        // half a change. Three slots must therefore go in one call, not three.
        val preferences = mutablePreferencesOf(PreferencesKeys.ACTIVE_PLAN_ID to "5k_sub_25")
        RunType.entries.forEach { preferences.writeCoachPrescription(it, prescription()) }

        preferences.clearCoachPrescriptions()

        assertEquals(CoachPrescriptions.NONE, preferences.coachPrescriptions())
        // Untouched: this says which plan is attached, not what the coach said about it.
        assertEquals("5k_sub_25", preferences[PreferencesKeys.ACTIVE_PLAN_ID])
    }

    @Test
    fun `a prescription written before the split stands for nothing and is cleared away`() {
        // One global prescription was written against whichever Workout was queued; under Run Types
        // there is no way to say which session it was about, and a prescription applied to the wrong
        // kind of session is the whole bug this ticket closes. So it applies nothing, and the keys
        // go with the next clear rather than sitting in storage for good.
        val globalKeys = listOf(
            intPreferencesKey("coach_target_zone"),
            intPreferencesKey("coach_run_seconds"),
            intPreferencesKey("coach_walk_seconds"),
            intPreferencesKey("coach_repeats")
        )
        val prescribedAt = longPreferencesKey("coach_prescribed_at")
        val preferences = mutablePreferencesOf(*globalKeys.map { it to 60 }.toTypedArray())
        preferences[prescribedAt] = now

        assertEquals(CoachPrescriptions.NONE, preferences.coachPrescriptions())

        preferences.clearCoachPrescriptions()

        globalKeys.forEach { assertNull(preferences[it]) }
        assertNull(preferences[prescribedAt])
    }

    @Test
    fun `the stored keys name the run type they belong to`() {
        // Asserted because these strings are the storage contract: renaming one silently orphans a
        // standing prescription rather than failing anywhere.
        assertEquals("coach_run_seconds_long", CoachPrescriptionKeys.of(RunType.LONG).runSeconds.name)
        assertEquals("coach_run_seconds_easy", CoachPrescriptionKeys.of(RunType.EASY).runSeconds.name)
        assertEquals("coach_run_seconds_quality", CoachPrescriptionKeys.of(RunType.QUALITY).runSeconds.name)
    }
}
