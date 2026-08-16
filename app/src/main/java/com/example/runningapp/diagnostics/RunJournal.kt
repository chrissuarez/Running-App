package com.example.runningapp.diagnostics

import android.util.Log
import java.io.File
import java.io.OutputStream
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The Run Journal: a plain-text record on the phone of every event that changed whether a Run was
 * recording (#310).
 *
 * It exists because Android's log buffer holds about two hours and a Run plus the walk home is
 * longer than that. When a Run stopped recording silently in #309, the diagnosis failed for want of
 * evidence rather than for want of reasoning — every app line had rolled off before anyone could
 * look, and the app wrote nothing of its own to disk.
 *
 * Three things it is not: not a second logger (the events are a closed list, see [RunJournalEvent]),
 * not telemetry (nothing here leaves the phone except inside the runner's own archive), and not a
 * store anything reads back to make a decision. It is written for a person or an agent to `cat`.
 *
 * ### The single thread
 *
 * Every write lands on one thread of its own, which is what makes this safe to call from the main
 * thread, the session thread and a finalization coroutine alike without a lock at any call site.
 * That thread outlives the service on purpose — the line recording a teardown must not be cancelled
 * by the teardown it is recording, the same reason `HrForegroundService.finalizationScope` is
 * detached (#310, and see `HrForegroundService.kt`).
 *
 * The file is in the order the writes were *asked for*, which for two threads asking at the same
 * instant is not quite the order they happened in. That is why each line carries its own clock,
 * read at the call rather than at the append: a reader who cares sorts on the timestamps, and never
 * has to trust the file's own order.
 *
 * ### The bound
 *
 * A Run costs a few hundred bytes, so [maxBytes] of a file plus one behind it holds hundreds of
 * Runs and still measures in the low hundreds of kilobytes. Past the cap the current file becomes
 * the previous one and a fresh file starts, which loses the oldest history and nothing else.
 */
class RunJournal(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val maxBytes: Long = MAX_BYTES,
) {

    // Daemon, so a journal thread can never be the reason a JVM (or a test run) stays up. Named,
    // because a thread that outlives the service will turn up in a thread dump and should say what
    // it is.
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RunJournal").apply { isDaemon = true }
    }
    private val dispatcher = writer.asCoroutineDispatcher()

    private val current get() = File(directory, CURRENT_FILE_NAME)
    private val previous get() = File(directory, PREVIOUS_FILE_NAME)

    fun write(event: RunJournalEvent, runRowId: Long? = null, detail: String? = null) =
        write(listOf(RunJournalEntry(event, runRowId, detail)))

    /**
     * Write these entries down, in this order.
     *
     * The clock is read here, on the caller's thread, so a line carries the moment the event
     * happened rather than the moment the writer thread got to it. A journal that timestamped its
     * own IO would be a journal that lies about a stall — which is exactly the kind of thing it is
     * kept to catch.
     *
     * Returns as soon as the lines are queued, except for an event whose absence a reader is
     * entitled to draw a conclusion from ([RunJournalEvent.absenceIsEvidence]): those are waited
     * out here, so that the moment this returns the line is on disk rather than queued behind an
     * append or an archive copy on a process Android may reclaim in the next instant. The rule
     * lives here rather than at the call sites because a call site can forget it, and the fourth
     * one to forget it would put the journal back to saying something false (#309, #310).
     *
     * The wait is [flushBlocking]'s: bounded, and it gives up rather than throwing, so writing a
     * line down can never be what ends the app.
     */
    fun write(entries: List<RunJournalEntry>) {
        if (entries.isEmpty()) return
        val at = now()
        val zone = zone()
        val lines = entries.map { RunJournalLine.format(it, at, zone) }
        writer.execute {
            // A journal that cannot be written is not worth taking the app down for, and there is
            // nowhere left to report it to that would outlive the incident either.
            try {
                append(lines)
            } catch (e: Exception) {
                Log.w("RunJournal", "Could not write ${lines.size} line(s)", e)
            }
        }
        if (entries.any { it.event.absenceIsEvidence }) flushBlocking()
    }

    private fun append(lines: List<String>) {
        directory.mkdirs()
        val text = lines.joinToString(separator = "") { it + "\n" }
        val file = current
        // Rolled before the write rather than after it, so the cap is a ceiling on the file rather
        // than something it is allowed to sit just over until the next event — which on a phone
        // that has stopped running is never.
        if (file.exists() && file.length() + text.toByteArray(Charsets.UTF_8).size > maxBytes) {
            previous.delete()
            if (!file.renameTo(previous)) file.delete()
        }
        file.appendText(text, Charsets.UTF_8)
    }

    /**
     * The journal's files, oldest first, skipping any that does not exist yet.
     *
     * Answered on the writer's own thread, so what comes back is behind every write asked for
     * before it — an archive taken mid-Run holds that Run's lines up to the moment it was taken.
     */
    suspend fun fileNames(): List<String> = withContext(dispatcher) {
        listOf(PREVIOUS_FILE_NAME, CURRENT_FILE_NAME).filter { File(directory, it).exists() }
    }

    /** Copy one of [fileNames] out. On the writer's thread, so it can never catch a torn append. */
    suspend fun copyTo(fileName: String, out: OutputStream) = withContext(dispatcher) {
        val file = File(directory, fileName)
        if (file.exists()) file.inputStream().use { it.copyTo(out) }
    }

    /** Wait for everything asked for so far to be on disk. For tests and for the phone checklist. */
    suspend fun flush() = withContext(dispatcher) { }

    /**
     * The same wait as [flush], from a caller that cannot suspend, and never for longer than
     * [timeoutMs].
     *
     * For the lines a reader is entitled to reason about the absence of — [write] takes this wait
     * for them itself — and for the teardown's own last drain. Every other write stays
     * non-blocking. `onDestroy()` runs on main and returns into a process Android may reclaim
     * immediately afterwards, so a service-destroyed line still queued behind a slow append or an
     * archive copy dies with the process. That line is the whole of #310: the one that says a Run's
     * recording ended because the system took the service, which is what #309 could not be told.
     *
     * Both waits work the same way — a do-nothing task behind everything already queued on the one
     * writer thread, which cannot run until every write asked for before it has.
     *
     * It gives up rather than throwing, on the rule [write] already keeps: a journal that cannot be
     * written is not worth taking the app down for. Least of all here, where taking the app down
     * means an ANR on the teardown of a service that was going away anyway.
     */
    fun flushBlocking(timeoutMs: Long = TEARDOWN_WAIT_MS) {
        try {
            writer.submit { }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Someone wants this thread more than the journal does. Say so and hand it back, or the
            // next thing on it inherits a swallowed interrupt and stalls for reasons of our making.
            Thread.currentThread().interrupt()
            Log.w("RunJournal", "Interrupted waiting for the journal to reach disk", e)
        } catch (e: Exception) {
            Log.w("RunJournal", "Journal did not reach disk within ${timeoutMs}ms", e)
        }
    }

    companion object {

        const val CURRENT_FILE_NAME = "run-journal.txt"

        /** The file behind the current one. Exactly one, which is the whole of the bound. */
        const val PREVIOUS_FILE_NAME = "run-journal.1.txt"

        /** Where the journal lives under `files/`, and the archive folder it is carried in. */
        const val DIRECTORY_NAME = "diagnostics"

        private const val MAX_BYTES = 128L * 1024L

        /**
         * How long a teardown will hold its thread waiting for the journal.
         *
         * Android allows about ten seconds on main before it calls the app hung, and a service being
         * destroyed has the rest of its own teardown to do inside that. Two seconds is far more than
         * an append of a few hundred bytes needs even behind an archive copy, and far short of the
         * budget it is spending.
         */
        private const val TEARDOWN_WAIT_MS = 2_000L
    }
}
