package com.example.runningapp

data class TrainingPlan(
    val id: String,
    val name: String,
    val description: String,
    val stages: List<PlanStage>
)

data class PlanStage(
    val id: String,
    val title: String,
    val description: String,
    val graduationRequirementText: String,
    val isLocked: Boolean = true,
    val workouts: List<WorkoutTemplate>
)

/**
 * What kind of work a Workout is (#173) — the thing that makes two Workouts differ in kind rather
 * than only in length. Stage 1 offers one of each and the runner picks; the later stages, which
 * are locked and still shaped as they were, only declare what they already are.
 */
enum class RunType {
    /** Endurance, built from long run Intervals with short walks between them. */
    LONG,

    /** Continuous and unhurried: one repeat, no walk. */
    EASY,

    /** The hard day, whatever its shape — Stage 1 spends it on strides after a long easy warm-up. */
    QUALITY
}

/**
 * Whether the AI coach evaluates a Run of this kind and writes a Prescription for it (#176).
 *
 * The Long Run only, because it is the one session where the judgement is genuinely worth making:
 * whether this week's endurance run goes from 30 minutes of running to 36 depends on how the last
 * few went. The Easy Run is a fixed continuous stretch and the Quality Run a fixed set of strides —
 * both are recorded in full and count toward history and the 30-day load, and neither is adjusted.
 *
 * This replaces asking whether the last Run had walk Intervals, a proxy that fails in both
 * directions once a Stage offers one Workout of each kind: a continuous Easy Run would be skipped
 * for having no walks, and a Quality Run *would* be evaluated — so the coach would start adjusting
 * the one session that most wants leaving alone.
 *
 * Accepted gap: the Quality Run never progresses on its own. Taking it from six strides toward
 * eight is a static rule for its own ticket, and an AI does not belong in it.
 */
val RunType.isCoachAdjusted: Boolean get() = this == RunType.LONG

data class WorkoutTemplate(
    val id: String,
    val title: String,
    val targetZone: Int, // e.g., 2
    val runDurationSeconds: Int,
    val walkDurationSeconds: Int,
    val totalRepeats: Int,
    // Warm-up/cool-down are structure, so they live on the workout (#107): a plan can prescribe a
    // longer warm-up before a hard day, and an unplanned run has neither. The 480/180 defaults
    // preserve the pre-#107 global envelope for every real workout that doesn't override them.
    val warmUpSeconds: Int = 480,
    val coolDownSeconds: Int = 180,
    // No default: every Workout declares its kind (#173), because a Workout the plan cannot name
    // the kind of is one the coach cannot decide whether to adjust.
    val runType: RunType
)

/**
 * Whether a main set of [repeats] × ([runSeconds] run + [walkSeconds] walk) is at least as much
 * work as this workout's own — the floor of #170, in one place because it is asked twice: when the
 * coach writes a prescription, and again when one is applied.
 *
 * Two measures, both of which have to clear: the main set's total seconds, and the running seconds
 * inside it. Total alone would let six 30s runs padded with 210s walks match a six-by-three-minute
 * workout second for second while prescribing a sixth of the running, which is exactly the easing
 * this rule exists to refuse. The warm-up/cool-down envelope is the workout's either way, so it
 * cancels out of both.
 */
fun WorkoutTemplate.clearedBy(runSeconds: Int, walkSeconds: Int, repeats: Int): Boolean {
    val proposedTotal = (runSeconds.toLong() + walkSeconds.toLong()) * repeats.toLong()
    val proposedRunning = runSeconds.toLong() * repeats.toLong()
    val plannedTotal =
        (runDurationSeconds.toLong() + walkDurationSeconds.toLong()) * totalRepeats.toLong()
    val plannedRunning = runDurationSeconds.toLong() * totalRepeats.toLong()
    return proposedTotal >= plannedTotal && proposedRunning >= plannedRunning
}

/**
 * Today's workout as it will actually be run: the base workout with the AI coach's prescription
 * applied. One home for that rule (#111), because the record-screen card promises the numbers you
 * are about to run — a card adapting on a looser condition than the service would show a shape the
 * run never takes.
 *
 * Only this workout's own Run Type is asked for (#175). The coach holds a prescription per type, and
 * a Long Run's intervals dropped onto a stride session would be the plan rewritten into something
 * nobody wrote — so the type match is the lookup itself rather than a check that could be skipped.
 *
 * A prescription carries all four fields together, so there is no half-applied one to guard
 * against, and a stale one applies nothing. Identity, title and the warm-up/cool-down
 * envelope stay the plan's — the coach prescribes work, not the whole workout (#113).
 *
 * One that asks for less work than this workout applies nothing either. The coach's write is
 * already held to that floor (#170), but a prescription stands for a fortnight and the plan's own
 * numbers can change underneath it — as they do the moment stage 1's workouts are rewritten (#173).
 * Asking again here is what stops a prescription floored at a workout that no longer exists from
 * quietly easing the one that replaced it.
 *
 * No testing-mode branch: testing mode erases the prescription and blocks the coach from writing
 * one, so under it there is simply nothing here to apply.
 */
fun WorkoutTemplate.withCoachPrescription(
    prescriptions: CoachPrescriptions,
    nowEpochMillis: Long
): WorkoutTemplate {
    val prescription = prescriptions[runType]
    if (prescription == null || !prescription.isFreshAt(nowEpochMillis)) return this
    val clearsFloor = clearedBy(
        runSeconds = prescription.runDurationSeconds,
        walkSeconds = prescription.walkDurationSeconds,
        repeats = prescription.totalRepeats
    )
    if (!clearsFloor) return this
    return copy(
        targetZone = prescription.targetZone,
        runDurationSeconds = prescription.runDurationSeconds,
        walkDurationSeconds = prescription.walkDurationSeconds,
        totalRepeats = prescription.totalRepeats
    )
}

/**
 * Today's Workout out of the ones a Stage offers: the one picked, or the first until one is (#174).
 *
 * One home for the rule, because the card and the Run both ask it — and a card promising a Workout
 * the Run then resolved differently is exactly the split that [WorkoutTemplate.withCoachPrescription]
 * exists to prevent. Never null while the Stage has any Workout: an id naming nothing is a pick that
 * outlived the Stage it was made in, not an instruction to run nothing.
 */
fun List<WorkoutTemplate>.pickedOrFirst(workoutId: String?): WorkoutTemplate? =
    firstOrNull { it.id == workoutId } ?: firstOrNull()

object TrainingPlanProvider {
    val plans = listOf(
        TrainingPlan(
            id = "5k_sub_25",
            name = "5K to Sub-25 Progressive Plan",
            description = "A progressive plan designed to take you from completing a 5K to breaking the 25-minute barrier through zone-based conditioning.",
            stages = listOf(
                PlanStage(
                    id = "base_builder",
                    title = "Stage 1: Base Builder",
                    description = "Focus on building aerobic capacity and consistency.",
                    graduationRequirementText = "Complete 4 weeks of consistent Zone 2 training.",
                    isLocked = false,
                    // One Workout of each Run Type (#173) — the week lives inside the stage, and the
                    // runner picks which of the three they are doing today.
                    workouts = listOf(
                        WorkoutTemplate(
                            id = "w1_long",
                            title = "Endurance Walk-Run",
                            targetZone = 2,
                            runDurationSeconds = 600,
                            walkDurationSeconds = 120,
                            totalRepeats = 3,
                            warmUpSeconds = 480,
                            coolDownSeconds = 180,
                            runType = RunType.LONG
                        ),
                        // Continuous, because nothing else in this stage builds toward stage 2's
                        // graduation, which is a 30-minute run with no walks.
                        WorkoutTemplate(
                            id = "w1_easy",
                            title = "Easy Continuous",
                            targetZone = 2,
                            runDurationSeconds = 1200,
                            walkDurationSeconds = 0,
                            totalRepeats = 1,
                            warmUpSeconds = 300,
                            coolDownSeconds = 180,
                            runType = RunType.EASY
                        ),
                        // The 20-minute easy stretch is expressed as the warm-up, which the run
                        // already treats as a phase where the coach is silent. "Full recovery"
                        // between strides is a fixed 90s walk — deliberately a constant and not a
                        // condition, so the run's state machine gains no new kind of interval.
                        WorkoutTemplate(
                            id = "w1_quality",
                            title = "Strides",
                            targetZone = 2,
                            runDurationSeconds = 20,
                            walkDurationSeconds = 90,
                            totalRepeats = 6,
                            warmUpSeconds = 1200,
                            coolDownSeconds = 180,
                            runType = RunType.QUALITY
                        )
                    )
                ),
                PlanStage(
                    id = "sub_30_bridge",
                    title = "Stage 2: Sub-30 Bridge",
                    description = "Bridging the gap to sustained running at higher intensities.",
                    graduationRequirementText = "Successfully complete a 5K under 30 minutes.",
                    isLocked = true,
                    workouts = listOf(
                        WorkoutTemplate("w2_s1", "Pace Stabilization", 2, 600, 60, 4, runType = RunType.LONG),
                        WorkoutTemplate("w2_s2", "The 30-Minute Run", 2, 1800, 0, 1, runType = RunType.EASY)
                    )
                ),
                PlanStage(
                    id = "sub_25_peak",
                    title = "Stage 3: Sub-25 Peak",
                    description = "Fine-tuning threshold and speed for performance.",
                    graduationRequirementText = "Run a 5K in 24:59 or faster.",
                    isLocked = true,
                    workouts = listOf(
                        // Both hard days, and neither is an endurance run — so this stage offers no
                        // Long run, and nothing here is a session the coach adjusts.
                        WorkoutTemplate("w3_s1", "Threshold Intervals", 4, 300, 120, 5, runType = RunType.QUALITY),
                        WorkoutTemplate("w3_s2", "5K Peak Test", 4, 1500, 0, 1, runType = RunType.QUALITY)
                    )
                )
            )
        ),
        TrainingPlan(
            id = "desk_test_plan",
            name = "Desk Test Plan",
            description = "Temporary short-interval plan for desk validation of run/walk transitions.",
            stages = listOf(
                PlanStage(
                    id = "desk_test_stage",
                    title = "Stage 1: Desk Test Stage",
                    description = "Quick verification stage for interval state machine behavior.",
                    graduationRequirementText = "Complete 2 short run/walk repeats.",
                    isLocked = false,
                    workouts = listOf(
                        WorkoutTemplate(
                            id = "desk_test_workout",
                            title = "10s Run / 10s Walk Test",
                            targetZone = 2,
                            runDurationSeconds = 10,
                            walkDurationSeconds = 10,
                            totalRepeats = 2,
                            warmUpSeconds = 0,
                            coolDownSeconds = 0,
                            runType = RunType.LONG
                        )
                    )
                )
            )
        )
    )

    fun getAllPlans(): List<TrainingPlan> = plans
    fun getPlanById(id: String) = plans.find { it.id == id }

    /**
     * Everything a stage offers: all of its workouts, one per Run Type, in the order the plan
     * declares them (#173). Falls back to the plan's first stage, and is empty when no plan is
     * attached. One home for the plan → stage walk that the service, the card and analytics need.
     *
     * All of them rather than the first, because the other two were never dead data — only
     * unreachable. Which of them today's run uses is the runner's choice, not this function's.
     */
    fun resolveStageWorkouts(planId: String?, stageId: String?): List<WorkoutTemplate> {
        val plan = planId?.let { getPlanById(it) } ?: return emptyList()
        val stage = plan.stages.firstOrNull { it.id == stageId } ?: plan.stages.firstOrNull()
        return stage?.workouts.orEmpty()
    }

    /**
     * The Workout the runner picked as today's Run (#174), or the stage's first when they have not
     * picked — which in stage 1 is its Long run. Returns null when no plan is attached (#107).
     *
     * A [workoutId] the stage does not offer falls back the same way rather than detaching the plan
     * — see [pickedOrFirst], which is the rule the card asks too.
     */
    fun resolvePickedWorkout(
        planId: String?,
        stageId: String?,
        workoutId: String?
    ): WorkoutTemplate? = resolveStageWorkouts(planId, stageId).pickedOrFirst(workoutId)

    /**
     * The Stage's own Workout of one kind — the Prescription floor and the envelope the coach
     * reasons inside (#176).
     *
     * Of that kind rather than the Stage's first, which is what this replaced. The coach evaluates
     * the Run just finished, so the Workout it must be held to is the one of that Run's own kind; in
     * stage 1 the first Workout is the Long run, so "first" would have floored every kind at it.
     *
     * Null when the Stage offers nothing of that kind — stage 3 offers no Long run — or when no plan
     * is attached. Not "the nearest Workout": a Prescription reasoned about a Long Run and floored at
     * a stride session is a shape nobody wrote.
     */
    fun resolveWorkoutOfType(
        planId: String?,
        stageId: String?,
        runType: RunType
    ): WorkoutTemplate? = resolveStageWorkouts(planId, stageId).firstOrNull { it.runType == runType }
}
