package com.example.runningapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The card and the run must resolve today's workout the same way (#111) — one function, so the
 * screen can never show numbers the run won't use.
 */
class TrainingPlanModelsTest {

    private val base = WorkoutTemplate("w1_s2", "Aerobic Foundation", 2, 300, 60, 5)
    private val settings = UserSettings()

    @Test
    fun `with no coach adjustment the base workout is what runs`() {
        assertSame(base, base.withCoachAdaptation(settings))
    }

    @Test
    fun `a complete coach adjustment replaces run, walk and repeats`() {
        val adapted = base.withCoachAdaptation(
            settings.copy(aiRunIntervalSeconds = 240, aiWalkIntervalSeconds = 90, aiRepeats = 4)
        )
        assertEquals(240, adapted.runDurationSeconds)
        assertEquals(90, adapted.walkDurationSeconds)
        assertEquals(4, adapted.totalRepeats)
        // Structure the coach doesn't write is left alone.
        assertEquals(base.warmUpSeconds, adapted.warmUpSeconds)
        assertEquals(base.targetZone, adapted.targetZone)
    }

    @Test
    fun `a partial coach adjustment is ignored rather than half-applied`() {
        assertSame(base, base.withCoachAdaptation(settings.copy(aiRunIntervalSeconds = 240)))
        assertSame(base, base.withCoachAdaptation(settings.copy(aiRunIntervalSeconds = 240, aiRepeats = 4)))
    }

    @Test
    fun `testing mode runs the plan as written`() {
        val adapted = settings.copy(
            aiRunIntervalSeconds = 240,
            aiWalkIntervalSeconds = 90,
            aiRepeats = 4,
            testingModeEnabled = true
        )
        assertSame(base, base.withCoachAdaptation(adapted))
    }
}
