package com.example.runningapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The #109 contract: one condition, two renderings. Each condition carries a screen action phrase
 * and a spoken sentence side by side, `voiceStyle` is gone, and no spoken cue names a zone.
 */
class CueSentencesTest {

    @Test
    fun `every condition renders both channels side by side`() {
        assertEquals(
            CoachingCue("ease off", "Heart rate drifting up. Keep effort steady, or take a short walk break."),
            coachingCue(CueCondition.ABOVE_DRIFTING)
        )
        assertEquals(
            CoachingCue("ease off", "Heart rate high. Walk until your breathing settles."),
            coachingCue(CueCondition.ABOVE_WALK_BREAK)
        )
        assertEquals(CoachingCue("ease off", "Ease off slightly."), coachingCue(CueCondition.ABOVE))
        assertEquals(CoachingCue("pick it up", "Gently increase pace."), coachingCue(CueCondition.BELOW))
        assertEquals(CoachingCue("on target", "Back on target."), coachingCue(CueCondition.RETURNED))
    }

    @Test
    fun `on target speaks nothing but still labels the screen`() {
        assertEquals("on target", coachingCue(CueCondition.ON_TARGET).screenAction)
        assertNull(coachingCue(CueCondition.ON_TARGET).spoken)
    }

    @Test
    fun `no spoken cue names a zone`() {
        val zoneNames = HrZone.entries.map { it.zoneName }
        for (condition in CueCondition.entries) {
            val spoken = coachingCue(condition).spoken ?: continue
            for (name in zoneNames) {
                assertTrue(
                    "Spoken cue for $condition must not name the zone \"$name\": \"$spoken\"",
                    !spoken.contains(name, ignoreCase = true)
                )
            }
        }
    }

    @Test
    fun `high cue picks drift over walk-break over plain ease-off`() {
        // Drifting: past 20 min, within baseline + 12. Wins even on a structured run.
        assertEquals(
            CueCondition.ABOVE_DRIFTING,
            highCueCondition(secondsRunning = 1300, baselineHr = 150, avgBpm = 160, isStructured = true)
        )
        // Structured, not drifting (no baseline yet): the walk-break cue.
        assertEquals(
            CueCondition.ABOVE_WALK_BREAK,
            highCueCondition(secondsRunning = 300, baselineHr = null, avgBpm = 175, isStructured = true)
        )
        // Unstructured, not drifting: the plain ease-off.
        assertEquals(
            CueCondition.ABOVE,
            highCueCondition(secondsRunning = 300, baselineHr = null, avgBpm = 175, isStructured = false)
        )
        // Past 20 min but well above baseline+12 is real overexertion, not drift.
        assertEquals(
            CueCondition.ABOVE,
            highCueCondition(secondsRunning = 1300, baselineHr = 150, avgBpm = 180, isStructured = false)
        )
    }

    @Test
    fun `live zone status names the zone you are in then the target-relative action`() {
        assertEquals("Tempo — ease off", liveZoneStatus("Tempo", ZoneBand.ABOVE))
        assertEquals("Moderate — on target", liveZoneStatus("Moderate", ZoneBand.IN))
        assertEquals("Endurance — pick it up", liveZoneStatus("Endurance", ZoneBand.BELOW))
    }

    @Test
    fun `live zone status is a plain dash with no signal`() {
        assertEquals("—", liveZoneStatus(null, ZoneBand.UNKNOWN))
        assertEquals("—", liveZoneStatus("Tempo", ZoneBand.UNKNOWN))
    }
}
