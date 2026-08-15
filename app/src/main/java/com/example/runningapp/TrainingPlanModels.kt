package com.example.runningapp

import com.example.runningapp.analysis.RecordType

data class TrainingPlan(
    val id: String,
    val name: String,
    val description: String,
    val stages: List<PlanStage>
)

/**
 * A Stage requirement written in numbers rather than left to a judgement: a Best Effort at one
 * record distance, in [withinSeconds] or faster (#290).
 *
 * "Is this 5K under 30 minutes" is arithmetic on a number the app already measures, and the app is
 * what answers it — see
 * [ADR 0016](docs/adr/0016-a-requirement-stated-in-numbers-is-not-the-coachs-to-judge.md). The
 * coach is fenced out of a Stage that carries one; the prose in [PlanStage.graduationRequirementText]
 * stays, for the screen and for the coach's debrief.
 *
 * A Best Effort and not a bare measurement, which is the whole of why this is safe: the app already
 * knows what one Run is worth at a record distance
 * ([com.example.runningapp.analysis.bestEffortsOf]), measured off the track or stated off a treadmill
 * console (ADR 0015), never for a Walk, and never for a Run still going. Those are exactly the three
 * edges this rule needs, so it borrows them rather than restating them and drifting.
 *
 * Not every requirement can have one. "4 weeks of consistent Zone 2 training" holds a genuine
 * judgement — what counts as consistent — and stays with the coach.
 */
data class BestEffortRequirement(
    /** One of the five distances contested as a Best Effort — [RecordType.bestEffortDistances]. */
    val record: RecordType,
    /**
     * The slowest time that still clears the bar, inclusive and in whole seconds — so "a 5K under
     * 30 minutes" is 1799 and "24:59 or faster" is 1499.
     *
     * Inclusive because a Best Effort is kept in whole seconds and a runner reads the bar off a
     * clock: 29:59 is under thirty minutes and 25:00 is not 24:59. Stated as the number that passes
     * rather than as the number in the prose, so there is one comparison here and no polarity to
     * get wrong at the call site.
     */
    val withinSeconds: Int
) {
    init {
        // A time at a set distance, and only that. The two records asked how *much* was done rather
        // than how quickly are measured in metres and in the Run's own clock, and a bar of
        // "1799 seconds" against either would be comparing a time to something that is not one —
        // silently, and in a rule that grants a promotion. A duration requirement or a distance one
        // is a new kind of requirement with its own comparison, not this one with a different enum
        // (ADR 0016).
        require(record.distanceMeters != null) {
            "A Best Effort requirement is a time at a set distance; ${record.name} is not one"
        }
    }
}

/**
 * The distance a Requirement is written at, as a sentence names it: "5 km", not "Fastest 5 km".
 *
 * One home for it, because three sentences say it — the bar already beaten (#293), the graduation
 * that grants, and the plan that is complete (#294) — and a record renamed in the book must not
 * leave two of them saying one thing and the third another.
 */
val BestEffortRequirement.distanceLabel: String get() = record.label.removePrefix("Fastest ")

data class PlanStage(
    val id: String,
    val title: String,
    val description: String,
    val graduationRequirementText: String,
    /**
     * The same requirement in numbers, where it can be written in them (#290). Null where the
     * requirement holds a judgement, which leaves it with the coach exactly as before.
     */
    val bestEffortRequirement: BestEffortRequirement? = null,
    val isLocked: Boolean = true,
    val workouts: List<WorkoutTemplate>
)

/**
 * The Stage's Test, or null for a Stage that offers none (#292).
 *
 * The first one declared, because a Stage has one requirement and so has one Test; a second would
 * be two ways of measuring the same bar, and the three-week prompt would then be counting from
 * whichever of them was run last while claiming to count from "the last test".
 */
val PlanStage.testWorkout: WorkoutTemplate?
    get() = workouts.firstOrNull { it.isTest }

/**
 * Every Test the plan holds, in Stage order — what "when did the runner last test" is asked of
 * (#292).
 *
 * The whole plan rather than one Stage, because a Test is the same 5 km flat out whichever Stage
 * offered it. Asked a Stage at a time, the Test that graduated a Stage would stop counting the
 * moment it succeeded, and the new Stage would ask for another the same afternoon.
 *
 * The Workouts and not just their ids: how long a Run had to last to have been one of them is the
 * Test's own length ([com.example.runningapp.training.wasRunFarEnough]), so the answer travels with
 * the question.
 */
val TrainingPlan.tests: List<PlanTest>
    get() = stages.mapNotNull { stage ->
        stage.testWorkout?.let { PlanTest(it, stage.bestEffortRequirement?.record) }
    }

/**
 * A Test and the distance it exists to cover — the Stage's Requirement, or null for a Stage whose
 * Requirement is not written in a Best Effort (#292).
 *
 * The two travel together because deciding whether a Run *was* this Test needs both: a Run that
 * covered the distance has taken the test whatever the clock says, and one that did not is judged
 * on how much of the Workout it ran ([com.example.runningapp.training.wasRunFarEnough]).
 */
data class PlanTest(
    val workout: WorkoutTemplate,
    val distance: RecordType?,
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
    val runType: RunType,
    /**
     * One line of instruction for the runner, shown on the Today card (#291) — null for a Workout
     * whose numbers say the whole of it.
     *
     * It exists because a Workout can carry a decision its numbers do not show. A 5K Test has no
     * warm-up inside it *on purpose*, so that the treadmill console reads 5.00 km at the end and the
     * stated distance and the stated Best Effort agree; without a line saying "warm up before you
     * start this", the runner presses START cold and the decision is invisible.
     */
    val instruction: String? = null,
    /**
     * Whether this Workout is the Stage's Test — the one that exists to answer a requirement
     * written in numbers (#292).
     *
     * Declared rather than inferred. A Stage's Test could be guessed at from its shape — no
     * envelope, one continuous repeat, a hard target zone — but every one of those is a coincidence
     * waiting to happen, and what a Test is for is not a fact about its numbers: it is the Workout
     * whose Run is *counted* as a test, which is the whole of what the three-week prompt is about
     * ([com.example.runningapp.training.testIsDue]).
     *
     * At most one per Stage — see [PlanStage.testWorkout].
     */
    val isTest: Boolean = false
)

/**
 * The main set: every repeat's run and walk together, and so the whole of the main Phase.
 *
 * The warm-up and cool-down envelope is deliberately not in it — see [clearedBy], which compares
 * two main sets and would have to subtract the envelope back off again.
 */
val WorkoutTemplate.mainSetSeconds: Long
    get() = totalRepeats.toLong() * (runDurationSeconds.toLong() + walkDurationSeconds.toLong())

/**
 * How long this Workout takes door to door: warm-up, main set and cool-down.
 *
 * One home for it, because it is asked for by things that must agree — the card promising "≈ 41
 * min" before the Run and the Run working out where its halfway point is during it (#208).
 */
val WorkoutTemplate.plannedSeconds: Long
    get() = warmUpSeconds.toLong() + mainSetSeconds + coolDownSeconds.toLong()

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
    val plannedTotal = mainSetSeconds
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

/**
 * What a runner has to be told before a 5K Test, because the Workout's numbers do not show it
 * (#291).
 *
 * A 5K Test carries no warm-up and no cool-down, and the reason is the treadmill. A warm-up
 * recorded inside the Run leaves the console reading 6.2 km at the end rather than 5.00, so there is
 * no whole-run 5 km to state and no console split to read — the runner would be subtracting one
 * clock reading from another, mid-Run, at full effort, which is exactly where mistakes live. With no
 * envelope the Run *is* the test: warm up with the app not recording, reset the console, press
 * START. Outdoors it costs nothing either way, because the rolling window finds the effort wherever
 * it falls inside the track.
 *
 * The price is that the warm-up earns no Effort Score — the same price as any jog that is not
 * recorded today.
 */
const val FIVE_K_TEST_INSTRUCTION: String =
    "Warm up for about 10 minutes before you start this — the Run itself is the test, " +
        "so press START only when you are ready to go."

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
                    // 1799 rather than 1800: the prose says *under* thirty minutes (#290).
                    bestEffortRequirement = BestEffortRequirement(RecordType.FASTEST_5K, 1799),
                    isLocked = true,
                    workouts = listOf(
                        WorkoutTemplate("w2_s1", "Pace Stabilization", 2, 600, 60, 4, runType = RunType.LONG),
                        WorkoutTemplate("w2_s2", "The 30-Minute Run", 2, 1800, 0, 1, runType = RunType.EASY),
                        // The stage graduates on a 5K and until now offered no way to attempt one
                        // (#291): its Long run walks a minute in four, and a walk break inside a
                        // Best Effort counts against it, so a sub-30 5K in there needed a pace the
                        // stage does not exist to produce. Continuous, no walk, and no envelope.
                        WorkoutTemplate(
                            id = "w2_s3",
                            title = "5K Test",
                            targetZone = 4,
                            runDurationSeconds = 1800,
                            walkDurationSeconds = 0,
                            totalRepeats = 1,
                            warmUpSeconds = 0,
                            coolDownSeconds = 0,
                            runType = RunType.QUALITY,
                            instruction = FIVE_K_TEST_INSTRUCTION,
                            isTest = true
                        )
                    )
                ),
                PlanStage(
                    id = "sub_25_peak",
                    title = "Stage 3: Sub-25 Peak",
                    description = "Fine-tuning threshold and speed for performance.",
                    graduationRequirementText = "Run a 5K in 24:59 or faster.",
                    bestEffortRequirement = BestEffortRequirement(RecordType.FASTEST_5K, 1499),
                    isLocked = true,
                    workouts = listOf(
                        // Both hard days, and neither is an endurance run — so this stage offers no
                        // Long run, and nothing here is a session the coach adjusts.
                        WorkoutTemplate("w3_s1", "Threshold Intervals", 4, 300, 120, 5, runType = RunType.QUALITY),
                        // The envelope goes, for the reason set out on [FIVE_K_TEST_INSTRUCTION]
                        // (#291): it used to take the 480/180 default, which puts eleven minutes of
                        // jogging inside the Run and leaves the console reading 6.2 km.
                        WorkoutTemplate(
                            id = "w3_s2",
                            title = "5K Peak Test",
                            targetZone = 4,
                            runDurationSeconds = 1500,
                            walkDurationSeconds = 0,
                            totalRepeats = 1,
                            warmUpSeconds = 0,
                            coolDownSeconds = 0,
                            runType = RunType.QUALITY,
                            instruction = FIVE_K_TEST_INSTRUCTION,
                            isTest = true
                        )
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
     * The Stage the runner is in: the one [stageId] names, or the plan's first when it names none
     * or names one this plan does not hold. Null when no plan is attached.
     *
     * The one place that walk is made, because the answer is not private to whoever asked. The card
     * shows this Stage's title, the Run follows its Workouts, and the Run is stamped with it at
     * START (#234) — a Run stamped with a Stage other than the one it was shown and run under is
     * evidence filed against work nobody did.
     */
    fun resolveActiveStage(planId: String?, stageId: String?): PlanStage? {
        val plan = planId?.let { getPlanById(it) } ?: return null
        return plan.stages.firstOrNull { it.id == stageId } ?: plan.stages.firstOrNull()
    }

    /**
     * Everything a stage offers: all of its workouts, one per Run Type, in the order the plan
     * declares them (#173). Empty when no plan is attached.
     *
     * All of them rather than the first, because the other two were never dead data — only
     * unreachable. Which of them today's run uses is the runner's choice, not this function's.
     */
    fun resolveStageWorkouts(planId: String?, stageId: String?): List<WorkoutTemplate> =
        resolveActiveStage(planId, stageId)?.workouts.orEmpty()

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
