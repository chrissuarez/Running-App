package com.example.runningapp.foreground

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wait for work in flight is not over until the scope is empty (#309).
 */
class ScopeDrainTest {

    @Test
    fun `work launched while the wait is under way is waited for too`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val firstMayFinish = CompletableDeferred<Unit>()
        val done = mutableListOf<String>()

        scope.launch {
            firstMayFinish.await()
            done += "first"
            // The late arrival: a producer that was already queued lands its write behind the
            // first join, exactly as a GPS fix on a looper asked to quit safely can.
            scope.launch { done += "late" }
        }

        val drain = launch { assertTrue(drainChildren(scope.coroutineContext.job)) }
        testScheduler.advanceUntilIdle()
        assertTrue("the drain must still be waiting on the first child", drain.isActive)

        firstMayFinish.complete(Unit)
        drain.join()

        assertEquals(listOf("first", "late"), done)
    }

    @Test
    fun `the excluded job is not waited for`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        var sibling = false

        // The shape of the rescue: a coroutine on the very scope it drains, which would wait for
        // itself for ever if the drain did not leave it out. Its siblings it still waits for.
        scope.launch { sibling = true }
        val rescue = scope.launch {
            assertTrue(drainChildren(scope.coroutineContext.job, except = coroutineContext.job))
            assertTrue(sibling)
        }
        rescue.join()
        assertTrue(rescue.isCompleted)
    }

    @Test
    fun `a producer still running when the passes run out is given up on`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        var launched = 0

        // Every child launches its replacement as it ends, so the scope is never empty while the
        // drain is looking. Capped well above the pass count, because a producer that truly never
        // stopped would leave this test's own scheduler with work for ever — the drain is what has
        // to give up here, and it does so long before the cap.
        fun launchOne() {
            if (launched >= 20) return
            scope.launch {
                launched++
                launchOne()
            }
        }
        launchOne()

        assertFalse(drainChildren(scope.coroutineContext.job, passes = 3))
        // The drain stopped looking while the producer was still going: what it waited for is
        // bounded by the passes, not by the producer running out. How many children ran inside
        // those passes is the scheduler's business, but it is neither none nor all of them.
        assertTrue(launched in 3 until 20)

        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `an empty scope is drained without waiting`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        assertTrue(drainChildren(scope.coroutineContext.job))
    }
}
