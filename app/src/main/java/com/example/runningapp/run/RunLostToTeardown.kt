package com.example.runningapp.run

import com.example.runningapp.SessionStatus
import com.example.runningapp.isRecording

/**
 * The Run a service teardown finds still recording, if there is one — #309's shape, decided rather
 * than merely written down.
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
 * Answers null where there is no row to answer for. A Run whose insert has not landed has nothing
 * in the database to finish and nothing recorded to finish it from, so a teardown in that instant
 * loses seconds that were never written down — the absence of `run-row-created` is all there is to
 * say about it, and the journal says that already.
 *
 * @param status what the service last published of the Run.
 * @param liveRunRowId the row id of the Run it holds as live, null once a stop has cleared it.
 */
fun runLostToTeardown(status: SessionStatus, liveRunRowId: Long?): Long? =
    liveRunRowId.takeIf { status.isRecording }
