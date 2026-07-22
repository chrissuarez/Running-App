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

    private val base = WorkoutTemplate("w1_s2", "Aerobic Foundation", 2, 300, 60, 5)
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
        assertSame(base, base.withCoachAdaptation(null, now))
    }

    @Test
    fun `a prescription replaces target, run, walk and repeats`() {
        val adapted = base.withCoachAdaptation(prescription(targetZone = 3), now)
        assertEquals(3, adapted.targetZone)
        assertEquals(240, adapted.runDurationSeconds)
        assertEquals(90, adapted.walkDurationSeconds)
        assertEquals(4, adapted.totalRepeats)
    }

    @Test
    fun `identity and the envelope stay the plan's — the coach prescribes work, not the workout`() {
        val adapted = base.withCoachAdaptation(prescription(), now)
        assertEquals(base.id, adapted.id)
        assertEquals(base.title, adapted.title)
        assertEquals(base.warmUpSeconds, adapted.warmUpSeconds)
        assertEquals(base.coolDownSeconds, adapted.coolDownSeconds)
    }

    @Test
    fun `a prescription still applies days later, since runs are days apart`() {
        val adapted = base.withCoachAdaptation(prescription(prescribedAt = daysBefore(5)), now)
        assertEquals(240, adapted.runDurationSeconds)
    }

    @Test
    fun `a prescription older than the cutoff is not applied`() {
        val stale = prescription(prescribedAt = daysBefore(COACH_PRESCRIPTION_MAX_AGE_DAYS + 1L))
        assertSame(base, base.withCoachAdaptation(stale, now))
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
}
