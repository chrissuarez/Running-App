package com.example.runningapp.diagnostics

import com.example.runningapp.SessionStatus
import com.example.runningapp.isRecording
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
 * [strapRunRowId] is the Run the Strap now connected has been worn for, and is what a Strap event
 * falls back to when no Run is live — see [RunHeldFor] for the rule it is kept by.
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

/**
 * The Strap, read off the Acquisition's phase rather than off the connection callbacks — so a
 * retry, which leaves [AcquisitionPhase.Connected] and comes back to it, reads as the dropout it
 * is, and a retry that goes round again reads as nothing at all.
 *
 * A release names [runRowId] if a Run is live and [strapRunRowId] — the Run the Strap has been worn
 * for — if none is. On a normal stop the Run publishes its cleared row before the Acquisition
 * publishes the release, so without the fallback the closing `strap-disconnected` of every strapped
 * Run reads `run=-` and a journal holding two Runs cannot say which one let go of the Strap. That
 * holds for a Strap put on before START as much as one connected mid-Run: the connection itself
 * names no Run, and the Run it turned out to be worn for arrives afterwards ([RunHeldFor]).
 *
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
    // The same rule as the arrival and the release above: what is compared is what the phase
    // carries, not merely which phase it is, so a change within one phase type is not swallowed.
    // A block is news when it blocks for a reason the app was not already blocked for. The adapter
    // coming back on mid-Run with BLUETOOTH_CONNECT missing steps straight from
    // Blocked(BluetoothUnavailable) to Blocked(PermissionMissing) (#224), and a journal keeping the
    // stale reason would name Bluetooth for a Run that in fact finished strapless for want of a
    // permission — the wrong answer to the only question a lost Run asks (#310).
    val wasBlockedBy = (before as? AcquisitionPhase.Blocked)?.reason
    val nowBlockedBy = (after as? AcquisitionPhase.Blocked)?.reason
    if (nowBlockedBy != null && nowBlockedBy != wasBlockedBy) {
        entries += RunJournalEntry(RunJournalEvent.ACQUISITION_BLOCKED, runRowId, nowBlockedBy.toString())
    }
    return entries
}

private fun AcquisitionPhase.Connected.describe() = "$name $address"

/**
 * The Run Journal's ear: hand it what the service has just published and the lines that follow are
 * written.
 *
 * Holds the two things [journalEntriesFor] cannot be given — what was true last time, and the Run
 * the Strap now connected has been worn for — and nothing else, so [journalEntriesFor] stays a pure
 * function of what was published. Not thread-safe, and does not need to be: it is fed from the one
 * thread the Run and the Acquisition publish on.
 */
class RunJournalWatch(private val journal: RunJournal) {

    private var last = JournaledState()

    /** A Strap connection is a holding like a Promotion is, and is remembered by the same rule. */
    private val strapWornFor = RunHeldFor()

    fun observe(published: JournaledState) {
        val entries = journalEntriesFor(last, published, strapWornFor.runRowId)
        last = published
        // Read off the lines just written rather than worked out again, so which Run a connection
        // belongs to is decided in exactly one place. The release ends the holding and the arrival
        // begins one; a Strap that changes address does both in that order, and is left holding the
        // new connection only.
        if (entries.any { it.event == RunJournalEvent.STRAP_DISCONNECTED }) {
            strapWornFor.ends(published.runRowId)
        }
        if (entries.any { it.event == RunJournalEvent.STRAP_CONNECTED }) strapWornFor.begins()
        strapWornFor.observe(published.runRowId)
        journal.write(entries)
    }
}
