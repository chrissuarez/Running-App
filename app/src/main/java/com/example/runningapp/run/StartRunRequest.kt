package com.example.runningapp.run

/**
 * One tap on START, whole (#56).
 *
 * Every choice the record screen offers travels together, because they are one decision: the Run
 * the runner is asking for. They used to travel as loose parameters, and a choice added to the
 * screen had to be threaded through the tap, the permission dialog it may park behind, the intent,
 * and the Simulate button beside it — four places, any one of which could be missed, and a Run
 * would then set off on three of the four things the runner picked.
 *
 * It is the *request* and not the Run's configuration: what the screen asked for, before the
 * rulebook has had its say about what a Run may be recorded as (see `RunConfig`).
 */
data class StartRunRequest(
    /** Today's plan set aside for this Run only — it never edits the plan (#107). */
    val skipPlan: Boolean,
    /** Treadmill or outdoor, as the toggle stood — see [RunMode.settingValue]. */
    val runMode: String,
    /** Which of the Stage's Workouts the card was showing (#174), or null for none. */
    val pickedWorkoutId: String?,
    /** The Route picked to follow, or null for a Run following none (#56). */
    val routeId: Long?,
    /** Which way round that course is to be run. Meaningless without [routeId] beside it. */
    val routeReversed: Boolean,
)
