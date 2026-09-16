package com.example.runningapp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #444 — the weather backfill may be asked for again in the same process, but never while an earlier
 * ask is still running.
 */
class RepeatablePassTest {

    @Test
    fun `a second ask after the first has finished runs the pass again`() = runTest {
        var runs = 0
        val pass = RepeatablePass(passesOn(this), "test") { runs++ }

        assertTrue(pass.startUnlessRunning())
        advanceUntilIdle()
        assertTrue(pass.startUnlessRunning())
        advanceUntilIdle()

        assertEquals(2, runs)
    }

    @Test
    fun `asks while the pass is still running become one more pass after it`() = runTest {
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        val pass = RepeatablePass(passesOn(this), "test") { runs++; if (runs == 1) gate.await() }

        assertTrue(pass.startUnlessRunning())
        advanceUntilIdle()
        assertFalse(pass.startUnlessRunning())
        assertFalse(pass.startUnlessRunning())
        advanceUntilIdle()
        assertEquals(1, runs)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, runs)
    }

    @Test
    fun `an ask before a started pass has begun is covered by that pass`() = runTest {
        var runs = 0
        val pass = RepeatablePass(passesOn(this), "test") { runs++ }

        assertTrue(pass.startUnlessRunning())
        // Started but not yet run: its list is still to be read, so it covers this ask.
        assertFalse(pass.startUnlessRunning())
        advanceUntilIdle()

        assertEquals(1, runs)
    }

    @Test
    fun `a pass with no ask during it is not followed by another`() = runTest {
        var runs = 0
        val pass = RepeatablePass(passesOn(this), "test") { runs++ }

        pass.startUnlessRunning()
        advanceUntilIdle()

        assertEquals(1, runs)
    }

    @Test
    fun `a pass that failed lets the next ask through`() = runTest {
        var runs = 0
        val passes = BackgroundPasses(CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())) { _, _ -> }
        val pass = RepeatablePass(passes, "test") { runs++; error("offline") }

        pass.startUnlessRunning()
        advanceUntilIdle()
        assertTrue(pass.startUnlessRunning())
        advanceUntilIdle()

        assertEquals(2, runs)
    }

    private fun passesOn(test: TestScope) =
        BackgroundPasses(CoroutineScope(StandardTestDispatcher(test.testScheduler) + SupervisorJob())) { name, e ->
            throw AssertionError("The $name pass failed", e)
        }
}
