package com.example.runningapp

import android.util.Log
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
    apply: suspend (maxHr: Int?, restingHr: Int?) -> Unit
) {
    // Null means "not stated now", leaving that number alone — not RESTING_HR_UNSTATED, which is a
    // resting heart rate being deliberately withdrawn.
    private data class Statement(val maxHr: Int?, val restingHr: Int?)

    private val statements = Channel<Statement>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (statement in statements) {
                // One failed statement must cost one statement, not the queue. Applying a profile
                // touches Room, DataStore and a re-tally of the whole history, so it can genuinely
                // fail — and an uncaught throw here would end the consumer for the life of the
                // process while [state] went on cheerfully accepting numbers into a channel nothing
                // reads. The runner would watch the app take a heart rate that never lands, with
                // nothing anywhere to say so.
                runCatching { apply(statement.maxHr, statement.restingHr) }
                    .onFailure { Log.e("StatedHeartRateQueue", "Stating heart rates failed", it) }
            }
        }
    }

    fun state(maxHr: Int?, restingHr: Int?) {
        statements.trySend(Statement(maxHr, restingHr))
    }
}
