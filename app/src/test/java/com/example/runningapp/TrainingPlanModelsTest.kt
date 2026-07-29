package com.example.runningapp

import org.junit.Assert.assertEquals
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

    private fun prescription(
        targetZone: Int = 2,
        run: Int = 240,
        walk: Int = 90,
        repeats: Int = 4,
        prescribedAt: Long = now
    ) = CoachPrescription(targetZone, run, walk, repeats, prescribedAt)

    private fun daysBefore(days: Long) = now - days * 24L * 60L * 60L * 1000L

    @Test
    fun `with no prescription the base workout is what runs`() {
        assertSame(base, base.withCoachPrescription(null, now))
    }

    @Test
    fun `a prescription replaces target, run, walk and repeats`() {
        val adapted = base.withCoachPrescription(prescription(targetZone = 3), now)
        assertEquals(3, adapted.targetZone)
        assertEquals(240, adapted.runDurationSeconds)
        assertEquals(90, adapted.walkDurationSeconds)
        assertEquals(4, adapted.totalRepeats)
    }

    @Test
    fun `identity and the envelope stay the plan's — the coach prescribes work, not the workout`() {
        val adapted = base.withCoachPrescription(prescription(), now)
        assertEquals(base.id, adapted.id)
        assertEquals(base.title, adapted.title)
        assertEquals(base.warmUpSeconds, adapted.warmUpSeconds)
        assertEquals(base.coolDownSeconds, adapted.coolDownSeconds)
    }

    @Test
    fun `a prescription still applies days later, since runs are days apart`() {
        val adapted = base.withCoachPrescription(prescription(prescribedAt = daysBefore(5)), now)
        assertEquals(240, adapted.runDurationSeconds)
    }

    @Test
    fun `a prescription older than the cutoff is not applied`() {
        val stale = prescription(prescribedAt = daysBefore(COACH_PRESCRIPTION_MAX_AGE_DAYS + 1L))
        assertSame(base, base.withCoachPrescription(stale, now))
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
    fun `stages and graduation are untouched`() {
        val stages = TrainingPlanProvider.getPlanById("5k_sub_25")!!.stages
        assertEquals(listOf("base_builder", "sub_30_bridge", "sub_25_peak"), stages.map { it.id })
        assertEquals("Complete 4 weeks of consistent Zone 2 training.", stages[0].graduationRequirementText)
        assertEquals(listOf(false, true, true), stages.map { it.isLocked })
    }
}
