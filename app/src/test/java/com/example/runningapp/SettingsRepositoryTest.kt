package com.example.runningapp

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun `testing mode blocks turning AI sharing back on`() {
        // The failure this prevents is delayed, not immediate: recording already ignores sharing
        // while testing mode is on, so a stored `true` does nothing until testing mode goes off —
        // at which point sharing resumes off a tap made while it was suppressed.
        assertFalse(aiSharingChangeAllowed(enabled = true, testingModeEnabled = true))
    }

    @Test
    fun `withdrawing consent is allowed in every state`() {
        assertTrue(aiSharingChangeAllowed(enabled = false, testingModeEnabled = true))
        assertTrue(aiSharingChangeAllowed(enabled = false, testingModeEnabled = false))
    }

    @Test
    fun `with testing mode off the setting is the runner's to make`() {
        assertTrue(aiSharingChangeAllowed(enabled = true, testingModeEnabled = false))
    }

    @Test
    fun `the coach may write when nothing moved while it was thinking`() {
        assertTrue(
            coachWriteAllowed(
                testingModeEnabled = false,
                activePlanId = "5k_sub_25",
                activeStageId = "base_builder",
                scope = CoachWriteScope("5k_sub_25", "base_builder")
            )
        )
    }

    @Test
    fun `an absent testing-mode key is off, not unknown`() {
        assertTrue(
            coachWriteAllowed(
                testingModeEnabled = null,
                activePlanId = null,
                activeStageId = null,
                scope = CoachWriteScope(null, null)
            )
        )
    }

    @Test
    fun `testing mode switched on mid-evaluation refuses the write`() {
        assertFalse(
            coachWriteAllowed(
                testingModeEnabled = true,
                activePlanId = "5k_sub_25",
                activeStageId = "base_builder",
                scope = CoachWriteScope("5k_sub_25", "base_builder")
            )
        )
    }

    @Test
    fun `a plan chosen mid-evaluation refuses the write`() {
        // The reply is intervals reasoned about against the plan just left; landing it here would
        // overwrite day one of the plan the runner picked while the coach was thinking.
        assertFalse(
            coachWriteAllowed(
                testingModeEnabled = false,
                activePlanId = "10k_sub_55",
                activeStageId = "base_builder",
                scope = CoachWriteScope("5k_sub_25", "base_builder")
            )
        )
    }

    @Test
    fun `a stage advanced mid-evaluation refuses the write`() {
        assertFalse(
            coachWriteAllowed(
                testingModeEnabled = false,
                activePlanId = "5k_sub_25",
                activeStageId = "sub_30_bridge",
                scope = CoachWriteScope("5k_sub_25", "base_builder")
            )
        )
    }

    @Test
    fun `detaching the plan entirely refuses the write`() {
        assertFalse(
            coachWriteAllowed(
                testingModeEnabled = false,
                activePlanId = null,
                activeStageId = null,
                scope = CoachWriteScope("5k_sub_25", "base_builder")
            )
        )
    }

    @Test
    fun `dropping the coach's work takes the debrief with the prescription`() {
        // The debrief explains the prescription. Clearing the numbers and keeping the text leaves
        // the runner reading about a workout that is not the one queued.
        val preferences = mutablePreferencesOf(
            PreferencesKeys.LATEST_COACH_MESSAGE to "Shortened after Tuesday.",
            PreferencesKeys.ACTIVE_PLAN_ID to "5k_sub_25",
            CoachPrescriptionKeys.TARGET_ZONE to 2,
            CoachPrescriptionKeys.RUN_SECONDS to 30,
            CoachPrescriptionKeys.WALK_SECONDS to 60,
            CoachPrescriptionKeys.REPEATS to 5,
            CoachPrescriptionKeys.PRESCRIBED_AT to 1_784_739_209_365L
        )

        preferences.clearCoachWork()

        assertNull(preferences[PreferencesKeys.LATEST_COACH_MESSAGE])
        assertNull(preferences[CoachPrescriptionKeys.TARGET_ZONE])
        assertNull(preferences[CoachPrescriptionKeys.RUN_SECONDS])
        assertNull(preferences[CoachPrescriptionKeys.WALK_SECONDS])
        assertNull(preferences[CoachPrescriptionKeys.REPEATS])
        assertNull(preferences[CoachPrescriptionKeys.PRESCRIBED_AT])
        // Untouched: this says which plan is attached, not what the coach said about it.
        assertEquals("5k_sub_25", preferences[PreferencesKeys.ACTIVE_PLAN_ID])
    }

    @Test
    fun `a Max HR chosen before the flag existed still counts as deliberately set`() {
        // Upgrading from a build with a Save button: they typed their number, the flag didn't
        // exist to record it. Reading that as "never set" would let their next edit rewrite
        // history already recorded against the number they chose.
        assertTrue(maxHrEverSet(flag = null, storedMaxHr = 180))
    }

    @Test
    fun `the stored placeholder is not evidence of anything`() {
        // The old Save wrote Max HR on every save, touched or not, so the key's presence means
        // nothing — only a value differing from the placeholder does.
        assertFalse(maxHrEverSet(flag = null, storedMaxHr = DEFAULT_MAX_HR))
        assertFalse(maxHrEverSet(flag = null, storedMaxHr = null))
    }

    @Test
    fun `the recorded flag outranks the inference in both directions`() {
        assertTrue(maxHrEverSet(flag = true, storedMaxHr = DEFAULT_MAX_HR))
        assertFalse(maxHrEverSet(flag = false, storedMaxHr = 180))
    }

    @Test
    fun `a lowered max hr brings the stored resting hr back inside what it can hold`() {
        // Storage must hold the number the zones actually use. A resting 90 left standing under a
        // Max HR of 100 would show 90 on the settings screen while every edge was sliced from 50.
        assertEquals(50, effectiveRestingHr(90, 100))
        assertEquals(90, effectiveRestingHr(90, 190))
        // Unstated survives the reconciliation rather than being clamped up into the range.
        assertEquals(RESTING_HR_UNSTATED, effectiveRestingHr(RESTING_HR_UNSTATED, 100))
    }
}
