package com.example.runningapp.floating

import com.example.runningapp.HrProfile
import com.example.runningapp.HrState
import com.example.runningapp.HrZone
import com.example.runningapp.SessionPhase
import com.example.runningapp.StructuredWorkoutPhase
import com.example.runningapp.UserSettings
import com.example.runningapp.ZoneBand
import com.example.runningapp.run.RunLifecycle
import com.example.runningapp.ui.workout.NextStepUi
import com.example.runningapp.ui.workout.mapWorkoutPlayerUiState
import com.example.runningapp.zoneBandOf
import com.example.runningapp.zoneLowerBpm
import com.example.runningapp.zoneUpperBpm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingCoachTest {

    // --- when it shows ---

    @Test
    fun `shows only while switched on, allowed, a Run is live and the app is off screen`() {
        assertTrue(floatingCoachShows(true, true, RunLifecycle.RUNNING, appOnScreen = false))
        assertTrue(floatingCoachShows(true, true, RunLifecycle.PAUSED, appOnScreen = false))

        assertFalse(floatingCoachShows(false, true, RunLifecycle.RUNNING, appOnScreen = false))
        assertFalse(floatingCoachShows(true, false, RunLifecycle.RUNNING, appOnScreen = false))
        assertFalse(floatingCoachShows(true, true, RunLifecycle.RUNNING, appOnScreen = true))
    }

    @Test
    fun `never shows without a live Run`() {
        listOf(RunLifecycle.IDLE, RunLifecycle.STOPPING, RunLifecycle.STOPPED).forEach {
            assertFalse(it.name, floatingCoachShows(true, true, it, appOnScreen = false))
        }
    }

    // --- the gauge ---

    @Test
    fun `the target zone fills the middle third of the gauge`() {
        assertEquals(1f / 3f, gaugeNeedle(bpm = 120, lowBpm = 120, highBpm = 140)!!, 0.0001f)
        assertEquals(0.5f, gaugeNeedle(bpm = 130, lowBpm = 120, highBpm = 140)!!, 0.0001f)
        assertEquals(2f / 3f, gaugeNeedle(bpm = 140, lowBpm = 120, highBpm = 140)!!, 0.0001f)
    }

    @Test
    fun `the needle pins at either end rather than leaving the dial`() {
        assertEquals(0f, gaugeNeedle(bpm = 60, lowBpm = 120, highBpm = 140)!!, 0.0001f)
        assertEquals(1f, gaugeNeedle(bpm = 200, lowBpm = 120, highBpm = 140)!!, 0.0001f)
    }

    @Test
    fun `no heart rate is no needle`() {
        assertNull(gaugeNeedle(bpm = 0, lowBpm = 120, highBpm = 140))
    }

    @Test
    fun `a one-beat zone still has a middle`() {
        assertEquals(0.5f, gaugeNeedle(bpm = 130, lowBpm = 130, highBpm = 130)!!, 0.0001f)
    }

    @Test
    fun `the needle sits in the third the band names, for every heart rate`() {
        // The needle and the colour must never disagree: whichever third the needle lands in is
        // the band the live screen reports for the same number.
        val profile = HrProfile(190)
        HrZone.values().forEach { zone ->
            val low = zoneLowerBpm(zone, profile)
            val high = zoneUpperBpm(zone, profile)
            (40..220).forEach { bpm ->
                val needle = gaugeNeedle(bpm, low, high)!!
                val third = when {
                    needle < GAUGE_TARGET_START -> ZoneBand.BELOW
                    needle > GAUGE_TARGET_END -> ZoneBand.ABOVE
                    else -> ZoneBand.IN
                }
                assertEquals("$zone at $bpm", zoneBandOf(bpm, profile, zone), third)
            }
        }
    }

    // --- the countdown line ---

    @Test
    fun `the next step reads as what comes and how long until it does`() {
        assertEquals("Walk in 00:45", nextStepText(NextStepUi("Walk", 45)))
        assertEquals("Cool-down in 02:00", nextStepText(NextStepUi("Cool-down", 120)))
    }

    @Test
    fun `no next step is no line`() {
        assertNull(nextStepText(null))
    }

    // --- the face, from the live screen's own reading ---

    private val running = HrState(
        lifecycle = RunLifecycle.RUNNING,
        currentPhase = SessionPhase.MAIN,
        isStructuredWorkout = true,
        structuredWorkoutPhase = StructuredWorkoutPhase.RUN,
        totalRepeats = 4,
        currentRepeat = 2,
        phaseTimeRemainingSeconds = 45,
        nextIntervalType = StructuredWorkoutPhase.WALK,
        nextIntervalDurationSeconds = 90,
        bpm = 131,
        avgBpm = 128,
        userSettings = UserSettings(maxHr = 190, targetZone = 2)
    )

    @Test
    fun `the face is the live screen's numbers, not a second working-out`() {
        val screen = mapWorkoutPlayerUiState(running)
        val face = floatingCoachFace(running)

        assertEquals("131", face.bpmText)
        assertEquals(screen.zoneBand, face.band)
        assertEquals(nextStepText(screen.nextStep), face.nextStepText)
        assertEquals("Walk in 00:45", face.nextStepText)
        assertEquals(
            gaugeNeedle(screen.gaugeBpm, screen.targetLowBpm, screen.targetHighBpm),
            face.needle
        )
    }

    @Test
    fun `no strap reads as a dash, not a zero`() {
        val face = floatingCoachFace(running.copy(bpm = 0, avgBpm = 0))
        assertEquals("--", face.bpmText)
        assertNull(face.needle)
        assertEquals(ZoneBand.UNKNOWN, face.band)
    }
}
