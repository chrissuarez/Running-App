package com.example.runningapp

import com.example.runningapp.analysis.RecordType
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card and the run must resolve today's workout the same way (#111) — one function, so the
 * screen can never show numbers the run won't use.
 */
class TrainingPlanModelsTest {

    private val base =
        WorkoutTemplate("w1_s2", "Aerobic Foundation", 2, 300, 60, 5, runType = RunType.LONG)
    private val now = 1_700_000_000_000L
    private val sub25Plan = TrainingPlanProvider.getPlanById("5k_sub_25")!!

    // More work than [base], since a prescription asking for less applies nothing (#170).
    private fun prescription(
        targetZone: Int = 2,
        run: Int = 360,
        walk: Int = 90,
        repeats: Int = 5,
        prescribedAt: Long = now
    ) = CoachPrescription(targetZone, run, walk, repeats, prescribedAt)

    /** What the coach has standing, with [prescription] in [base]'s own slot (#175). */
    private fun standingFor(
        prescription: CoachPrescription,
        runType: RunType = RunType.LONG
    ) = CoachPrescriptions(mapOf(runType to prescription))

    private fun daysBefore(days: Long) = now - days * 24L * 60L * 60L * 1000L

    @Test
    fun `with no prescription the base workout is what runs`() {
        assertSame(base, base.withCoachPrescription(CoachPrescriptions.NONE, now))
    }

    @Test
    fun `a prescription for another run type applies nothing to this one`() {
        // The reason the slots exist (#175): ten-minute run Intervals written for the Long Run must
        // never reshape a session built from twenty-second strides.
        val forTheEasyRun = standingFor(prescription(), runType = RunType.EASY)

        assertSame(base, base.withCoachPrescription(forTheEasyRun, now))
    }

    @Test
    fun `two slots at once each move their own workout and no other`() {
        val long = workoutOf(RunType.LONG)
        val quality = workoutOf(RunType.QUALITY)
        val standing = CoachPrescriptions(
            mapOf(
                RunType.LONG to prescription(run = 660, walk = 120, repeats = 3),
                RunType.QUALITY to prescription(run = 25, walk = 90, repeats = 6)
            )
        )

        assertEquals(660, long.withCoachPrescription(standing, now).runDurationSeconds)
        assertEquals(25, quality.withCoachPrescription(standing, now).runDurationSeconds)
        // No slot of its own, so the plan's numbers stand.
        assertSame(workoutOf(RunType.EASY), workoutOf(RunType.EASY).withCoachPrescription(standing, now))
    }

    @Test
    fun `a prescription replaces target, run, walk and repeats`() {
        val adapted = base.withCoachPrescription(standingFor(prescription(targetZone = 3)), now)
        assertEquals(3, adapted.targetZone)
        assertEquals(360, adapted.runDurationSeconds)
        assertEquals(90, adapted.walkDurationSeconds)
        assertEquals(5, adapted.totalRepeats)
    }

    @Test
    fun `identity and the envelope stay the plan's — the coach prescribes work, not the workout`() {
        val adapted = base.withCoachPrescription(standingFor(prescription()), now)
        assertEquals(base.id, adapted.id)
        assertEquals(base.title, adapted.title)
        assertEquals(base.warmUpSeconds, adapted.warmUpSeconds)
        assertEquals(base.coolDownSeconds, adapted.coolDownSeconds)
    }

    @Test
    fun `a prescription still applies days later, since runs are days apart`() {
        val adapted = base.withCoachPrescription(standingFor(prescription(prescribedAt = daysBefore(5))), now)
        assertEquals(360, adapted.runDurationSeconds)
    }

    @Test
    fun `a prescription asking for less work than the workout applies nothing`() {
        // Written against a workout this stage no longer offers — the plan's own numbers changed
        // under it (#173). The floor is the same rule the coach's write is held to (#170), asked
        // again here because a standing prescription outlives the workout it was floored at.
        val fromAnEarlierPlan = prescription(run = 180, walk = 60, repeats = 6)

        assertSame(base, base.withCoachPrescription(standingFor(fromAnEarlierPlan), now))
    }

    @Test
    fun `a prescription padded with walks is refused on its running seconds`() {
        // 6 x (60s run + 240s walk) is longer than 5 x (300s run + 60s walk) end to end while
        // prescribing a fifth of the running. Total alone would let that through.
        val padded = prescription(run = 60, walk = 240, repeats = 6)

        assertSame(base, base.withCoachPrescription(standingFor(padded), now))
    }

    @Test
    fun `a prescription that clears the workout still applies in full`() {
        val harder = prescription(run = 360, walk = 60, repeats = 5)

        val adapted = base.withCoachPrescription(standingFor(harder), now)

        assertEquals(360, adapted.runDurationSeconds)
        assertEquals(60, adapted.walkDurationSeconds)
        assertEquals(5, adapted.totalRepeats)
    }

    @Test
    fun `a prescription older than the cutoff is not applied`() {
        val stale = prescription(prescribedAt = daysBefore(COACH_PRESCRIPTION_MAX_AGE_DAYS + 1L))
        assertSame(base, base.withCoachPrescription(standingFor(stale), now))
    }

    @Test
    fun `the cutoff day itself still counts as fresh`() {
        val onTheEdge = prescription(prescribedAt = daysBefore(COACH_PRESCRIPTION_MAX_AGE_DAYS.toLong()))
        assertTrue(onTheEdge.isFreshAt(now))
    }

    @Test
    fun `a clock that moved backwards does not throw away a real prescription`() {
        assertTrue(prescription(prescribedAt = now + 60_000).isFreshAt(now))
    }

    // --- Run Types on the plan (#173) ---

    private fun stage1() = TrainingPlanProvider.getPlanById("5k_sub_25")!!.stages.first()

    private fun workoutOf(runType: RunType) = stage1().workouts.single { it.runType == runType }

    /** Warm-up + the whole main set + cool-down: the total a runner reads off the card. */
    private fun WorkoutTemplate.totalSeconds() =
        warmUpSeconds + (runDurationSeconds + walkDurationSeconds) * totalRepeats + coolDownSeconds

    // That every Workout declares a Run Type is the compiler's to enforce — the field has no
    // default — so there is no test for it here. What needs asserting is which types they declare.

    @Test
    fun `stage 1 offers exactly one workout of each run type`() {
        assertEquals(RunType.entries.toSet(), stage1().workouts.map { it.runType }.toSet())
        assertEquals(3, stage1().workouts.size)
    }

    @Test
    fun `the long run is 47 minutes of 10 on 2 off, three times`() {
        val long = workoutOf(RunType.LONG)
        assertEquals(480, long.warmUpSeconds)
        assertEquals(600, long.runDurationSeconds)
        assertEquals(120, long.walkDurationSeconds)
        assertEquals(3, long.totalRepeats)
        assertEquals(180, long.coolDownSeconds)
        assertEquals(47 * 60, long.totalSeconds())
    }

    @Test
    fun `the easy run is 28 minutes with 20 of them continuous`() {
        val easy = workoutOf(RunType.EASY)
        assertEquals(300, easy.warmUpSeconds)
        assertEquals(1200, easy.runDurationSeconds)
        // One repeat and no walk: the whole point of the session is that it is unbroken.
        assertEquals(0, easy.walkDurationSeconds)
        assertEquals(1, easy.totalRepeats)
        assertEquals(180, easy.coolDownSeconds)
        assertEquals(28 * 60, easy.totalSeconds())
    }

    @Test
    fun `the quality run is six strides after a 20 minute warm-up`() {
        val quality = workoutOf(RunType.QUALITY)
        // The easy stretch is the warm-up, which is where the coach is already silent.
        assertEquals(1200, quality.warmUpSeconds)
        assertEquals(20, quality.runDurationSeconds)
        // Full recovery is a fixed 90s walk, not a condition — the Run gains no new kind of Interval.
        assertEquals(90, quality.walkDurationSeconds)
        assertEquals(6, quality.totalRepeats)
        assertEquals(180, quality.coolDownSeconds)
        assertEquals(34 * 60, quality.totalSeconds())
    }

    @Test
    fun `resolving a stage's workouts returns all of them, not the first`() {
        assertEquals(
            stage1().workouts,
            TrainingPlanProvider.resolveStageWorkouts("5k_sub_25", "base_builder")
        )
    }

    @Test
    fun `resolving the workouts of no plan, or of a stage the plan does not have`() {
        assertEquals(emptyList<WorkoutTemplate>(), TrainingPlanProvider.resolveStageWorkouts(null, "base_builder"))
        // An unknown stage falls back to the plan's first, as the single-workout walk always has.
        assertEquals(
            stage1().workouts,
            TrainingPlanProvider.resolveStageWorkouts("5k_sub_25", "no_such_stage")
        )
    }

    @Test
    fun `the Stage the runner is in is the one the setting names`() {
        assertEquals(
            "sub_30_bridge",
            TrainingPlanProvider.resolveActiveStage("5k_sub_25", "sub_30_bridge")?.id
        )
    }

    @Test
    fun `a plan naming no Stage, or one it does not hold, is in its first`() {
        // What the Run is stamped with (#234) has to be the Stage its Workout came from, so both
        // fall back where the Workout does rather than being written down as they are found.
        assertEquals("base_builder", TrainingPlanProvider.resolveActiveStage("5k_sub_25", null)?.id)
        assertEquals(
            "base_builder",
            TrainingPlanProvider.resolveActiveStage("5k_sub_25", "no_such_stage")?.id
        )
    }

    @Test
    fun `no plan attached is no Stage at all, whatever the setting says`() {
        // A Run with no plan records no Stage, and answers no Stage's requirement.
        assertNull(TrainingPlanProvider.resolveActiveStage(null, null))
        assertNull(TrainingPlanProvider.resolveActiveStage(null, "base_builder"))
        assertNull(TrainingPlanProvider.resolveActiveStage("no_such_plan", "base_builder"))
    }

    @Test
    fun `the picked workout is the one today's run uses`() {
        assertEquals(
            "w1_quality",
            TrainingPlanProvider.resolvePickedWorkout("5k_sub_25", "base_builder", "w1_quality")?.id
        )
    }

    @Test
    fun `no pick, or one the stage does not offer, falls back to the stage's first`() {
        val first = stage1().workouts.first().id
        assertEquals(
            first,
            TrainingPlanProvider.resolvePickedWorkout("5k_sub_25", "base_builder", null)?.id
        )
        assertEquals(
            first,
            TrainingPlanProvider.resolvePickedWorkout("5k_sub_25", "base_builder", "w2_s1")?.id
        )
        assertNull(TrainingPlanProvider.resolvePickedWorkout(null, "base_builder", "w1_long"))
    }

    @Test
    fun `the coach adjusts the Long Run and nothing else`() {
        // The one gate (#176). Easy is fixed at its continuous stretch and Quality at its strides;
        // both are recorded in full and simply not adjusted.
        assertTrue(RunType.LONG.isCoachAdjusted)
        assertFalse(RunType.EASY.isCoachAdjusted)
        assertFalse(RunType.QUALITY.isCoachAdjusted)
    }

    @Test
    fun `a stage's workout of a run type is the one of that kind, not its first`() {
        // The prescription floor is per Run Type (#176), so this cannot be "the stage's first
        // workout" — in stage 1 that is the Long run, and it would floor every kind at it.
        assertEquals(
            "w1_easy",
            TrainingPlanProvider.resolveWorkoutOfType("5k_sub_25", "base_builder", RunType.EASY)?.id
        )
        assertEquals(
            "w1_quality",
            TrainingPlanProvider.resolveWorkoutOfType("5k_sub_25", "base_builder", RunType.QUALITY)?.id
        )
        assertEquals(
            "w1_long",
            TrainingPlanProvider.resolveWorkoutOfType("5k_sub_25", "base_builder", RunType.LONG)?.id
        )
    }

    @Test
    fun `a stage that offers no workout of a run type has none`() {
        // Stage 3 is two hard days and no endurance run, so there is nothing there to floor a Long
        // prescription at — and inventing one out of a Quality workout is the mistake #176 refuses.
        assertNull(TrainingPlanProvider.resolveWorkoutOfType("5k_sub_25", "sub_25_peak", RunType.LONG))
        assertNull(TrainingPlanProvider.resolveWorkoutOfType(null, "base_builder", RunType.LONG))
    }

    @Test
    fun `stages and graduation are untouched`() {
        val stages = TrainingPlanProvider.getPlanById("5k_sub_25")!!.stages
        assertEquals(listOf("base_builder", "sub_30_bridge", "sub_25_peak"), stages.map { it.id })
        assertEquals("Complete 4 weeks of consistent Zone 2 training.", stages[0].graduationRequirementText)
    }

    // --- Which Stages are locked (#301) ---------------------------------------------------------

    @Test
    fun `the stage the runner is in is never locked, and neither is one they have left`() {
        // The whole of #301: staged into Stage 2, the padlock is on Stage 3 and nowhere else. Under
        // the static flag it sat on Stage 2 as well — on the card the runner was training in.
        assertEquals(setOf("sub_25_peak"), sub25Plan.lockedStageIds("sub_30_bridge"))
        // And graduating clears it, which nothing did before, because nothing ever wrote the flag.
        assertEquals(emptySet<String>(), sub25Plan.lockedStageIds("sub_25_peak"))
    }

    @Test
    fun `stages after the first are locked while the runner is in the first`() {
        assertEquals(setOf("sub_30_bridge", "sub_25_peak"), sub25Plan.lockedStageIds("base_builder"))
    }

    @Test
    fun `a plan the runner is in no stage of stands at its first stage`() {
        // The same walk resolveActiveStage makes, so an unactivated plan reads the way it always
        // did — first Stage open, everything past it shut — and a stale id names no Stage rather
        // than locking the lot.
        assertEquals(setOf("sub_30_bridge", "sub_25_peak"), sub25Plan.lockedStageIds(null))
        assertEquals(setOf("sub_30_bridge", "sub_25_peak"), sub25Plan.lockedStageIds("desk_test_stage"))
    }

    // --- Requirements written in numbers, and the tests that answer them (#290, #291) ----------

    @Test
    fun `only the two 5K stages state their requirement in numbers`() {
        val stages = TrainingPlanProvider.getPlanById("5k_sub_25")!!.stages

        // "4 weeks of consistent Zone 2 training" holds a genuine judgement and stays the coach's.
        assertNull(stages[0].bestEffortRequirement)
        // And the two that are arithmetic say so, at the numbers their own prose states: under 30
        // minutes is 1799, and 24:59 or faster is 1499.
        assertEquals(
            listOf(
                BestEffortRequirement(RecordType.FASTEST_5K, 1_799),
                BestEffortRequirement(RecordType.FASTEST_5K, 1_499),
            ),
            stages.drop(1).map { it.bestEffortRequirement }
        )
    }

    @Test
    fun `a requirement can only be stated at a distance a Best Effort is run over`() {
        // The two records that ask how *much* was done are measured in metres and in the Run's own
        // clock, so a bar of "1799 seconds" against either compares a time to something that is not
        // one — silently, inside a rule that grants a promotion (#293).
        RecordType.entries.filter { it.distanceMeters == null }.forEach { record ->
            assertThrows(IllegalArgumentException::class.java) {
                BestEffortRequirement(record, 1_799)
            }
        }
        RecordType.bestEffortDistances.forEach { BestEffortRequirement(it, 1_799) }
    }

    @Test
    fun `every stage that graduates on a 5K offers a way to run one`() {
        // A rule with no test to attempt leaves stage 2 as unreachable as it was (#291).
        val stages = TrainingPlanProvider.getPlanById("5k_sub_25")!!.stages

        stages.filter { it.bestEffortRequirement != null }.forEach { stage ->
            val test = stage.workouts.single { it.title.endsWith("Test") }
            // Continuous and no walk: a walk break inside a Best Effort counts against it.
            assertEquals(0, test.walkDurationSeconds)
            assertEquals(1, test.totalRepeats)
            assertEquals(RunType.QUALITY, test.runType)
            // And no envelope, so the treadmill console reads 5.00 km at the end.
            assertEquals(0, test.warmUpSeconds)
            assertEquals(0, test.coolDownSeconds)
            // Which is a decision the numbers cannot show, so it is said in words.
            assertEquals(FIVE_K_TEST_INSTRUCTION, test.instruction)
        }
    }

    @Test
    fun `stage 1 is offered no test, and its workouts keep their envelopes`() {
        // A test there measures a runner still on walk breaks against a bar two stages away: the
        // number would be discouraging and useless (#291).
        val stageOne = TrainingPlanProvider.getPlanById("5k_sub_25")!!.stages.first()

        assertTrue(stageOne.workouts.none { it.instruction != null })
        assertTrue(stageOne.workouts.all { it.warmUpSeconds > 0 })
    }

    @Test
    fun `every stage that graduates on a 5K names its Test`() {
        // The prompt counts three weeks from the last Run of the Stage's Test (#292), so a Stage
        // whose Test is unnamed is one whose prompt can never fire.
        val stages = TrainingPlanProvider.getPlanById("5k_sub_25")!!.stages

        stages.filter { it.bestEffortRequirement != null }.forEach { stage ->
            val test = stage.testWorkout
            assertNotNull("${stage.id} names no Test", test)
            assertEquals(stage.workouts.single { it.title.endsWith("Test") }, test)
            // One Test per Stage: a second would be two ways of measuring the same bar.
            assertEquals(1, stage.workouts.count { it.isTest })
        }
    }

    @Test
    fun `a stage whose requirement is a judgement has no Test`() {
        val stageOne = TrainingPlanProvider.getPlanById("5k_sub_25")!!.stages.first()

        assertNull(stageOne.testWorkout)
    }
}
