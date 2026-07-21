package com.example.runningapp.ui.workout

import com.example.runningapp.HrZone
import com.example.runningapp.UserSettings
import com.example.runningapp.WorkoutTemplate
import com.example.runningapp.targetHrZone
import com.example.runningapp.withCoachAdaptation

/**
 * The one thing on the record screen that changes each morning (#111).
 *
 * An open run is a thing, not a gap: it gets the same solid card as a real workout — title, target
 * pill, and a line saying what the app will still do. That is what makes *skipped today* and *no
 * plan at all* the same screen, differing only in the link underneath ([TodayCardLink]); nothing
 * announces an absence, because there isn't one.
 */
data class TodayCardUiState(
    /** "TODAY · STAGE 1: BASE BUILDER", or plain "TODAY" for an open run. */
    val eyebrow: String,
    /** The workout's name, or "Open run". */
    val title: String,
    /** The interval shape ("5 × (5 min run / 1 min walk)"), or what an open run will still do. */
    val detailLine: String,
    /** "Target: Moderate" — information, never a control (#101). */
    val targetPill: String,
    /** "8 min warm-up · 3 min cool-down · ≈ 41 min". Null for an open run, which has no shape. */
    val envelopeLine: String?,
    /** Named change and why, when the coach edited today's numbers. Null when it didn't. */
    val coachNote: String?,
    /** The one link inside the card, bottom-right — an edit to the card, not an alternative to START. */
    val link: TodayCardLink
)

enum class TodayCardLinkKind {
    /** Skip today's plan — today only, never an edit to the plan. */
    SKIP,

    /** Undo the skip, in the exact slot Skip vacated. */
    UNDO,

    /** No plan is attached at all: go pick one. */
    CHOOSE_PLAN
}

data class TodayCardLink(val kind: TodayCardLinkKind, val label: String)

/**
 * The card's full state for one morning.
 *
 * [baseWorkout] is the plan's queued workout *before* the coach's adaptation — this applies it, so
 * the card and the run resolve today's numbers through the same function. A null [baseWorkout] (no
 * plan) and [skippedToday] both produce the open-run card; only [link] tells them apart.
 */
fun todayCardUiState(
    stageTitle: String?,
    baseWorkout: WorkoutTemplate?,
    settings: UserSettings,
    runMode: String,
    skippedToday: Boolean
): TodayCardUiState {
    val planned = baseWorkout?.withCoachAdaptation(settings)

    if (planned == null || skippedToday) {
        return TodayCardUiState(
            eyebrow = "TODAY",
            title = "Open run",
            detailLine = openRunPromise(settings, runMode),
            // The workout's target is irrelevant on a day you aren't running it; an open run aims
            // at the global default.
            targetPill = targetPill(settings.targetHrZone),
            envelopeLine = null,
            coachNote = null,
            link = if (baseWorkout != null) {
                TodayCardLink(TodayCardLinkKind.UNDO, "Bring back ${baseWorkout.title}")
            } else {
                TodayCardLink(TodayCardLinkKind.CHOOSE_PLAN, "Choose a plan")
            }
        )
    }

    return TodayCardUiState(
        eyebrow = if (stageTitle.isNullOrBlank()) "TODAY" else "TODAY · ${stageTitle.uppercase()}",
        title = planned.title,
        detailLine = intervalShape(planned),
        targetPill = targetPill(HrZone.ofNumberOrDefault(planned.targetZone)),
        envelopeLine = envelopeLine(planned),
        coachNote = coachNote(baseWorkout, planned, settings),
        link = TodayCardLink(TodayCardLinkKind.SKIP, "Skip today")
    )
}

private fun targetPill(zone: HrZone): String = "Target: ${zone.zoneName}"

/**
 * What an open run will still do. Every run records; this names the two things a runner would
 * otherwise wonder about, and promises splits only where they can happen — they are measured from
 * GPS, so a treadmill run has none.
 */
private fun openRunPromise(settings: UserSettings, runMode: String): String = buildList {
    add(if (settings.coachingEnabled) "Zone coaching on" else "Zone coaching off")
    if (runMode == "outdoor" && settings.splitAnnouncementsEnabled) add("splits every 1 km")
}.joinToString(" · ")

/** "5 × (5 min run / 1 min walk)", dropping the parts a workout doesn't have. */
private fun intervalShape(workout: WorkoutTemplate): String {
    val run = "${formatDuration(workout.runDurationSeconds)} run"
    val body = if (workout.walkDurationSeconds > 0) {
        "($run / ${formatDuration(workout.walkDurationSeconds)} walk)"
    } else {
        run
    }
    return if (workout.totalRepeats > 1) "${workout.totalRepeats} × $body" else body.removeSurrounding("(", ")")
}

/**
 * "8 min warm-up · 3 min cool-down · ≈ 41 min". The total was missing from the sketch and is the
 * most-wanted number at 6am, so it is the one part that always appears.
 */
private fun envelopeLine(workout: WorkoutTemplate): String = buildList {
    if (workout.warmUpSeconds > 0) add("${formatDuration(workout.warmUpSeconds)} warm-up")
    if (workout.coolDownSeconds > 0) add("${formatDuration(workout.coolDownSeconds)} cool-down")
    add("≈ ${totalMinutes(workout)} min")
}.joinToString(" · ")

/** Rounded to the nearest minute, but never to zero — a run always takes some time. */
private fun totalMinutes(workout: WorkoutTemplate): Int {
    val total = workout.warmUpSeconds +
        workout.totalRepeats * (workout.runDurationSeconds + workout.walkDurationSeconds) +
        workout.coolDownSeconds
    return ((total + 30) / 60).coerceAtLeast(1)
}

/**
 * Adapted numbers are shown directly, plus one line naming the change and why — never the original
 * numbers, and never a silent edit. Falls back to a plain statement when the coach adjusted the
 * numbers without leaving a reason.
 */
private fun coachNote(
    baseWorkout: WorkoutTemplate,
    planned: WorkoutTemplate,
    settings: UserSettings
): String? {
    if (planned == baseWorkout) return null
    val message = settings.latestCoachMessage?.takeIf { it.isNotBlank() }
        ?: return "Coach: Today's intervals were adjusted for you."
    return "Coach: $message"
}

/** "5 min", "10 s", "1 min 30 s" — whole minutes wherever a workout has them. */
private fun formatDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val minutes = safe / 60
    val remainder = safe % 60
    return when {
        minutes == 0 -> "$remainder s"
        remainder == 0 -> "$minutes min"
        else -> "$minutes min $remainder s"
    }
}
