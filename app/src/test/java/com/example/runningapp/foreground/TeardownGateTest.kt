package com.example.runningapp.foreground

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the teardown refuses, and the one thing it does not (#315).
 *
 * The rule is one line, and it is tested because getting it the other way round is a whole Run's
 * seconds rather than a metre: the drains this gate stands in front of are what a Run lost to a
 * teardown is rebuilt from, and the buffer handed over during a teardown is everything a Run that
 * never got its row ever recorded.
 */
class TeardownGateTest {

    @Test
    fun `a live service gives the run work as it always did`() {
        assertTrue(runMayBeGivenWork(teardownBegun = false))
    }

    @Test
    fun `a teardown refuses new work for the run`() {
        // The point of the gate: a producer still alive after a bounded join, or a GPS looper that
        // was never joined at all, is refused rather than raced. That is what makes an empty scope
        // proof rather than an observation.
        assertFalse(runMayBeGivenWork(teardownBegun = true))
    }

    @Test
    fun `a teardown still lets through the finish already under way`() {
        // The teardown's own delivery of a held buffer. Refusing it would cost a real Run its whole
        // recording to fix a rescue's rounding.
        assertTrue(runMayBeGivenWork(teardownBegun = true, deliveringHeldWork = true))
    }

    @Test
    fun `held work needs no exception while the service is alive`() {
        assertTrue(runMayBeGivenWork(teardownBegun = false, deliveringHeldWork = true))
    }
}
