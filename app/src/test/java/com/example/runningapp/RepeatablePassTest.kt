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
    fun `an ask while the pass is still running starts nothing`() = runTest {
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        val pass = RepeatablePass(passesOn(this), "test") { runs++; gate.await() }

        assertTrue(pass.startUnlessRunning())
        advanceUntilIdle()
        assertFalse(pass.startUnlessRunning())
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, runs)
        assertTrue(pass.startUnlessRunning())
        advanceUntilIdle()
        assertEquals(2, runs)
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
