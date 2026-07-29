package com.example.runningapp

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Applies heart-rate statements one at a time, in the order they were made (#172).
 *
 * `SessionRepository.setStatedProfile` holds a lock, so two statements can never overlap — but a
 * lock says nothing about which of two independently launched coroutines reaches it first, and the
 * two numbers bound one reserve: whichever statement lands first decides which maximum the
 * resting-HR re-tally re-bands history against. Blur Max HR, then leave with a resting edit
 * pending, and the same two gestures left different zone history depending on the scheduler.
 *
 * One consumer reading one queue makes call order the answer. That is the whole of this class, and
 * the reason it is a class rather than two lines in the container: the ordering is the behaviour,
 * so it is worth being able to test.
 *
 * [state] does not suspend, because its callers are the settings screen's commit handlers and the
 * number is already accepted on screen by the time it arrives here. For the same reason the queue
 * is unlimited and never closed, and one failing statement never stops the next — dropping a
 * statement would drop a number the runner watched the app take.
 */
class StatedHeartRateQueue(
    scope: CoroutineScope,
    /**
     * A statement left interrupted by a previous process, or null when none was — see
     * `SessionRepository.interruptedStatement`.
     *
     * Read and applied by the consumer *before* it takes anything off the queue, which is the only
     * placement that works. Enqueued instead, it would be a race: the runner reaching Settings
     * first would have their statement applied and then overwritten by last session's leftover
     * number, or would clear the note with a statement that moves no history and strand the
     * already-re-banded runs for good. Anything stated meanwhile waits in the queue, which is
     * exactly right — recovery is what the profile *was*, so it has to land first.
     */
    recover: suspend () -> StatedHeartRates?,
    apply: suspend (maxHr: Int?, restingHr: Int?) -> Unit
) {
    private val statements = Channel<StatedHeartRates>(Channel.UNLIMITED)

    init {
        scope.launch {
            applying { recover()?.let { apply(it.maxHr, it.restingHr) } }
            for (statement in statements) {
                applying { apply(statement.maxHr, statement.restingHr) }
            }
        }
    }

    /**
     * One failed statement must cost one statement, not the queue.
     *
     * Applying a profile touches Room, DataStore and a re-tally of the whole history, so it can
     * genuinely fail — and an uncaught throw would end the consumer for the life of the process
     * while [state] went on cheerfully accepting numbers into a channel nothing reads. The runner
     * would watch the app take a heart rate that never lands, with nothing anywhere to say so.
     *
     * A failed recovery is left in place rather than retried here: the note is only cleared by a
     * statement landing, so the next launch finds it and tries again.
     */
    private inline fun applying(block: () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            // Not a failure — the scope is going away, and swallowing it would leave the consumer
            // looking alive to structured concurrency while nothing drains the queue.
            throw cancellation
        } catch (failure: Throwable) {
            Log.e("StatedHeartRateQueue", "Stating heart rates failed", failure)
        }
    }

    fun state(maxHr: Int?, restingHr: Int?) {
        statements.trySend(StatedHeartRates(maxHr, restingHr))
    }
}
