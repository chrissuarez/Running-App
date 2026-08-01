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
