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
 * Today's workout as it will actually be run: the base workout with the AI coach's prescription
 * applied. One home for that rule (#111), because the record-screen card promises the numbers you
 * are about to run — a card adapting on a looser condition than the service would show a shape the
 * run never takes.
 *
 * A prescription carries all four fields together, so there is no half-applied one to guard
 * against, and a stale one applies nothing. Identity, title and the warm-up/cool-down
 * envelope stay the plan's — the coach prescribes work, not the whole workout (#113).
 *
 * No testing-mode branch: testing mode erases the prescription and blocks the coach from writing
 * one, so under it there is simply nothing here to apply.
 */
fun WorkoutTemplate.withCoachPrescription(
    prescription: CoachPrescription?,
    nowEpochMillis: Long
): WorkoutTemplate {
    if (prescription == null || !prescription.isFreshAt(nowEpochMillis)) return this
    return copy(
        targetZone = prescription.targetZone,
        runDurationSeconds = prescription.runDurationSeconds,
        walkDurationSeconds = prescription.walkDurationSeconds,
        totalRepeats = prescription.totalRepeats
    )
}

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
     * The single workout the app still queues on its own, until the runner does the picking: the
     * stage's first, which in stage 1 is its Long run. Returns null when no plan is attached (#107).
     */
    fun resolveBaseWorkout(planId: String?, stageId: String?): WorkoutTemplate? =
        resolveStageWorkouts(planId, stageId).firstOrNull()
}
