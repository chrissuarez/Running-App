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
     *
     * When it cannot land — reading or applying it throws — it is **carried** by the next statement
     * instead of being left for the next launch (#179). Carried, not queued: the statement's own
     * numbers win, and only what it did not state is taken from the note. So the interrupted
     * resting heart rate still reaches history, the maximum the runner has just corrected is not
     * quietly replaced by the one they corrected it from, and nothing lands after the statement to
     * overwrite it — the whole of why recovery goes first, kept.
     *
     * Left for the next launch, that correction *was* silently undone: a future-only Max HR change
     * moves no history, so it does not clear the note, and the next launch replayed the older
     * maximum over it.
     */
    recover: suspend () -> StatedHeartRates?,
    apply: suspend (maxHr: Int?, restingHr: Int?) -> Unit
) {
    private val statements = Channel<StatedHeartRates>(Channel.UNLIMITED)

    init {
        scope.launch {
            // What the interrupted statement still owes, once recovery has failed to land it. Held
            // only until a statement carries it — see [carrying].
            var owed: StatedHeartRates? = null
            // Whether the note has been read at all. A recovery can fail at reading it as easily as
            // at applying it, and until it has been read there is nothing to carry.
            var read = false

            applying {
                owed = recover()
                read = true
                owed?.let { apply(it.maxHr, it.restingHr) }
                owed = null
            }
            for (statement in statements) {
                // Read again, on the way in, when the first attempt could not read it — never on
                // its own and never between statements, so nothing it holds can land after a
                // number the runner has stated. Its own `applying` because a note too broken to
                // read must still cost the note and not the statement below.
                if (!read) applying { owed = recover(); read = true }
                applying {
                    val carried = owed
                    apply(statement.maxHr ?: carried?.maxHr, statement.restingHr ?: carried?.restingHr)
                    owed = null
                }
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
     * A failed recovery costs the recovery the same way: what it owes is carried by the next
     * statement instead — see [recover].
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
