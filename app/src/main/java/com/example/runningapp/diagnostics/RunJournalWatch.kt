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
 *
 * [strapRunRowId] is the Run that was live when the Strap now connected was connected, and is what
 * a Strap event falls back to when no Run is live — see [strapRunRowIdAfter] for where it comes
 * from.
 */
fun journalEntriesFor(
    before: JournaledState,
    after: JournaledState,
    strapRunRowId: Long? = null,
): List<RunJournalEntry> {
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
    entries += strapEntries(before.acquisition, after.acquisition, runRowId, strapRunRowId)
    return entries
}

/**
 * The Run a Strap event should fall back to after [entries] have been written — read back off the
 * `strap-connected` line rather than worked out again, so which Run a connection belongs to is
 * decided in exactly one place.
 *
 * A connection carries its Run only for as long as it lasts: a release forgets it, so a Strap that
 * connects an hour after a Run ended is named for no Run rather than for the Run before it. A
 * journal that guesses wrong is worse than one with a gap in it (#310).
 */
fun strapRunRowIdAfter(entries: List<RunJournalEntry>, current: Long?): Long? {
    val connected = entries.lastOrNull { it.event == RunJournalEvent.STRAP_CONNECTED }
    if (connected != null) return connected.runRowId
    if (entries.any { it.event == RunJournalEvent.STRAP_DISCONNECTED }) return null
    return current
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
 *
 * A release names [runRowId] if a Run is live and [strapRunRowId] — the Run the connection was made
 * during — if none is. On a normal stop the Run publishes its cleared row before the Acquisition
 * publishes the release, so without the fallback the closing `strap-disconnected` of every strapped
 * Run reads `run=-` and a journal holding two Runs cannot say which one let go of the Strap.
 * An arrival has no such gap and takes the live Run only, so a Strap connected long after a Run
 * finished is named for no Run rather than inheriting that Run's row.
 */
private fun strapEntries(
    before: AcquisitionPhase,
    after: AcquisitionPhase,
    runRowId: Long?,
    strapRunRowId: Long?,
): List<RunJournalEntry> {
    val entries = mutableListOf<RunJournalEntry>()
    val was = before as? AcquisitionPhase.Connected
    val now = after as? AcquisitionPhase.Connected
    if (was != null && (now == null || now.address != was.address)) {
        val releasedBy = runRowId ?: strapRunRowId
        entries += RunJournalEntry(RunJournalEvent.STRAP_DISCONNECTED, releasedBy, was.describe())
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
 * Holds the two things [journalEntriesFor] cannot be given — what was true last time, and the Run
 * the Strap now connected was connected during — and nothing else. Not thread-safe, and does not
 * need to be: it is fed from the one thread the Run and the Acquisition publish on.
 */
class RunJournalWatch(private val journal: RunJournal) {

    private var last = JournaledState()
    private var strapRunRowId: Long? = null

    fun observe(published: JournaledState) {
        val entries = journalEntriesFor(last, published, strapRunRowId)
        last = published
        strapRunRowId = strapRunRowIdAfter(entries, strapRunRowId)
        journal.write(entries)
    }
}
