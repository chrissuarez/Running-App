package com.example.runningapp.ui.workout

import com.example.runningapp.HrState
import com.example.runningapp.SessionPhase
import com.example.runningapp.SessionStatus
import com.example.runningapp.StructuredWorkoutPhase
import com.example.runningapp.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutPlayerModelsTest {

    @Test
    fun `mapZoneBand returns below in above correctly`() {
        assertEquals(ZoneBand.BELOW, mapZoneBand(bpm = 120, zoneLow = 130, zoneHigh = 150, hasSignal = true))
        assertEquals(ZoneBand.IN, mapZoneBand(bpm = 140, zoneLow = 130, zoneHigh = 150, hasSignal = true))
        assertEquals(ZoneBand.ABOVE, mapZoneBand(bpm = 160, zoneLow = 130, zoneHigh = 150, hasSignal = true))
        assertEquals(ZoneBand.UNKNOWN, mapZoneBand(bpm = 0, zoneLow = 130, zoneHigh = 150, hasSignal = false))
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
            userSettings = UserSettings(zone2Low = 120, zone2High = 145)
        )

        val ui = mapWorkoutPlayerUiState(state)

        assertEquals("WALK", ui.phaseLabel)
        assertEquals("WALK 3/8", ui.intervalLabel)
        assertEquals("00:42", ui.countdownText)
        assertEquals(ZoneBand.ABOVE, ui.zoneBand)
        assertEquals(CUE_REASON_HR_HIGH, ui.coachCue?.reasonTag)
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

    @Test
    fun `mapWorkoutPlayerUiState shows easy fixed duration copy without interval targets`() {
        val state = HrState(
            sessionType = "Easy Fixed Duration",
            currentPhase = SessionPhase.MAIN,
            isStructuredWorkout = false,
            phaseSecondsElapsed = 900,
            phaseSecondsRemaining = 900,
            sessionStatus = SessionStatus.RUNNING
        )

        val ui = mapWorkoutPlayerUiState(state)

        assertEquals("EASY SESSION", ui.phaseLabel)
        assertEquals("30 min easy session", ui.intervalLabel)
        assertEquals("15:00", ui.countdownText)
        assertEquals("self-adjust jog/walk", ui.nextLabel)
        assertEquals("50%", ui.progressLabel)
        assertEquals(null, ui.timeline)
    }
}
