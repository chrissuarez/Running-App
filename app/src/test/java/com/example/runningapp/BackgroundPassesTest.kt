package com.example.runningapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * #375 — a launch pass whose opening read throws must leave the app running and its work owed.
 */
class BackgroundPassesTest {

    @Test
    fun `a pass whose opening read throws does not escape the scope`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val failures = mutableListOf<Pair<String, Throwable>>()
        val passes = BackgroundPasses(scope) { name, e -> failures += name to e }

        // The shape every launch pass has: the work list is read before the per-item guard begins,
        // so a throw there is the one failure the pass itself does not cover.
        var workDone = false
        passes.launch("Stage settlement") {
            readTheWorkList()
            workDone = true
        }
        advanceUntilIdle()

        assertEquals(1, failures.size)
        assertEquals("Stage settlement", failures.single().first)
        // Still owed: nothing the pass would have done was done.
        assertFalse(workDone)
        // And the app is still here — the scope was not taken down with the pass.
        assertTrue(scope.isActive)
    }

    @Test
    fun `a failed pass leaves its siblings running`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val failures = mutableListOf<String>()
        val passes = BackgroundPasses(scope) { name, _ -> failures += name }

        var siblingRan = false
        passes.launch("first") { readTheWorkList() }
        passes.launch("second") { siblingRan = true }
        advanceUntilIdle()

        assertEquals(listOf("first"), failures)
        assertTrue(siblingRan)
    }

    @Test
    fun `cancellation is the scope talking, not a pass failing`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val failures = mutableListOf<String>()
        val passes = BackgroundPasses(scope) { name, _ -> failures += name }

        val job = passes.launch("cancelled") { throw CancellationException("scope torn down") }
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertTrue(failures.isEmpty())
    }

    /** Room on a disk under pressure at cold start, which is what #375 was filed about. */
    private fun readTheWorkList(): List<Long> = throw IllegalStateException("could not read")
}
