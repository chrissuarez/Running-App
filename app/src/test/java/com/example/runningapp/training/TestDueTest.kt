package com.example.runningapp.training

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The acceptance criteria for #292: when the card says a 5K Test is due. */
class TestDueTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val today: LocalDate = LocalDate.of(2026, 8, 14)

    /** Midday on [daysAgo] days ago, which is safely inside its own calendar day in [zone]. */
    private fun daysAgo(daysAgo: Long): Long =
        today.minusDays(daysAgo).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun due(
        lastTestStartedAtMillis: Long?,
        form: Double? = 0.0,
    ) = testIsDue(lastTestStartedAtMillis, form, today, zone)

    @Test
    fun `a runner who has never tested is due one`() {
        assertTrue(due(lastTestStartedAtMillis = null))
    }

    @Test
    fun `a test three weeks ago is due again`() {
        assertTrue(due(daysAgo(21)))
    }

    @Test
    fun `a test twenty days ago is not due yet`() {
        assertFalse(due(daysAgo(20)))
    }

    @Test
    fun `a test long past is due`() {
        assertFalse(due(daysAgo(1)))
        assertTrue(due(daysAgo(90)))
    }

    @Test
    fun `a fatigued runner is not prompted, however long it has been`() {
        // Below −10 the number would measure the fatigue rather than the fitness, and under
        // ADR 0016 that number graduates a Stage.
        assertFalse(due(daysAgo(90), form = -10.1))
        assertFalse(due(lastTestStartedAtMillis = null, form = -40.0))
    }

    @Test
    fun `neutral and fresh Form both let the prompt through`() {
        assertTrue(due(daysAgo(90), form = -10.0))
        assertTrue(due(daysAgo(90), form = 25.0))
    }

    @Test
    fun `no curve at all holds nothing back`() {
        // Nothing says the runner is tired, so the prompt is not held on a fact nobody has.
        assertTrue(due(daysAgo(90), form = null))
    }

    @Test
    fun `the three weeks are counted in the runner's own calendar days`() {
        // A Test late on its day and a prompt early on the day three weeks later are 21 days
        // apart to the runner, whatever the clock reading between them says.
        val lateOnTheDay = today.minusDays(21).atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        assertTrue(due(lateOnTheDay))
    }
}
