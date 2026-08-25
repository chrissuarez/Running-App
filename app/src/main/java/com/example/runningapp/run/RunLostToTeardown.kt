package com.example.runningapp.run

import com.example.runningapp.SessionStatus
import com.example.runningapp.isRecording

/**
 * What a service teardown found of the Run it was recording, if it was recording one — #309's
 * shape, decided rather than merely written down, and #314's shape beside it.
 *
 * A Run leaves the service one of two ways. A stop crosses it out of RUNNING or PAUSED first and
 * the hand-back that takes the service down follows, so by the time the teardown runs the Run is no
 * longer recording and its finalization is already on its way to the row. The other way is a
 * teardown that arrives with the Run still recording: the system stopping an unpromoted service
 * while the app is idle, which is what #310's journal caught on the phone. Nothing after that point
 * will stop the Run — the recording is gone with the service and the row is left at `endTime = 0`,
 * which is a Run that has vanished from history, from the export and from the coach until something
 * puts it back.
 *
 * This is the one place that tells those apart, and it is deliberately the same reading the Run
 * Journal is reasoned about by: recording, then not, is a stop; still recording at the teardown is
 * a loss ([com.example.runningapp.diagnostics.RunJournalEvent.RUN_STOPPED]). STOPPING is not a loss
 * even though its row has no totals yet — the runner has stopped, and the Run finalizes the moment
 * its id lands ([RunLifecycle.STOPPING]).
 *
 * @param status what the service last published of the Run.
 * @param liveRunRowId the row id of the Run it holds as live, null once a stop has cleared it and
 * null again before the Run's insert has come back.
 * @param heldWork what the Run is still holding for an id it has not been given
 * ([RunState.pendingRowEffects]). Only ever read for a Run with no row: a Run with one is holding
 * nothing, because the id it was waiting for arrived.
 */
fun runLostToTeardown(
    status: SessionStatus,
    liveRunRowId: Long?,
    heldWork: List<PendingRowWork> = emptyList(),
): RunLostToTeardown? =
    if (!status.isRecording) null
    else liveRunRowId?.let { RunLostToTeardown.HasRow(it) }
        ?: RunLostToTeardown.AwaitingItsRow(heldWork)

/**
 * The Run a teardown took, in the two states it can be found in — which are two different jobs.
 *
 * The split is where the Run's seconds are. A Run with a row has written every second it recorded
 * to `hr_samples` and `track_points` as it ran, so putting it back is a read of the database. A Run
 * whose insert has not come back has written nothing at all: its seconds are held in
 * [RunState.pendingRowEffects] waiting for an id, and the row they are addressed to is still on its
 * way. Nothing can be read back for that Run, so the seconds have to be handed over before there is
 * anything to read.
 */
sealed interface RunLostToTeardown {

    /**
     * Whether there is anything for the runner to go and look for.
     *
     * What the runner is told turns on this, so it is decided here with the rest of the reading
     * rather than at the notification (#314). A Run put back is a Run in history; a Run with
     * nothing to put back is a Run that was never written down, and telling that runner to check
     * their history sends them looking for something that is not there.
     */
    val hasSomethingToSave: Boolean

    /** The Run has a row. Finish it from what it wrote down (#309). */
    data class HasRow(val runRowId: Long) : RunLostToTeardown {

        /**
         * Always, as far as this can see. The Run has been writing a row per second since its id
         * landed, and whether those seconds add up to a Run is a question only the database can
         * answer — the rescue asks it ([com.example.runningapp.data.finishedFromRecord]), long
         * after the runner has been told.
         */
        override val hasSomethingToSave: Boolean get() = true
    }

    /**
     * The Run's insert had not landed (#314).
     *
     * The row id is not in this answer because there is not one yet — it exists only inside the
     * insert the teardown has to wait out. What is here instead is everything the Run was holding
     * for that id, which is the only record of the Run that exists anywhere: nothing addressed to a
     * row can have been written before there was a row to address.
     *
     * @param heldWork the Run's held work, in the order it was produced.
     */
    data class AwaitingItsRow(val heldWork: List<PendingRowWork>) : RunLostToTeardown {

        /**
         * A banked second of heart rate, and only that.
         *
         * It is the same evidence the rebuild reads, which is what makes it the right question:
         * without a sample and without a fix — and a Run with no id writes no fix, because a track
         * point is addressed to a row too — the rebuild has nothing to measure and refuses the Run
         * ([com.example.runningapp.data.finishedFromRecord]). A held Pause or Interval is not
         * evidence of a recorded second but bookkeeping about seconds that were never written down,
         * so a Run holding only those has nothing to save either.
         *
         * A Run recorded without a Strap banks no sample at all, so it comes out false and its
         * runner is told nothing was saved. That is the truth of it: with no sample, no fix and no
         * row, such a Run left nothing behind to put back.
         */
        override val hasSomethingToSave: Boolean
            get() = heldWork.any { it is PendingRowWork.SaveHrSample }

        /**
         * Whether the runner had already stopped this Run, which makes it no loss at all.
         *
         * A STOP is dispatched on the session thread, and the state that says so is published a
         * beat before the Run's held finalize is looked at — so a teardown whose snapshot was taken
         * in that beat reads a Run that is still RUNNING and finds a [PendingRowWork.Finalize] in
         * its held work. The Run is over and its own totals are held right here, which is a better
         * finish than any rebuild: the held work goes out, the finalize among it, and nothing
         * rescues, discards or tells the runner anything.
         */
        val runnerStopped: Boolean get() = heldWork.any { it is PendingRowWork.Finalize }
    }
}
