package com.example.runningapp.ui.workout

import com.example.runningapp.HrState
import com.example.runningapp.SessionPhase
import com.example.runningapp.StructuredWorkoutPhase

const val CUE_REASON_PLANNED = "planned_transition"
const val CUE_REASON_HR_HIGH = "hr_too_high"
const val CUE_REASON_HR_RECOVERED = "hr_recovered"
const val CUE_REASON_SENSOR_LOST = "sensor_lost"
const val CUE_REASON_UNKNOWN = "unknown"

enum class CueSeverity {
    INFO,
    WARNING,
    CRITICAL
}

enum class ZoneBand {
    BELOW,
    IN,
    ABOVE,
    UNKNOWN
}

enum class TimelineSegmentType {
    RUN,
    WALK,
    RECOVER,
    OTHER
}

enum class TimelineMarkerType {
    PLANNED_TRANSITION,
    HR_TRIGGER,
    HR_RECOVERY
}

data class TimelineSegmentUi(
    val type: TimelineSegmentType,
    val label: String,
    val durationSeconds: Int
)

data class TimelineMarkerUi(
    val segmentIndex: Int,
    val fractionInSegment: Float,
    val type: TimelineMarkerType
)

data class IntervalTimelineUiState(
    val segments: List<TimelineSegmentUi>,
    val currentSegmentIndex: Int,
    val currentSegmentFraction: Float,
    val markers: List<TimelineMarkerUi>
)

data class CoachCueUiState(
    val message: String,
    val reasonTag: String,
    val severity: CueSeverity
)

data class WorkoutPlayerUiState(
    val phaseLabel: String,
    val countdownText: String,
    val intervalLabel: String,
    val progressLabel: String,
    val progressFraction: Float,
    val nextLabel: String?,
    val hrText: String,
    val zoneBand: ZoneBand,
    val zoneLabel: String,
    val secondaryMetrics: List<Pair<String, String>>,
    val sensorFreshnessText: String,
    val sensorStale: Boolean,
    val timeline: IntervalTimelineUiState?,
    val coachCue: CoachCueUiState?
)

fun mapWorkoutPlayerUiState(state: HrState): WorkoutPlayerUiState {
    val countdownSeconds = when {
        state.currentPhase == SessionPhase.MAIN && state.isStructuredWorkout -> state.phaseTimeRemainingSeconds
        state.currentPhase != SessionPhase.MAIN -> state.phaseSecondsRemaining
        else -> 0
    }
    val isStructuredMain = state.currentPhase == SessionPhase.MAIN && state.isStructuredWorkout && state.totalRepeats > 0
    val progressFraction = (state.workoutProgressPercent.coerceIn(0, 100) / 100f)

    val intervalLabel = if (isStructuredMain) {
        "${state.structuredWorkoutPhase} ${state.currentRepeat}/${state.totalRepeats}"
    } else {
        when (state.currentPhase) {
            SessionPhase.WARM_UP -> "Warm-up"
            SessionPhase.COOL_DOWN -> "Cool-down"
            SessionPhase.MAIN -> "Main"
        }
    }

    val phaseLabel = when (state.currentPhase) {
        SessionPhase.WARM_UP -> "WARM-UP"
        SessionPhase.MAIN -> when (state.structuredWorkoutPhase) {
            StructuredWorkoutPhase.RUN -> "RUN"
            StructuredWorkoutPhase.WALK -> "WALK"
        }
        SessionPhase.COOL_DOWN -> "COOL-DOWN"
    }

    val zoneBand = mapZoneBand(
        bpm = state.avgBpm,
        zoneLow = state.userSettings.zone2Low,
        zoneHigh = state.userSettings.zone2High,
        hasSignal = state.bpm > 0 || state.avgBpm > 0
    )

    val timeline = if (isStructuredMain) mapIntervalTimelineUiState(state) else null
    val cue = mapCoachCueUiState(state)

    val secondary = mutableListOf(
        "Elapsed" to formatStopwatch(state.secondsRunning)
    )
    if (state.runMode == "outdoor") {
        secondary += "Distance" to "%.2f km".format(state.distanceKm)
        if (state.paceMinPerKm > 0) {
            secondary += "Pace" to formatPace(state.paceMinPerKm)
        }
    }

    return WorkoutPlayerUiState(
        phaseLabel = phaseLabel,
        countdownText = formatStopwatch(countdownSeconds.toLong()),
        intervalLabel = intervalLabel,
        progressLabel = "${state.workoutProgressPercent.coerceIn(0, 100)}%",
        progressFraction = progressFraction,
        nextLabel = state.nextIntervalType?.let { next ->
            if (state.nextIntervalDurationSeconds > 0) {
                "Next: $next ${formatStopwatch(state.nextIntervalDurationSeconds.toLong())}"
            } else {
                "Next: $next"
            }
        },
        hrText = "${state.bpm} bpm",
        zoneBand = zoneBand,
        zoneLabel = "Z2 ${state.userSettings.zone2Low}-${state.userSettings.zone2High}",
        secondaryMetrics = secondary,
        sensorFreshnessText = if (state.lastHrAgeSeconds > 0) "HR age ${state.lastHrAgeSeconds}s" else "HR signal active",
        sensorStale = state.lastHrAgeSeconds >= 5,
        timeline = timeline,
        coachCue = cue
    )
}

fun mapZoneBand(bpm: Int, zoneLow: Int, zoneHigh: Int, hasSignal: Boolean): ZoneBand {
    if (!hasSignal || bpm <= 0) return ZoneBand.UNKNOWN
    if (bpm < zoneLow) return ZoneBand.BELOW
    if (bpm > zoneHigh) return ZoneBand.ABOVE
    return ZoneBand.IN
}

fun mapIntervalTimelineUiState(state: HrState): IntervalTimelineUiState {
    val repeats = state.totalRepeats.coerceAtLeast(1)
    val segments = buildList {
        repeat(repeats) {
            add(TimelineSegmentUi(TimelineSegmentType.RUN, "RUN", state.currentIntervalPlannedSeconds.coerceAtLeast(1)))
            add(TimelineSegmentUi(TimelineSegmentType.WALK, "WALK", state.nextIntervalDurationSeconds.coerceAtLeast(1)))
        }
    }

    val repeatIndexZero = (state.currentRepeat - 1).coerceAtLeast(0)
    val currentSegmentIndex = (repeatIndexZero * 2) + if (state.structuredWorkoutPhase == StructuredWorkoutPhase.WALK) 1 else 0
    val fraction = if (state.currentIntervalPlannedSeconds > 0) {
        (state.currentIntervalElapsedSeconds.toFloat() / state.currentIntervalPlannedSeconds.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val markers = buildList {
        add(
            TimelineMarkerUi(
                segmentIndex = currentSegmentIndex.coerceIn(0, segments.lastIndex),
                fractionInSegment = 1f,
                type = TimelineMarkerType.PLANNED_TRANSITION
            )
        )
        if (state.hrCapExceededInCurrentInterval && state.hrCapExceededAtSecond != null && state.currentIntervalPlannedSeconds > 0) {
            add(
                TimelineMarkerUi(
                    segmentIndex = currentSegmentIndex.coerceIn(0, segments.lastIndex),
                    fractionInSegment = (state.hrCapExceededAtSecond.toFloat() / state.currentIntervalPlannedSeconds.toFloat()).coerceIn(0f, 1f),
                    type = TimelineMarkerType.HR_TRIGGER
                )
            )
        }
    }

    return IntervalTimelineUiState(
        segments = segments,
        currentSegmentIndex = currentSegmentIndex.coerceIn(0, segments.lastIndex),
        currentSegmentFraction = fraction,
        markers = markers
    )
}

fun mapCoachCueUiState(state: HrState): CoachCueUiState? {
    val reasonLower = state.currentWalkReason.lowercase()
    val staleSignal = state.sessionStatus.name == "RUNNING" && state.lastHrAgeSeconds >= 8

    val reasonTag = when {
        staleSignal -> CUE_REASON_SENSOR_LOST
        state.hrCapExceededInCurrentInterval || reasonLower.contains("hr") || reasonLower.contains("cap") -> CUE_REASON_HR_HIGH
        reasonLower.contains("recover") || reasonLower.contains("resume") -> CUE_REASON_HR_RECOVERED
        reasonLower.contains("planned") -> CUE_REASON_PLANNED
        else -> CUE_REASON_UNKNOWN
    }

    val message = when (reasonTag) {
        CUE_REASON_SENSOR_LOST -> "Sensor signal is stale. Keep effort easy until reconnect."
        CUE_REASON_HR_HIGH -> "Above cap. Walk until HR settles."
        CUE_REASON_HR_RECOVERED -> "Recovered. Resume easy jog."
        CUE_REASON_PLANNED -> "Planned transition. Follow the interval."
        else -> state.cooldownWithHysteresisString.takeIf { it.isNotBlank() } ?: return null
    }

    val severity = when (reasonTag) {
        CUE_REASON_SENSOR_LOST -> CueSeverity.CRITICAL
        CUE_REASON_HR_HIGH -> CueSeverity.WARNING
        else -> CueSeverity.INFO
    }

    return CoachCueUiState(message = message, reasonTag = reasonTag, severity = severity)
}

fun formatStopwatch(seconds: Long): String {
    val clamped = seconds.coerceAtLeast(0)
    val mins = clamped / 60
    val secs = clamped % 60
    return "%02d:%02d".format(mins, secs)
}

private fun formatPace(paceMinPerKm: Double): String {
    val mins = paceMinPerKm.toInt()
    val secs = ((paceMinPerKm - mins) * 60).toInt().coerceIn(0, 59)
    return "%d:%02d /km".format(mins, secs)
}
