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
        val queue = StatedHeartRateQueue(scope, recover = { null }) { maxHr, restingHr ->
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
    fun `an interrupted statement lands before anything the runner states`() = runTest {
        // Recovery is what the profile *was*, so it has to land first. Enqueued instead of applied
        // ahead of the queue, a runner reaching Settings before the note had been read would have
        // their statement applied and then overwritten by last session's leftover number.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val applied = mutableListOf<Pair<Int?, Int?>>()
        val queue = StatedHeartRateQueue(
            scope = scope,
            recover = { StatedHeartRates(null, 60) }
        ) { maxHr, restingHr -> applied += maxHr to restingHr }

        // Stated before the consumer has run at all — the queue buffers it.
        queue.state(null, 55)
        advanceUntilIdle()

        assertEquals(listOf<Pair<Int?, Int?>>(null to 60, null to 55), applied)
        scope.cancel()
    }

    @Test
    fun `with nothing interrupted, the queue just starts`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val applied = mutableListOf<Pair<Int?, Int?>>()
        val queue = StatedHeartRateQueue(scope, recover = { null }) { maxHr, restingHr -> applied += maxHr to restingHr }

        queue.state(null, 55)
        advanceUntilIdle()

        assertEquals(listOf<Pair<Int?, Int?>>(null to 55), applied)
        scope.cancel()
    }

    @Test
    fun `a failed recovery costs the recovery, not the queue`() = runTest {
        // A recovery that cannot even be read never stops a statement landing. It is read again on
        // the way into the next statement rather than replayed on its own, so nothing it carries
        // can overtake a number the runner has just stated.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val applied = mutableListOf<Pair<Int?, Int?>>()
        val queue = StatedHeartRateQueue(
            scope = scope,
            recover = { throw IllegalStateException("storage is having a day") }
        ) { maxHr, restingHr -> applied += maxHr to restingHr }

        queue.state(null, 55)
        queue.state(null, 50)
        advanceUntilIdle()

        assertEquals(listOf<Pair<Int?, Int?>>(null to 55, null to 50), applied)
        scope.cancel()
    }

    @Test
    fun `a recovery that could not be applied is carried by the next statement`() = runTest {
        // #179. The runner corrects Max HR to 200 after a failed recovery of (195, 60). Left for
        // the next launch, the stale 195 would replay and silently undo the 200 — a future-only
        // Max HR change moves no history, so it never clears the note on its way past. Carried
        // under the statement instead, the runner's 200 wins and the interrupted 60 still lands.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val applied = mutableListOf<Pair<Int?, Int?>>()
        var note: StatedHeartRates? = StatedHeartRates(195, 60)
        var failRecovery = true
        val queue = StatedHeartRateQueue(scope, recover = { note }) { maxHr, restingHr ->
            if (failRecovery) {
                failRecovery = false
                throw IllegalStateException("storage is having a day")
            }
            applied += maxHr to restingHr
            // Only a statement that re-bands history finishes the note — see
            // `SettingsRepository.setStatedHeartRates`.
            if (restingHr != null) note = null
        }

        queue.state(200, null)
        advanceUntilIdle()

        assertEquals(listOf<Pair<Int?, Int?>>(200 to 60), applied)
        scope.cancel()
    }

    @Test
    fun `a recovery too broken to read is carried by a later statement once it can be`() = runTest {
        // The failure can be reading the note as easily as applying it, and then there is nothing
        // to carry until it can be read. So it is read again on the way into each statement until
        // one takes it — never applied on its own, so it can overwrite nothing.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val applied = mutableListOf<Pair<Int?, Int?>>()
        var readable = false
        val queue = StatedHeartRateQueue(
            scope = scope,
            recover = {
                if (!readable) throw IllegalStateException("storage is having a day")
                StatedHeartRates(195, 60)
            }
        ) { maxHr, restingHr -> applied += maxHr to restingHr }

        queue.state(null, 55)
        advanceUntilIdle()
        readable = true
        queue.state(200, null)
        advanceUntilIdle()

        assertEquals(listOf<Pair<Int?, Int?>>(null to 55, 200 to 60), applied)
        scope.cancel()
    }

    @Test
    fun `a recovery is carried once, not by every statement after it`() = runTest {
        // Carrying it again would re-state a number the runner has moved on from: the statement
        // that took it landed it, and the note it came from is finished.
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val applied = mutableListOf<Pair<Int?, Int?>>()
        var note: StatedHeartRates? = StatedHeartRates(195, 60)
        var failRecovery = true
        val queue = StatedHeartRateQueue(scope, recover = { note }) { maxHr, restingHr ->
            if (failRecovery) {
                failRecovery = false
                throw IllegalStateException("storage is having a day")
            }
            applied += maxHr to restingHr
            if (restingHr != null) note = null
        }

        queue.state(200, null)
        queue.state(205, null)
        advanceUntilIdle()

        assertEquals(listOf<Pair<Int?, Int?>>(200 to 60, 205 to null), applied)
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
        val queue = StatedHeartRateQueue(scope, recover = { null }) { maxHr, _ ->
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
        val queue = StatedHeartRateQueue(scope, recover = { null }) { maxHr, _ ->
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
