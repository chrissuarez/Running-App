package com.example.runningapp.run

import com.example.runningapp.HrProfile
import com.example.runningapp.HrZone
import com.example.runningapp.WorkoutTemplate

/**
 * Which of the two ways a Run is recorded — the only difference the Run itself cares about is
 * whether it asks for GPS.
 *
 * The stored setting is a string, so the mapping lives here rather than being spelled at each
 * comparison; [settingValue] is what goes back into the database row.
 */
enum class RunMode(val settingValue: String) {
    TREADMILL("treadmill"),
    OUTDOOR("outdoor");

    companion object {
        /** Anything unrecognised reads as treadmill: the mode that records nothing extra. */
        fun ofSettingValue(value: String?): RunMode =
            entries.firstOrNull { it.settingValue == value } ?: TREADMILL
    }
}

/**
 * The course a Run set out to follow, and which way round it was set out on (#56).
 *
 * The Route itself is not held here, only which one it is. A Route is a row in a library the runner
 * curates while no Run is going on — it can be renamed, re-measured by a better export of the same
 * file, or thrown away entirely — and the Run has no business holding a copy of a line it did not
 * record. What the Run is entitled to say is which course it set out on, which is what this is.
 *
 * [reversed] is the runner's word that they are running the course the other way round. It changes
 * nothing about the line: the same ground is covered in the same places, so the same line is drawn
 * on the map. What it is for is everything that depends on which way you are *going* along it — the
 * next turn, how far is left — which belong to the tickets that come after this one.
 */
data class RunRoute(val routeId: Long, val reversed: Boolean)

/**
 * Whether a Run recorded in [runMode] can set out on a course at all (#56).
 *
 * The one statement of the rule, and both its readers come through here: the rulebook, which drops a
 * course a treadmill Run was handed ([runRouteSetOutOn]), and the record screen, which does not
 * offer the picker where the answer is no. There is no ground under a treadmill for a course to be
 * over.
 *
 * Two readers rather than one because they answer different questions with the same fact — what a
 * Run may be recorded as, and what a screen may show — and a rule spelled at each of them is a rule
 * free to be spelled differently at one of them later.
 */
fun runModeCanSetOutOnARoute(runMode: RunMode): Boolean = runMode == RunMode.OUTDOOR

/**
 * The course a Run started in [runMode] set out on, given what the record screen offered (#56).
 *
 * The screen does not clear the pick when the runner switches to Treadmill — switching back must not
 * cost them their choice — so a pick genuinely does arrive at START beside a treadmill mode, and
 * something has to drop it. That is this, put to [runModeCanSetOutOnARoute].
 */
fun runRouteSetOutOn(runMode: RunMode, routeId: Long?, reversed: Boolean): RunRoute? {
    if (!runModeCanSetOutOnARoute(runMode)) return null
    return routeId?.let { RunRoute(it, reversed) }
}

/**
 * Everything about a Run that is fixed the moment START is pressed (#131).
 *
 * Nothing changes these for the Run's lifetime. The heart-rate profile used to be read live on
 * every second of zone accounting while Settings stayed reachable mid-Run, so a Run could bank its
 * first minutes against one Max HR and its last against another and mean two things at once. A Run
 * is now recorded entirely under the settings that were in force when it began.
 *
 * What is *not* here is as deliberate: coaching on/off, auto-pause and Split announcements are
 * controls the runner flips during a Run, so they arrive as [RunEvent.ControlsChanged] instead.
 * See [RunControls].
 */
data class RunConfig(
    /** The heart rates this Run's zones are sliced from, as they stood at START. */
    val hrProfile: HrProfile,
    val targetZone: HrZone,
    val runMode: RunMode,
    /**
     * The Workout this Run follows, already resolved from the plan and the coach's prescription —
     * or null for an unplanned Run, and for one where today's plan was skipped (#107). A Run with
     * no Workout has no warm-up, no cool-down and no Intervals.
     */
    val workout: WorkoutTemplate?,
    /** Whether this Run may be sent to the AI coach. Pinned, like everything else here. */
    val includeInAiTraining: Boolean,
    /**
     * The Stage this Run is being recorded under, or null for a Run with no plan attached (#234).
     *
     * Pinned at START like everything else here, and for the sharpest version of the reason: a
     * Stage can be graduated by an evaluation while this Run is still going, and the Run would then
     * be filed under a Stage it was never run under. What a Run is evidence for is settled when it
     * begins.
     */
    val ranUnderStageId: String?,
    /**
     * The Route this Run set out to follow, or null for a Run following none (#56).
     *
     * Pinned like everything else here, and for the sharpest version of the reason a Route needs:
     * the library is editable and a Route can be deleted mid-Run. What the Run set out to follow is
     * settled when it begins, and a course thrown away afterwards does not retrospectively make
     * this an unrouted Run — it makes it a Run whose course is gone, which the map says by drawing
     * nothing and which is exactly what a Route's own doc promises costs a Run nothing.
     *
     * Always null on a treadmill Run, whatever the screen sent — see [runModeCanSetOutOnARoute],
     * which is the one statement of that rule. There is no ground under a treadmill for a course to
     * be over, and nothing that could be measured against one.
     */
    val route: RunRoute? = null,
) {
    val warmUpSeconds: Int get() = workout?.warmUpSeconds ?: 0

    val coolDownSeconds: Int get() = workout?.coolDownSeconds ?: 0

    /** A Run following a Workout is a run/walk Run; that is what the record and the coach read. */
    val isRunWalkMode: Boolean get() = workout != null
}

/**
 * The settings the runner is allowed to change mid-Run.
 *
 * They are delivered as events rather than read from a settings object, because a module that
 * reaches out for a value is not a function of its inputs — and because being obeyed *immediately*
 * is the whole point of a control (#109). Contrast [RunConfig], which is pinned.
 */
data class RunControls(
    val coachingEnabled: Boolean = true,
    val autoPauseEnabled: Boolean = false,
    val splitAnnouncementsEnabled: Boolean = false,
    /**
     * Whether the halfway "turn around" cue may be spoken (#208). On by default, because the Run it
     * applies to — outdoors, following a Workout — is an out-and-back for most runners; the runner
     * doing loops turns it off.
     */
    val turnaroundCueEnabled: Boolean = true,
)
