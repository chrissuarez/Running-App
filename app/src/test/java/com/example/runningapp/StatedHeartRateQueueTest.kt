package com.example.runningapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The ordering *is* the behaviour, so it is what these cover. Everything else about the queue is
 * one channel and one consumer.
 */
class StatedHeartRateQueueTest {

    @Test
    fun `statements are applied in the order they were made`() = runTest {
        // Blurring Max HR commits it on its own, and the resting edit follows on the way out.
        // Launched independently the second could reach the repository first and re-band history
        // against the maximum about to be replaced — the same two gestures, different history.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val applied = mutableListOf<Pair<Int?, Int?>>()
        val queue = StatedHeartRateQueue(scope) { maxHr, restingHr ->
            // The first statement is the slow one — a first Max HR set re-tallies all history — so
            // an unordered implementation lets the second overtake it.
            if (maxHr != null) delay(1_000)
            applied += maxHr to restingHr
        }

        queue.state(181, null)
        queue.state(null, 55)
        advanceUntilIdle()

        assertEquals(listOf<Pair<Int?, Int?>>(181 to null, null to 55), applied)
        scope.cancel()
    }

    @Test
    fun `a statement that fails costs one statement, not the queue`() = runTest {
        // Applying a profile touches Room, DataStore and a re-tally of the whole history, so it can
        // genuinely fail. An uncaught throw would end the consumer for the life of the process
        // while `state` went on accepting numbers into a channel nothing reads — the runner
        // watching the app take a heart rate that never lands, with nothing to say so.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val applied = mutableListOf<Int?>()
        val queue = StatedHeartRateQueue(scope) { maxHr, _ ->
            if (maxHr == 181) throw IllegalStateException("storage is having a day")
            applied += maxHr
        }

        queue.state(181, null)
        queue.state(190, null)
        advanceUntilIdle()

        assertEquals(listOf<Int?>(190), applied)
        scope.cancel()
    }

    @Test
    fun `one statement is applied at a time, never overlapping`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        var inFlight = 0
        var overlapped = false
        val applied = mutableListOf<Int?>()
        val queue = StatedHeartRateQueue(scope) { maxHr, _ ->
            inFlight++
            if (inFlight > 1) overlapped = true
            delay(10)
            applied += maxHr
            inFlight--
        }

        repeat(5) { queue.state(180 + it, null) }
        advanceUntilIdle()

        assertFalse(overlapped)
        // Not vacuous: every statement really did go through.
        assertEquals(listOf<Int?>(180, 181, 182, 183, 184), applied)
        scope.cancel()
    }
}
