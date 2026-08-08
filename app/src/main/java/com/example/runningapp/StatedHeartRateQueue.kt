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
     *
     * What carrying cannot reach is a note nothing in this launch could ever read, left on disk by
     * a statement that moved no history, and then found by the next launch. That needs reading the
     * note to fail while writing the profile succeeds — the same DataStore, one failing and one
     * not — and one statement, no second one, before the process dies. Everything short of that is
     * covered: any statement that does carry the note re-bands history and so finishes it, and a
     * note read late can no longer hold a number a statement has already replaced.
     */
    recover: suspend () -> StatedHeartRates?,
    apply: suspend (maxHr: Int?, restingHr: Int?) -> Unit
) {
    private val statements = Channel<StatedHeartRates>(Channel.UNLIMITED)

    init {
        scope.launch {
            val outstanding = OutstandingStatement()

            applying {
                val recovered = recover()
                outstanding.found(recovered)
                recovered?.let { apply(it.maxHr, it.restingHr) }
                outstanding.settled()
            }
            for (statement in statements) {
                // Read again, on the way in, when the first attempt could not read it — never on
                // its own and never between statements, so nothing it holds can land after a
                // number the runner has stated. Its own `applying` because a note too broken to
                // read must still cost the note and not the statement below.
                if (outstanding.unread) applying { outstanding.found(recover()) }
                applying {
                    val carried = outstanding.under(statement)
                    apply(carried.maxHr, carried.restingHr)
                    outstanding.landed(statement)
                }
            }
        }
    }

    /**
     * What a launch still owes the runner: an interrupted statement recovery could not land, held
     * until a statement carries it (#179).
     *
     * Three states rather than two — never read, owed, settled — because a recovery can fail at
     * reading the note as easily as at applying it, and there is nothing to carry until it has been
     * read. They live together here because the rules between them are the whole behaviour.
     */
    private class OutstandingStatement {
        private var read = false
        private var owed: StatedHeartRates? = null
        private var maxHrStatedSince = false
        private var restingHrStatedSince = false

        /** Whether the note has never been read, and so is still worth trying to read. */
        val unread: Boolean get() = !read

        /**
         * Takes what recovery found — minus any number the runner has stated since.
         *
         * That subtraction is the point when reading is what failed. The statements made while the
         * note was unreadable have landed already, and a note read after them carrying the number
         * one of them replaced would put it straight back: the same silent reversion, moved from
         * the next launch to the next statement.
         */
        fun found(recovered: StatedHeartRates?) {
            read = true
            owed = recovered?.let {
                StatedHeartRates(
                    maxHr = if (maxHrStatedSince) null else it.maxHr,
                    restingHr = if (restingHrStatedSince) null else it.restingHr
                )
            }
        }

        /**
         * [statement] with what is owed carried underneath it: the runner's numbers win, and only
         * what this statement did not state is taken from the note.
         */
        fun under(statement: StatedHeartRates) = StatedHeartRates(
            maxHr = statement.maxHr ?: owed?.maxHr,
            restingHr = statement.restingHr ?: owed?.restingHr
        )

        /** Nothing is owed any longer — what was owed has just been applied. */
        fun settled() {
            owed = null
        }

        /**
         * [statement] has landed, carrying whatever was owed. Nothing is owed any longer, and no
         * note read later can undo what it stated.
         */
        fun landed(statement: StatedHeartRates) {
            settled()
            if (statement.maxHr != null) maxHrStatedSince = true
            if (statement.restingHr != null) restingHrStatedSince = true
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
