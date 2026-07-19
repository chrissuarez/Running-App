package com.example.runningapp.ui.workout

import com.example.runningapp.HrState
import com.example.runningapp.HrZone
import com.example.runningapp.SessionPhase
import com.example.runningapp.SessionStatus
import com.example.runningapp.StructuredWorkoutPhase
import com.example.runningapp.UserSettings
import com.example.runningapp.ZoneBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutPlayerModelsTest {

    private fun stateWithHr(bpm: Int, targetZone: Int) = HrState(
        sessionStatus = SessionStatus.RUNNING,
        currentPhase = SessionPhase.MAIN,
        bpm = bpm,
        avgBpm = bpm,
        userSettings = UserSettings(maxHr = 190, targetZone = targetZone)
    )

    @Test
    fun `zone band comes from the current zone against the target zone`() {
        assertEquals(ZoneBand.BELOW, mapWorkoutPlayerUiState(stateWithHr(100, targetZone = 2)).zoneBand)
        assertEquals(ZoneBand.IN, mapWorkoutPlayerUiState(stateWithHr(120, targetZone = 2)).zoneBand)
        assertEquals(ZoneBand.ABOVE, mapWorkoutPlayerUiState(stateWithHr(140, targetZone = 2)).zoneBand)
        assertEquals(ZoneBand.UNKNOWN, mapWorkoutPlayerUiState(stateWithHr(0, targetZone = 2)).zoneBand)

        // Same heart rate, different target.
        assertEquals(ZoneBand.IN, mapWorkoutPlayerUiState(stateWithHr(140, targetZone = 3)).zoneBand)
    }

    @Test
    fun `the frozen run target wins over a global changed mid-run`() {
        // Global was moved to Z3 mid-run, but the run started against Z2. 140 bpm is ABOVE Z2 and
        // IN Z3 — the label and band must both stay with the frozen Z2, matching the coach.
        val state = stateWithHr(140, targetZone = 3).copy(activeTargetZone = HrZone.MODERATE)
        assertEquals("Z2 114-132", mapWorkoutPlayerUiState(state).zoneLabel)
        assertEquals(ZoneBand.ABOVE, mapWorkoutPlayerUiState(state).zoneBand)
    }

    @Test
    fun `zone label names the target zone band`() {
        assertEquals("Z2 114-132", mapWorkoutPlayerUiState(stateWithHr(120, targetZone = 2)).zoneLabel)
        assertEquals("Z3 133-151", mapWorkoutPlayerUiState(stateWithHr(120, targetZone = 3)).zoneLabel)
    }

    @Test
    fun `formatStopwatch handles negative and regular values`() {
        assertEquals("00:00", formatStopwatch(-4))
        assertEquals("00:09", formatStopwatch(9))
        assertEquals("02:05", formatStopwatch(125))
    }

    @Test
    fun `mapIntervalTimelineUiState includes planned and hr markers`() {
        val state = HrState(
            currentPhase = SessionPhase.MAIN,
            isStructuredWorkout = true,
            totalRepeats = 4,
            currentRepeat = 2,
            structuredWorkoutPhase = StructuredWorkoutPhase.RUN,
            currentIntervalPlannedSeconds = 120,
            currentIntervalElapsedSeconds = 30,
            nextIntervalDurationSeconds = 60,
            hrCapExceededInCurrentInterval = true,
            hrCapExceededAtSecond = 18
        )

        val timeline = mapIntervalTimelineUiState(state)

        assertEquals(8, timeline.segments.size)
        assertEquals(2, timeline.currentSegmentIndex)
        assertEquals(TimelineMarkerType.PLANNED_TRANSITION, timeline.markers.first().type)
        assertTrue(timeline.markers.any { it.type == TimelineMarkerType.HR_TRIGGER })
    }

    @Test
    fun `mapWorkoutPlayerUiState builds expected labels and cue reason`() {
        val state = HrState(
            sessionStatus = SessionStatus.RUNNING,
            currentPhase = SessionPhase.MAIN,
            isStructuredWorkout = true,
            structuredWorkoutPhase = StructuredWorkoutPhase.WALK,
            currentRepeat = 3,
            totalRepeats = 8,
            phaseTimeRemainingSeconds = 42,
            currentIntervalPlannedSeconds = 90,
            currentIntervalElapsedSeconds = 20,
            workoutProgressPercent = 40,
            bpm = 152,
            avgBpm = 152,
            currentWalkReason = "HR cap exceeded",
            hrCapExceededInCurrentInterval = true,
            userSettings = UserSettings(maxHr = 190, targetZone = 2)
        )

        val ui = mapWorkoutPlayerUiState(state)

        assertEquals("WALK", ui.phaseLabel)
        assertEquals("WALK 3/8", ui.intervalLabel)
        assertEquals("00:42", ui.countdownText)
        assertEquals(ZoneBand.ABOVE, ui.zoneBand)
        assertEquals(CUE_REASON_HR_HIGH, ui.coachCue?.reasonTag)
    }

    @Test
    fun `open run does not surface a planned-interval coach cue`() {
        // currentWalkReason defaults to "Planned"; on an open run there is no interval to follow,
        // so the planned-transition cue must not fire (#107). Blanking the zone-coaching passthrough
        // isolates the gate: with nothing else to say, the open run yields no cue at all.
        val openRun = HrState(
            sessionStatus = SessionStatus.RUNNING,
            currentPhase = SessionPhase.MAIN,
            isStructuredWorkout = false,
            currentWalkReason = "Planned",
            cooldownWithHysteresisString = ""
        )
        assertEquals(null, mapCoachCueUiState(openRun))

        // The same reason on a structured workout still coaches the interval.
        val structured = openRun.copy(isStructuredWorkout = true)
        assertEquals(CUE_REASON_PLANNED, mapCoachCueUiState(structured)?.reasonTag)
    }

    @Test
    fun `mapWorkoutPlayerUiState uses elapsed timer for non-structured main sessions`() {
        val state = HrState(
            currentPhase = SessionPhase.MAIN,
            isStructuredWorkout = false,
            phaseSecondsElapsed = 93,
            sessionStatus = SessionStatus.RUNNING
        )

        val ui = mapWorkoutPlayerUiState(state)

        assertEquals("01:33", ui.countdownText)
        assertEquals("Main", ui.intervalLabel)
    }
}
