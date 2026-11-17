package com.example.runningapp.floating

import com.example.runningapp.HrState
import com.example.runningapp.ZoneBand
import com.example.runningapp.analysis.formatMinutesSeconds
import com.example.runningapp.run.RunLifecycle
import com.example.runningapp.ui.workout.NextStepUi
import com.example.runningapp.ui.workout.mapWorkoutPlayerUiState

/**
 * The floating coach (#545): a small box drawn over other apps during a Run, so a runner watching
 * a film full screen on the treadmill can still see their heart rate, where it sits in the target
 * zone, and what the Workout does next.
 *
 * Everything it shows is read off [mapWorkoutPlayerUiState] — the live screen's own reading — so
 * the box and the screen can never disagree about a number. That includes the screen's one split:
 * the big number is the live beat, as the screen prints it, while the needle and colour follow the
 * smoothed reading the band is judged on, exactly as the screen's zone line does. This file holds the decisions; the
 * window itself ([FloatingCoachWindow]) only draws what it is handed.
 */
data class FloatingCoachFace(
    /** The heart rate, big. "--" with no reading, so a lost strap never reads as a zero. */
    val bpmText: String,
    /** Where the needle sits on the dial, 0 (far left) to 1 (far right); null with no reading. */
    val needle: Float?,
    val band: ZoneBand,
    /** What comes next and when, or null where nothing is scheduled next. */
    val nextStepText: String?
)

fun floatingCoachFace(state: HrState): FloatingCoachFace {
    val screen = mapWorkoutPlayerUiState(state)
    return FloatingCoachFace(
        bpmText = if (state.bpm > 0) state.bpm.toString() else "--",
        needle = gaugeNeedle(screen.gaugeBpm, screen.targetLowBpm, screen.targetHighBpm),
        band = screen.zoneBand,
        nextStepText = nextStepText(screen.nextStep)
    )
}

/**
 * Whether the box should be on screen at all.
 *
 * Only while a Run is live — paused counts, since the runner is still on the belt — and only while
 * the app itself is out of sight: with the app open, the live screen already says all of this.
 * [mayDrawOverlays] is Android's own answer, asked fresh each time, because the runner can take the
 * permission away in system settings at any moment and the switch in this app would not know.
 */
fun floatingCoachShows(
    enabled: Boolean,
    mayDrawOverlays: Boolean,
    lifecycle: RunLifecycle,
    appOnScreen: Boolean
): Boolean = enabled && mayDrawOverlays && lifecycle.isLive && !appOnScreen

/** Where the target zone begins and ends on the dial, as fractions of its sweep. */
const val GAUGE_TARGET_START = 1f / 3f
const val GAUGE_TARGET_END = 2f / 3f

/**
 * Where [bpm] puts the needle on a speedo-style dial whose middle third is the target zone.
 *
 * The thirds either side are each one zone-width wide, below and above, and the needle pins at the
 * ends past them rather than leaving the dial. The target edges are inclusive — exactly as
 * [com.example.runningapp.zoneBandOf] counts them — so the third the needle lands in is always the
 * band the colour says.
 */
fun gaugeNeedle(bpm: Int, lowBpm: Int, highBpm: Int): Float? {
    if (bpm <= 0) return null
    val width = (highBpm - lowBpm).coerceAtLeast(1).toFloat()
    return when {
        bpm < lowBpm ->
            GAUGE_TARGET_START * ((bpm - (lowBpm - width)) / width).coerceIn(0f, 1f)
        bpm > highBpm ->
            (GAUGE_TARGET_END + (1f - GAUGE_TARGET_END) * ((bpm - highBpm) / width)).coerceIn(0f, 1f)
        highBpm == lowBpm -> (GAUGE_TARGET_START + GAUGE_TARGET_END) / 2f
        // Clamped so float rounding can never nudge an in-zone needle out of the middle third.
        else -> (GAUGE_TARGET_START + (GAUGE_TARGET_END - GAUGE_TARGET_START) * ((bpm - lowBpm) / width))
            .coerceIn(GAUGE_TARGET_START, GAUGE_TARGET_END)
    }
}

/** "Walk in 00:45" — the step that comes next and how long until it does. */
fun nextStepText(step: NextStepUi?): String? =
    step?.let { "${it.label} in ${formatMinutesSeconds(it.secondsLeft.toLong())}" }
