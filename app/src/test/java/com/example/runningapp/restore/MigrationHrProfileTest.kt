package com.example.runningapp.restore

import com.example.runningapp.HrProfile
import com.example.runningapp.UserSettings
import com.example.runningapp.archive.ArchivedSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which heart rates a picked backup's finished runs are re-banded on (#267).
 *
 * The whole point of the pair is that they part company: a runner who stated 181 and later
 * corrected to 195 has history banded on 181 and live zones on 195 (#112, #172). A restore that
 * reached for the live number would move every restored run onto a Reserve it was never read
 * under — decided, absurdly, by whatever is in the Settings field on the day the file is picked.
 */
class MigrationHrProfileTest {

    private val archived = ArchivedSettings(
        maxHr = 200,
        maxHrEverSet = true,
        historyMaxHr = 160,
        restingHr = 40,
        targetZone = 2,
        coachingEnabled = true,
        splitAnnouncementsEnabled = true,
        autoPauseEnabled = true,
        aiDataSharingEnabled = false,
        runMode = "outdoor",
        activePlanId = null,
        activeStageId = null,
    )

    private val phone = UserSettings(
        maxHr = 195,
        maxHrEverSet = true,
        historyMaxHr = 181,
        restingHr = 50,
    )

    @Test
    fun `an archive's runs are banded on what that archive's history was recorded under`() {
        assertEquals(HrProfile(160, 40), migrationHrProfile(archived, phone))
    }

    @Test
    fun `a bare database brings no settings, so this phone's history profile stands in`() {
        assertEquals(HrProfile(181, 50), migrationHrProfile(null, phone))
    }

    @Test
    fun `correcting the live maximum does not move a restore`() {
        // The failure this exists to catch (#267): the same file, picked either side of a Max HR
        // correction, restoring two different histories.
        assertEquals(
            migrationHrProfile(null, phone),
            migrationHrProfile(null, phone.copy(maxHr = 210)),
        )
    }
}
