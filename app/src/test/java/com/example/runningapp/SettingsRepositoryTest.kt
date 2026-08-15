package com.example.runningapp

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.runningapp.archive.ArchiveJson
import com.example.runningapp.training.PlanCompletion
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
    fun `a plan whose Stage was never named reads as its first`() {
        // Storage is allowed to hold no Stage; the runner is not allowed to be in none while a plan
        // is attached. Resolving here is what stops each reader answering that differently (#234).
        val settings = userSettingsOf(
            mutablePreferencesOf(PreferencesKeys.ACTIVE_PLAN_ID to "5k_sub_25")
        )

        assertEquals("base_builder", settings.activeStageId)
    }

    @Test
    fun `a Stage the plan does not hold reads as its first`() {
        val settings = userSettingsOf(
            mutablePreferencesOf(
                PreferencesKeys.ACTIVE_PLAN_ID to "5k_sub_25",
                PreferencesKeys.ACTIVE_STAGE_ID to "no_such_stage"
            )
        )

        assertEquals("base_builder", settings.activeStageId)
    }

    @Test
    fun `with no plan attached there is no Stage, whatever storage holds`() {
        // A Run started here records no Stage and answers no Stage's requirement.
        val settings = userSettingsOf(
            mutablePreferencesOf(PreferencesKeys.ACTIVE_STAGE_ID to "base_builder")
        )

        assertNull(settings.activeStageId)
    }

    @Test
    fun `a finished plan reads back as the plan, the day and the time`() {
        val settings = userSettingsOf(
            mutablePreferencesOf(
                PreferencesKeys.PLAN_COMPLETE_PLAN_ID to "5k_sub_25",
                PreferencesKeys.PLAN_COMPLETE_DAY to 20_679L,
                PreferencesKeys.PLAN_COMPLETE_SECONDS to 1_492
            )
        )

        assertEquals(PlanCompletion("5k_sub_25", 20_679L, 1_492), settings.planCompletion)
    }

    @Test
    fun `part of a completion is not a completion`() {
        // The three keys are only ever written together, so a partial trio cannot come from this
        // app — and inventing the missing half of a fact that happens once and cannot be taken back
        // is not a reading worth having (#294).
        assertNull(
            userSettingsOf(
                mutablePreferencesOf(
                    PreferencesKeys.PLAN_COMPLETE_PLAN_ID to "5k_sub_25",
                    PreferencesKeys.PLAN_COMPLETE_SECONDS to 1_492
                )
            ).planCompletion
        )
    }

    @Test
    fun `a completion naming no plan is not stored`() {
        // It cannot be built in Kotlin, but it can arrive: an archive is JSON read by Gson, which
        // fills a field a truncated document never mentioned with null whatever the type says.
        // Stored as it stands it would go under a key with nothing to put there (#294).
        val halfWritten = ArchiveJson.read(
            """
            {
              "formatVersion": 1,
              "createdAtEpochMillis": 1,
              "databaseVersion": 19,
              "settings": { "maxHr": 181, "planCompletion": { "seconds": 1492 } },
              "runs": [],
              "intervalStats": []
            }
            """.trimIndent()
        )!!.settings.planCompletion
        val preferences = mutablePreferencesOf()

        preferences.writePlanCompletion(halfWritten)

        assertNull(userSettingsOf(preferences).planCompletion)
    }

    @Test
    fun `a completion naming no day and no time is not stored`() {
        // The other half of the same hazard, and the quieter one: Gson fills an absent Long or Int
        // with 0 rather than null, so a truncated document that does name the Plan arrives looking
        // complete. Stored, the card would call the runner's finest afternoon 1 January 1970 and
        // their time 0:00 (#294).
        val dayless = ArchiveJson.read(
            """
            {
              "formatVersion": 1,
              "createdAtEpochMillis": 1,
              "databaseVersion": 19,
              "settings": { "maxHr": 181, "planCompletion": { "planId": "5k_sub_25" } },
              "runs": [],
              "intervalStats": []
            }
            """.trimIndent()
        )!!.settings.planCompletion
        val preferences = mutablePreferencesOf()

        preferences.writePlanCompletion(dayless)

        assertNull(userSettingsOf(preferences).planCompletion)
    }

    @Test
    fun `a completion on a day no calendar can read is not stored`() {
        // A day far enough out of range does not read wrong, it throws: the card formats it with
        // LocalDate.ofEpochDay. Refused at the write, so a hand-edited archive costs a fact rather
        // than the screen that would show it (#294).
        val preferences = mutablePreferencesOf()

        preferences.writePlanCompletion(
            PlanCompletion(
                planId = "5k_sub_25",
                completedOnEpochDay = Long.MAX_VALUE,
                seconds = 1_492
            )
        )

        assertNull(userSettingsOf(preferences).planCompletion)
    }

    @Test
    fun `a runner who has finished nothing has finished nothing`() {
        assertNull(userSettingsOf(mutablePreferencesOf()).planCompletion)
    }

    @Test
    fun `the coach may write on a plan whose Stage was never named`() {
        // The scope carries the Stage the evaluation was about, resolved; storage still holds
        // nothing. Compared as they stand, the runner would look like they had left a Stage they
        // never left, and every coach write would be refused in exactly the state it is fine in.
        assertTrue(
            coachWriteAllowed(
                testingModeEnabled = false,
                activePlanId = "5k_sub_25",
                activeStageId = null,
                scope = CoachWriteScope("5k_sub_25", "base_builder")
            )
        )
        assertTrue(
            coachWriteAllowed(
                testingModeEnabled = false,
                activePlanId = "5k_sub_25",
                activeStageId = "no_such_stage",
                scope = CoachWriteScope("5k_sub_25", "base_builder")
            )
        )
    }

    @Test
    fun `dropping the coach's work takes the debrief with the prescription`() {
        // The debrief explains the prescription. Clearing the numbers and keeping the text leaves
        // the runner reading about a workout that is not the one queued.
        val preferences = mutablePreferencesOf(PreferencesKeys.ACTIVE_PLAN_ID to "5k_sub_25")
        preferences.writeStandingDebrief("Shortened after Tuesday.", DebriefAuthor.COACH)
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
        // And the name that was on it (#296): left behind, it heads whatever lands in the slot next.
        assertNull(preferences[stringPreferencesKey("coach_debrief_author")])
        assertEquals(CoachPrescriptions.NONE, preferences.coachPrescriptions())
        // Untouched: this says which plan is attached, not what the coach said about it.
        assertEquals("5k_sub_25", preferences[PreferencesKeys.ACTIVE_PLAN_ID])
    }

    @Test
    fun `the congratulation for a finished Plan is the app's, not the coach's`() {
        // Written from the Plan's own numbers, offline and with no Gemini key (#294) — so the card
        // must not head it with the coach's name (#296).
        val preferences = mutablePreferencesOf()

        preferences.completePlanOnce(
            PlanCompletion(planId = "5k_sub_25", completedOnEpochDay = 20_000L, seconds = 1_632),
            "You ran 5 km in 27:12. That is the whole of 5K Sub-25."
        )

        assertEquals(
            "You ran 5 km in 27:12. That is the whole of 5K Sub-25.",
            preferences[PreferencesKeys.LATEST_COACH_MESSAGE]
        )
        assertEquals(DebriefAuthor.APP, debriefAuthorOf(preferences))
    }

    @Test
    fun `a Plan already finished is not congratulated again`() {
        val preferences = mutablePreferencesOf()
        val completion = PlanCompletion(planId = "5k_sub_25", completedOnEpochDay = 20_000L, seconds = 1_632)
        preferences.completePlanOnce(completion, "That is the whole of 5K Sub-25.")
        // The coach's ordinary debrief, written about a Run after the Plan was finished.
        preferences.writeStandingDebrief("Steady all the way through.", DebriefAuthor.COACH)

        preferences.completePlanOnce(completion.copy(seconds = 1_500), "That is the whole of 5K Sub-25.")

        // Neither the day and time it records nor the words on screen are moved by a second
        // qualifying Run — including the name over them.
        assertEquals(1_632, preferences[PreferencesKeys.PLAN_COMPLETE_SECONDS])
        assertEquals("Steady all the way through.", preferences[PreferencesKeys.LATEST_COACH_MESSAGE])
        assertEquals(DebriefAuthor.COACH, debriefAuthorOf(preferences))
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
    fun `putting the confirmation card away says nothing about anyone's heart`() {
        // Two events, two flags (#65, #103). Closing the card is not a statement, so it must not
        // be readable as one — reading it as one would leave the runner's zones on the placeholder
        // with the one-shot recompute spent and nothing to show for it.
        val preferences = mutablePreferencesOf(PreferencesKeys.MAX_HR_CARD_DISMISSED to true)

        val settings = userSettingsOf(preferences)

        assertTrue(settings.maxHrCardDismissed)
        assertFalse(settings.maxHrEverSet)
        assertEquals(DEFAULT_MAX_HR, settings.maxHr)
    }

    @Test
    fun `a runner who has never seen the card has not dismissed it`() {
        assertFalse(userSettingsOf(mutablePreferencesOf()).maxHrCardDismissed)
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
