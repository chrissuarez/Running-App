package com.example.runningapp.training

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The acceptance criteria for #292: when the card says a 5K Test is due. */
class TestDueTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 14)

    /** The day [daysAgo] days before today — the day the Run itself says it happened on. */
    private fun daysAgo(daysAgo: Long): LocalDate = today.minusDays(daysAgo)

    private fun due(
        lastTestRanOn: LocalDate?,
        form: Double? = 0.0,
    ) = testIsDue(lastTestRanOn, form, today)

    @Test
    fun `a runner who has never tested is due one`() {
        assertTrue(due(lastTestRanOn = null))
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
        assertFalse(due(lastTestRanOn = null, form = -40.0))
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
    fun `the three weeks are counted in whole calendar days`() {
        // A Test late on its day and a prompt early on the day three weeks later are 21 days apart
        // to the runner, whatever the clock reading between them says. Which day the Test was on is
        // the Run's own fact and is not worked out here any more (#304) — see RunDayTest.
        assertTrue(due(daysAgo(21)))
        assertFalse(due(daysAgo(20)))
    }
}
