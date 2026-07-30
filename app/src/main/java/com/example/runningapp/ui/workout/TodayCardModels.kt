package com.example.runningapp.ui.workout

import com.example.runningapp.CoachPrescription
import com.example.runningapp.HrZone
import com.example.runningapp.RunType
import com.example.runningapp.UserSettings
import com.example.runningapp.WorkoutTemplate
import com.example.runningapp.pickedOrFirst
import com.example.runningapp.targetHrZone
import com.example.runningapp.withCoachPrescription

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
    val link: TodayCardLink,
    /**
     * The Stage's Workouts, one per Run Type, for the runner to pick today's from (#174). Empty on
     * an open run, which has no Stage to pick from.
     */
    val choices: List<TodayCardChoice>
)

/**
 * One of the Stage's Workouts, offered as today's Run (#174).
 *
 * The Plan is a menu, not a cursor: all three are always on offer, in the order the Stage declares
 * them, and picking is the whole of choosing today's Run. Nothing here remembers a position in a
 * week, so there is no rule to invent for a missed Run or two in a day.
 */
data class TodayCardChoice(
    val workoutId: String,
    /** "Long", "Easy" or "Quality" — what makes the three differ in kind rather than length. */
    val runTypeLabel: String,
    /** The Workout's own name, e.g. "Endurance Walk-Run". */
    val title: String,
    /**
     * "3 × (10 min run / 2 min walk) · ≈ 47 min" — the shape and the total, at the numbers this
     * Workout will actually be run at, prescription included.
     */
    val summaryLine: String,
    /** Whether this is today's Run. Exactly one choice is selected whenever there are any. */
    val selected: Boolean
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
 * [stageWorkouts] are the Stage's own Workouts *before* the coach's prescription — this applies it,
 * so the card and the run resolve today's numbers through the same function. An empty
 * [stageWorkouts] (no plan) and [skippedToday] both produce the open-run card; only [link] tells
 * them apart.
 *
 * [pickedWorkoutId] is the runner's choice of today's Run (#174), and the Stage's first Workout
 * until they make one. An id that names nothing here — the Stage changed under a stale pick — falls
 * back to that same first Workout rather than leaving the card with nothing to be about.
 */
fun todayCardUiState(
    stageTitle: String?,
    stageWorkouts: List<WorkoutTemplate>,
    pickedWorkoutId: String?,
    settings: UserSettings,
    prescription: CoachPrescription?,
    nowEpochMillis: Long,
    runMode: String,
    skippedToday: Boolean
): TodayCardUiState {
    val picked = stageWorkouts.pickedOrFirst(pickedWorkoutId)
    val planned = picked?.withCoachPrescription(prescription, nowEpochMillis)

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
            link = if (picked != null) {
                TodayCardLink(TodayCardLinkKind.UNDO, "Bring back ${picked.title}")
            } else {
                TodayCardLink(TodayCardLinkKind.CHOOSE_PLAN, "Choose a plan")
            },
            // Nothing is being picked from on a day the plan isn't being run: the link back is the
            // only choice the open-run card offers.
            choices = emptyList()
        )
    }

    return TodayCardUiState(
        eyebrow = if (stageTitle.isNullOrBlank()) "TODAY" else "TODAY · ${stageTitle.uppercase()}",
        title = planned.title,
        detailLine = intervalShape(planned),
        targetPill = targetPill(HrZone.ofNumberOrDefault(planned.targetZone)),
        envelopeLine = envelopeLine(planned),
        coachNote = coachNote(picked, planned, settings),
        link = TodayCardLink(TodayCardLinkKind.SKIP, "Skip today"),
        choices = stageWorkouts.map { workout ->
            // Each choice asks the prescription for itself rather than borrowing today's answer:
            // the floor (#170) is measured against the workout being adapted, so the same standing
            // prescription can move one of the three and leave another as the plan wrote it.
            val asRun = workout.withCoachPrescription(prescription, nowEpochMillis)
            TodayCardChoice(
                workoutId = workout.id,
                runTypeLabel = runTypeLabel(workout.runType),
                title = workout.title,
                summaryLine = "${intervalShape(asRun)} · ≈ ${totalMinutes(asRun)} min",
                selected = workout.id == picked.id
            )
        }
    )
}

private fun runTypeLabel(runType: RunType): String = when (runType) {
    RunType.LONG -> "Long"
    RunType.EASY -> "Easy"
    RunType.QUALITY -> "Quality"
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
 * Prescribed numbers are shown directly, plus one line naming the change and why — never the
 * original numbers, and never a silent edit. Falls back to a plain statement when the coach changed
 * the numbers without leaving a reason.
 *
 * Only the debrief's [firstSentence] appears here. The debrief is written to be read whole and runs
 * to several paragraphs; dropped into the card entire it pushed the skip link and everything under
 * it off the screen (#113, found on a device — the coach has to be reachable to see it). The full
 * text still shows in its own slot on the screen when there is no prescription for it to explain.
 */
private fun coachNote(
    picked: WorkoutTemplate,
    planned: WorkoutTemplate,
    settings: UserSettings
): String? {
    if (planned == picked) return null
    val message = settings.latestCoachMessage?.takeIf { it.isNotBlank() }
        ?: return "Coach: Today's intervals were adjusted for you."
    return "Coach: ${firstSentence(message)}"
}

/**
 * Up to and including the first `.`, `!` or `?` that ends a word.
 *
 * The terminator has to be followed by a space or the end of the text, or the coach's own numbers
 * would cut the sentence in half — "your pace was 5.30" ends at "5." otherwise. Text with no
 * terminator at all is returned whole rather than guessed at.
 */
private fun firstSentence(text: String): String {
    val trimmed = text.trim()
    trimmed.forEachIndexed { i, c ->
        if (c == '.' || c == '!' || c == '?') {
            val next = trimmed.getOrNull(i + 1)
            if (next == null || next.isWhitespace()) return trimmed.substring(0, i + 1)
        }
    }
    return trimmed
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
