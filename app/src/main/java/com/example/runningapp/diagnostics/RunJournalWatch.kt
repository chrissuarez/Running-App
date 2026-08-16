package com.example.runningapp.diagnostics

import com.example.runningapp.SessionStatus
import com.example.runningapp.run.AcquisitionPhase

/**
 * The published facts a Run Journal line can be derived from.
 *
 * Everything here the service already publishes on `HrState`, which is the point: the Run
 * lifecycle and the Strap are journaled by reading what was just published rather than by a call at
 * each of the dozen places that move them — the same reason Promotion is derived rather than
 * claimed (ADR 0001). A line cannot be forgotten at a new call site if there is no call site.
 */
data class JournaledState(
    val sessionStatus: SessionStatus = SessionStatus.IDLE,
    /** The live Run's row, null before it lands and again once the Run is over. */
    val runRowId: Long? = null,
    val acquisition: AcquisitionPhase = AcquisitionPhase.Idle,
)

/**
 * What the journal has to say about the step from [before] to [after]. Empty for a step that
 * changed nothing the journal cares about, which is nearly every one of them: the state this reads
 * republishes every second while a Run is on.
 */
fun journalEntriesFor(before: JournaledState, after: JournaledState): List<RunJournalEntry> {
    // The Run being named is whichever of the two knows one. On a stop the live Run has already
    // been cleared by the time the status says so, and a stop line naming no Run is the one line
    // a lost Run's diagnosis cannot do without.
    val runRowId = after.runRowId ?: before.runRowId
    val entries = mutableListOf<RunJournalEntry>()

    runEvent(before.sessionStatus, after.sessionStatus)?.let {
        entries += RunJournalEntry(it, runRowId)
    }
    if (before.runRowId == null && after.runRowId != null) {
        entries += RunJournalEntry(RunJournalEvent.RUN_ROW_CREATED, after.runRowId)
    }
    entries += strapEntries(before.acquisition, after.acquisition, runRowId)
    return entries
}

/**
 * A Run stops once, however many steps its stop takes: STOPPING and STOPPED are both "not
 * recording", and only the crossing out of RUNNING or PAUSED is news.
 */
private fun runEvent(before: SessionStatus, after: SessionStatus): RunJournalEvent? = when {
    before == after -> null
    after == SessionStatus.RUNNING && before == SessionStatus.PAUSED -> RunJournalEvent.RUN_RESUMED
    after == SessionStatus.RUNNING -> RunJournalEvent.RUN_STARTED
    after == SessionStatus.PAUSED && before == SessionStatus.RUNNING -> RunJournalEvent.RUN_PAUSED
    before.isRecording && !after.isRecording -> RunJournalEvent.RUN_STOPPED
    else -> null
}

private val SessionStatus.isRecording: Boolean
    get() = this == SessionStatus.RUNNING || this == SessionStatus.PAUSED

/**
 * The Strap, read off the Acquisition's phase rather than off the connection callbacks — so a
 * retry, which leaves [AcquisitionPhase.Connected] and comes back to it, reads as the dropout it
 * is, and a retry that goes round again reads as nothing at all.
 */
private fun strapEntries(
    before: AcquisitionPhase,
    after: AcquisitionPhase,
    runRowId: Long?,
): List<RunJournalEntry> {
    val entries = mutableListOf<RunJournalEntry>()
    val was = before as? AcquisitionPhase.Connected
    val now = after as? AcquisitionPhase.Connected
    if (was != null && (now == null || now.address != was.address)) {
        entries += RunJournalEntry(RunJournalEvent.STRAP_DISCONNECTED, runRowId, was.describe())
    }
    if (now != null && (was == null || was.address != now.address)) {
        entries += RunJournalEntry(RunJournalEvent.STRAP_CONNECTED, runRowId, now.describe())
    }
    if (after is AcquisitionPhase.GaveUp && before !is AcquisitionPhase.GaveUp) {
        entries += RunJournalEntry(RunJournalEvent.ACQUISITION_GAVE_UP, runRowId)
    }
    if (after is AcquisitionPhase.Blocked && before !is AcquisitionPhase.Blocked) {
        entries += RunJournalEntry(RunJournalEvent.ACQUISITION_BLOCKED, runRowId, after.reason.toString())
    }
    return entries
}

private fun AcquisitionPhase.Connected.describe() = "$name $address"

/**
 * The Run Journal's ear: hand it what the service has just published and the lines that follow are
 * written.
 *
 * Holds the one thing [journalEntriesFor] cannot be given — what was true last time — and nothing
 * else. Not thread-safe, and does not need to be: it is fed from the one thread the Run and the
 * Acquisition publish on.
 */
class RunJournalWatch(private val journal: RunJournal) {

    private var last = JournaledState()

    fun observe(published: JournaledState) {
        val entries = journalEntriesFor(last, published)
        last = published
        journal.write(entries)
    }
}
