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
            PreferencesKeys.ACTIVE_PLAN_ID to "5k_sub_25"
        )
        // Every Run Type's slot, since the debrief is written about whichever one the coach adapted
        // and all three go together (#175).
        RunType.entries.forEach { runType ->
            preferences.writeCoachPrescription(
                runType,
                CoachPrescription(
                    targetZone = 2,
                    runDurationSeconds = 30,
                    walkDurationSeconds = 60,
                    totalRepeats = 5,
                    prescribedAtEpochMillis = 1_784_739_209_365L
                )
            )
        }

        preferences.clearCoachWork()

        assertNull(preferences[PreferencesKeys.LATEST_COACH_MESSAGE])
        assertEquals(CoachPrescriptions.NONE, preferences.coachPrescriptions())
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

    // --- What a statement of the pair actually stores (#172) ---

    @Test
    fun `stating one number leaves the other exactly as it was`() {
        assertEquals(
            StoredHeartRates(maxHr = 181, restingHr = 60),
            storedHeartRates(statedMaxHr = 181, statedRestingHr = null, storedMaxHr = 190, storedRestingHr = 60)
        )
        assertEquals(
            StoredHeartRates(maxHr = 190, restingHr = 55),
            storedHeartRates(statedMaxHr = null, statedRestingHr = 55, storedMaxHr = 190, storedRestingHr = 60)
        )
    }

    @Test
    fun `nothing stored and nothing stated leaves the resting key alone`() {
        // Null out means "no resting heart rate is stored and none is being stated", so nothing is
        // written under that key — not the same as a stored, deliberate RESTING_HR_UNSTATED.
        assertEquals(
            StoredHeartRates(maxHr = 181, restingHr = null),
            storedHeartRates(statedMaxHr = 181, statedRestingHr = null, storedMaxHr = null, storedRestingHr = null)
        )
    }

    @Test
    fun `stating only a lower maximum still brings a stranded resting hr back into range`() {
        // The backstop: storage must never hold a pair with no reserve between them, whatever the
        // screen did or did not refuse.
        assertEquals(
            StoredHeartRates(maxHr = 100, restingHr = 50),
            storedHeartRates(statedMaxHr = 100, statedRestingHr = null, storedMaxHr = 190, storedRestingHr = 90)
        )
    }

    @Test
    fun `a resting hr stated with a maximum is judged against that maximum, not the old one`() {
        assertEquals(
            StoredHeartRates(maxHr = 100, restingHr = 50),
            storedHeartRates(statedMaxHr = 100, statedRestingHr = 90, storedMaxHr = 190, storedRestingHr = null)
        )
    }

    @Test
    fun `an unstated resting hr survives the pair being stored`() {
        assertEquals(
            StoredHeartRates(maxHr = 190, restingHr = RESTING_HR_UNSTATED),
            storedHeartRates(
                statedMaxHr = null,
                statedRestingHr = RESTING_HR_UNSTATED,
                storedMaxHr = 190,
                storedRestingHr = 60
            )
        )
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
