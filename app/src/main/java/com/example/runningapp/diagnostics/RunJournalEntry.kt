package com.example.runningapp.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Something that changed whether a Run was recording (#310).
 *
 * Deliberately a short closed list rather than a logging level: the Run Journal answers one
 * question — "was this Run recording, and if it stopped, what stopped it" — and every event here
 * earns its place by being an answer to it. Anything that merely describes what the app was doing
 * belongs in `Log.d`, which rolls off the phone within a couple of hours, which is why this exists.
 *
 * [token] is what gets written, and is what a human or an agent greps for over `adb`, so it is
 * stable: renaming one silently invalidates every instruction that reads a journal off a phone.
 *
 * @property absenceIsEvidence Whether a reader is entitled to conclude something from this event
 * *not* being there. Most of the journal is read forwards — the lines say what happened. These few
 * are read backwards: a [SERVICE_CREATED] with no [SERVICE_DESTROYED] above it says the process
 * died, a stop with no [RUN_FINALIZED] after it says the totals never reached the row, a destroy
 * with a live Run and no [DEMOTED] above it says the system took the service. That reasoning only
 * holds while a missing line means the event did not happen. All three are written at moments the
 * process may be about to go away — a teardown, a hand-back, a finalize the service no longer
 * outlives — so a line merely queued behind a slow append or an archive copy dies with the process,
 * and the journal then says something false, which is worse than a journal that says nothing.
 *
 * So an event whose absence is evidence is waited for where it is written, by [RunJournal.write]
 * itself rather than by each call site remembering to. Marking these three covers every other line
 * as well: the journal has one writer thread and it is FIFO, so waiting for a decisive line has
 * already landed everything queued in front of it. That is what keeps this set small — nothing is
 * marked for being important, only for being read by its absence.
 */
enum class RunJournalEvent(val token: String, val absenceIsEvidence: Boolean = false) {

    /** The service came up. With no [SERVICE_DESTROYED] above it, the process had died. */
    SERVICE_CREATED("service-created"),

    /** The service went down, carrying whatever it knew of the state it went down in. */
    SERVICE_DESTROYED("service-destroyed", absenceIsEvidence = true),

    RUN_STARTED("run-started"),

    /**
     * The Run's row landed in the database. Its own line rather than a detail on [RUN_STARTED]:
     * the insert is asynchronous, so a Run that died between the two is a Run with no row, and only
     * the absence of this line says so.
     */
    RUN_ROW_CREATED("run-row-created"),

    RUN_PAUSED("run-paused"),
    RUN_RESUMED("run-resumed"),

    /** The Run left RUNNING or PAUSED. Written once per Run, whatever the stop then does. */
    RUN_STOPPED("run-stopped"),

    /** The Run's totals reached its row. A Run stopped but never finalized stops here. */
    RUN_FINALIZED("run-finalized", absenceIsEvidence = true),

    /** startForeground() was granted: the Run has its notification and its wake lock. */
    PROMOTED("promoted"),

    /** The platform refused the foreground start. The Run keeps going, undefended. */
    PROMOTION_REFUSED("promotion-refused"),

    /**
     * The foreground state was handed back — notification, wake lock and `stopSelf()` together.
     *
     * The line #310 was written for: in #309 one of these, timestamped, would have closed the
     * ticket on its own.
     *
     * A demote is the only `stopSelf()` in the app, so every one of them is written down — including
     * the one that hands back a start the platform refused to promote. Those read as a `demoted`
     * with a [PROMOTION_REFUSED] above it rather than a [PROMOTED], which is how a hand-back of
     * something never held is told apart from a Run losing its foreground state.
     */
    DEMOTED("demoted", absenceIsEvidence = true),

    STRAP_CONNECTED("strap-connected"),
    STRAP_DISCONNECTED("strap-disconnected"),

    /** The Acquisition stopped chasing a Strap it could not reach. */
    ACQUISITION_GAVE_UP("acquisition-gave-up"),

    /**
     * The Acquisition cannot proceed at all — Bluetooth off, a permission gone, a refused scan.
     *
     * Alongside the three the ticket asked for because it is the same evidence: a Run that carries
     * on with no heart rate has a reason, and this is where the reason is written down.
     */
    ACQUISITION_BLOCKED("acquisition-blocked"),
}

/**
 * One line of the Run Journal, before it is a line: what happened, which Run it happened to, and
 * whatever detail is worth keeping.
 *
 * [runRowId] is null only when there genuinely is no Run to name — the service coming up, or a Run
 * whose row has not landed yet.
 */
data class RunJournalEntry(
    val event: RunJournalEvent,
    val runRowId: Long? = null,
    val detail: String? = null,
)

/**
 * How an entry is written down.
 *
 * One line, plain text, wall clock first — no structure worth parsing, because the reader is a
 * person or an agent scrolling `adb shell cat`, not a program. Local time with its offset, because
 * that is the clock an incident is reasoned about in ("it stopped somewhere around ten to eight"),
 * and the offset keeps it unambiguous when the phone has since moved or the clocks have changed.
 */
object RunJournalLine {

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSxxx")

    fun format(entry: RunJournalEntry, atMillis: Long, zone: ZoneId): String {
        val at = CLOCK.format(Instant.ofEpochMilli(atMillis).atZone(zone))
        val run = entry.runRowId?.toString() ?: "-"
        // A detail is somebody else's text — an exception message, a Strap's own name — and one
        // event must stay one line, so anything that would break the line is flattened into it.
        val detail = entry.detail?.replace(LINE_BREAKS, " ")?.trim()
        val tail = if (detail.isNullOrEmpty()) "" else ": $detail"
        return "$at run=$run ${entry.event.token}$tail"
    }

    private val LINE_BREAKS = Regex("[\\r\\n]+")
}
