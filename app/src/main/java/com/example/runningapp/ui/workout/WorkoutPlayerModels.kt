package com.example.runningapp.ui.workout

import com.example.runningapp.HrState
import com.example.runningapp.SessionPhase
import com.example.runningapp.SessionStatus
import com.example.runningapp.StructuredWorkoutPhase
import com.example.runningapp.ZoneBand
import com.example.runningapp.data.formatMinutesPerKm
import com.example.runningapp.hrZoneOf
import com.example.runningapp.liveZoneStatus
import com.example.runningapp.targetHrZone
import com.example.runningapp.zoneBandOf

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
    val zoneStatusText: String,
    val secondaryMetrics: List<Pair<String, String>>,
    val sensorFreshnessText: String,
    val sensorStale: Boolean,
    val timeline: IntervalTimelineUiState?,
    val coachCue: CoachCueUiState?
)

fun mapWorkoutPlayerUiState(state: HrState): WorkoutPlayerUiState {
    val countdownSeconds = when {
        state.currentPhase == SessionPhase.MAIN && state.isStructuredWorkout -> state.phaseTimeRemainingSeconds
        state.currentPhase == SessionPhase.MAIN -> state.phaseSecondsElapsed.toInt()
        state.currentPhase != SessionPhase.MAIN -> state.phaseSecondsRemaining
        else -> 0
    }
    val isStructuredMain = state.currentPhase == SessionPhase.MAIN && state.isStructuredWorkout && state.totalRepeats > 0
    val progressPercent = state.workoutProgressPercent.coerceIn(0, 100)
    val progressFraction = progressPercent / 100f

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

    // The run's target is frozen at its start (activeTargetZone); fall back to the live global
    // only when idle. This keeps the label and band in step with the coach if the global target
    // is changed mid-run.
    val targetZone = state.activeTargetZone ?: state.userSettings.targetHrZone
    val hasSignal = state.bpm > 0 || state.avgBpm > 0
    // Trust avgBpm only while the coach is actively filling its window this packet — then the
    // screen agrees with the coach's smoothed reading. The coach adds a sample only when running,
    // in a coached phase (MAIN/WARM_UP), with coaching on; anywhere else — coaching off, cool-down,
    // paused — avgBpm is stale (0, or frozen at the last main-run value) while bpm keeps changing,
    // so show the live bpm. That gives a live zone/colour rather than a dash or a frozen zone.
    val coachSampling = state.sessionStatus == SessionStatus.RUNNING &&
        (state.currentPhase == SessionPhase.MAIN || state.currentPhase == SessionPhase.WARM_UP) &&
        state.userSettings.coachingEnabled
    val displayBpm = if (coachSampling && state.avgBpm > 0) state.avgBpm else state.bpm
    val zoneBand = if (hasSignal) zoneBandOf(displayBpm, state.userSettings.maxHr, targetZone) else ZoneBand.UNKNOWN
    // The screen names the zone you are actually in (not the target) and the action to close the
    // gap to target; band, not zone, picks the action so words and colour agree. See #109.
    val actualZone = if (hasSignal) hrZoneOf(displayBpm, state.userSettings.maxHr) else null

    val timeline = if (isStructuredMain) mapIntervalTimelineUiState(state) else null
    val cue = mapCoachCueUiState(state)

    val secondary = mutableListOf(
        "Elapsed" to formatStopwatch(state.secondsRunning)
    )
    // The run's pinned mode, not the live setting: the settings write from a just-tapped mode
    // toggle is async, and an outdoor run started right after the tap must still show
    // distance/pace while GPS records.
    if ((state.activeRunMode ?: state.userSettings.runMode) == "outdoor") {
        secondary += "Distance" to formatDistanceKm(state.distanceKm)
        if (state.paceMinPerKm > 0) {
            secondary += "Pace" to formatPace(state.paceMinPerKm)
        }
    }

    return WorkoutPlayerUiState(
        phaseLabel = phaseLabel,
        countdownText = formatStopwatch(countdownSeconds.toLong()),
        intervalLabel = intervalLabel,
        progressLabel = "${progressPercent}%",
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
        zoneStatusText = liveZoneStatus(actualZone?.zoneName, zoneBand),
        secondaryMetrics = secondary,
        // Key freshness off live bpm, not age alone: lastHrAgeSeconds stays 0 both when a packet
        // just arrived AND when none ever has (a strapless run, or a saved strap left off), so a
        // no-signal run would otherwise read "HR signal active" all run. bpm is 0 whenever there is
        // no live reading — a strapless run, or a mid-run dropout (which zeros bpm) — so it cleanly
        // marks the sensor absent. The >= 8s sensor-lost coach cue still keys off age, so it fires
        // only for a real dropout (age grows from the last packet) and never nags a chosen no-strap run.
        sensorFreshnessText = when {
            state.bpm <= 0 -> "No HR signal"
            state.lastHrAgeSeconds > 0 -> "HR age ${state.lastHrAgeSeconds}s"
            else -> "HR signal active"
        },
        sensorStale = state.bpm <= 0 || state.lastHrAgeSeconds >= 5,
        timeline = timeline,
        coachCue = cue
    )
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
        // "Planned transition" only means something on a structured workout. On an open run
        // currentWalkReason is still its default "Planned", so without this gate the coach card
        // would tell an open-run user to "follow the interval" that doesn't exist (#107).
        state.isStructuredWorkout && reasonLower.contains("planned") -> CUE_REASON_PLANNED
        else -> CUE_REASON_UNKNOWN
    }

    val message = when (reasonTag) {
        CUE_REASON_SENSOR_LOST -> "Sensor signal is stale. Keep effort easy until reconnect."
        CUE_REASON_HR_HIGH -> "Above cap. Walk until HR settles."
        CUE_REASON_HR_RECOVERED -> "Recovered. Resume easy jog."
        CUE_REASON_PLANNED -> "Planned transition. Follow the interval."
        else -> state.coachWaitingLine.takeIf { it.isNotBlank() } ?: return null
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

fun formatPace(paceMinPerKm: Double): String = "${formatMinutesPerKm(paceMinPerKm)} /km"

fun formatDistanceKm(distanceKm: Double): String = "%.2f km".format(distanceKm)
