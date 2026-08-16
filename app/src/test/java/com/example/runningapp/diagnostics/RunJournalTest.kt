package com.example.runningapp.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunJournalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var clock = 1786863058123L

    private fun journal(maxBytes: Long = 1024L) = RunJournal(
        directory = folder.root,
        now = { clock },
        zone = { ZoneId.of("Europe/London") },
        maxBytes = maxBytes,
    )

    private fun RunJournal.read(name: String): String {
        val out = ByteArrayOutputStream()
        runBlocking { copyTo(name, out) }
        return out.toString(Charsets.UTF_8.name())
    }

    @Test
    fun `an event is one line, appended`() {
        val journal = journal()

        journal.write(RunJournalEvent.RUN_STARTED)
        clock += 1000
        journal.write(RunJournalEvent.DEMOTED, runRowId = 41)
        runBlocking { journal.flush() }

        assertEquals(
            listOf(
                "2026-08-16 07:50:58.123+01:00 run=- run-started",
                "2026-08-16 07:50:59.123+01:00 run=41 demoted",
            ),
            File(folder.root, RunJournal.CURRENT_FILE_NAME).readLines()
        )
    }

    @Test
    fun `the journal survives a fresh instance over the same folder`() {
        journal().let {
            it.write(RunJournalEvent.SERVICE_CREATED)
            runBlocking { it.flush() }
        }

        val second = journal()
        second.write(RunJournalEvent.SERVICE_DESTROYED)
        runBlocking { second.flush() }

        assertEquals(2, File(folder.root, RunJournal.CURRENT_FILE_NAME).readLines().size)
    }

    @Test
    fun `past the cap the journal rolls, keeping one file behind it`() {
        val journal = journal(maxBytes = 200L)

        repeat(20) {
            journal.write(RunJournalEvent.RUN_STARTED, runRowId = it.toLong())
            clock += 1000
        }
        runBlocking { journal.flush() }

        val current = File(folder.root, RunJournal.CURRENT_FILE_NAME)
        val previous = File(folder.root, RunJournal.PREVIOUS_FILE_NAME)
        assertTrue(previous.exists())
        assertTrue(current.length() <= 200L)
        assertTrue(previous.length() <= 200L)
        // The newest event is in the current file, and the oldest has rolled off entirely.
        assertTrue(current.readText().contains("run=19"))
        assertTrue(!current.readText().contains("run=0 ") && !previous.readText().contains("run=0 "))
    }

    @Test
    fun `what the archive is offered is the journal oldest first`() {
        val journal = journal(maxBytes = 200L)

        repeat(20) {
            journal.write(RunJournalEvent.RUN_STARTED, runRowId = it.toLong())
            clock += 1000
        }

        val names = runBlocking { journal.fileNames() }
        assertEquals(listOf(RunJournal.PREVIOUS_FILE_NAME, RunJournal.CURRENT_FILE_NAME), names)

        val text = names.joinToString("") { journal.read(it) }
        val runs = Regex("run=(\\d+)").findAll(text).map { it.groupValues[1].toInt() }.toList()
        assertEquals(runs.sorted(), runs)
    }

    @Test
    fun `a journal never written to offers the archive nothing`() {
        assertEquals(emptyList<String>(), runBlocking { journal().fileNames() })
    }

    @Test
    fun `a teardown line is on disk by the time the blocking wait returns`() {
        val journal = journal()
        journal.write(RunJournalEvent.SERVICE_CREATED)
        runBlocking { journal.flush() }

        // The writer taken up the way an archive copy takes it, so the destroyed line below is
        // queued rather than written: the shape in which a reclaimed process loses it (#310).
        val busy = journal.occupy(forMillis = 300L)

        journal.write(RunJournalEvent.SERVICE_DESTROYED)
        journal.flushBlocking()

        assertTrue(
            File(folder.root, RunJournal.CURRENT_FILE_NAME).readText().contains("service-destroyed")
        )
        busy.join()
    }

    @Test
    fun `a blocking wait that runs out of time gives up rather than throwing`() {
        val journal = journal()
        journal.write(RunJournalEvent.SERVICE_CREATED)
        runBlocking { journal.flush() }

        val busy = journal.occupy(forMillis = 1000L)

        // An ordinary line, so the writer is still held when the wait below is taken: a decisive
        // one would have waited the occupier out on its own and left nothing to time out on.
        journal.write(RunJournalEvent.RUN_STOPPED)
        // Returns, and returns on time: a teardown must not be held up by a journal it cannot get
        // written, and must not be brought down by one either.
        val waited = measureTimeMillis { journal.flushBlocking(timeoutMs = 50L) }
        assertTrue("waited ${waited}ms", waited < 500L)

        busy.join()
    }

    @Test
    fun `an event read by its absence is on disk by the time write returns`() {
        val journal = journal()
        journal.write(RunJournalEvent.SERVICE_CREATED)
        runBlocking { journal.flush() }

        // The writer taken up the way an archive copy takes it, so a queued line would still be
        // queued here: the shape in which a reclaimed process loses one (#309, #310).
        val busy = journal.occupy(forMillis = 300L)

        journal.write(RunJournalEvent.RUN_FINALIZED, runRowId = 41)

        assertTrue(
            "run-finalized was still only queued when write returned",
            File(folder.root, RunJournal.CURRENT_FILE_NAME).readText().contains("run-finalized")
        )
        busy.join()
    }

    @Test
    fun `an ordinary event does not hold up whoever wrote it`() {
        val journal = journal()
        journal.write(RunJournalEvent.SERVICE_CREATED)
        runBlocking { journal.flush() }

        val busy = journal.occupy(forMillis = 500L)

        // Back before the writer is free again: the wait is for the lines a reader reasons about
        // the absence of, and every other call site stays as cheap as it was.
        val waited = measureTimeMillis { journal.write(RunJournalEvent.RUN_STARTED, runRowId = 41) }
        assertTrue("waited ${waited}ms", waited < 250L)
        assertTrue(busy.isAlive)

        busy.join()
    }

    @Test
    fun `an ordinary line queued first lands with the one that is waited for`() {
        val journal = journal()
        journal.write(RunJournalEvent.SERVICE_CREATED)
        runBlocking { journal.flush() }

        val busy = journal.occupy(forMillis = 300L)

        // One writer thread, in the order the writes were asked for: waiting for the decisive line
        // has already landed everything queued in front of it, which is what keeps the set of
        // events that wait down to three.
        journal.write(RunJournalEvent.RUN_STOPPED, runRowId = 41)
        journal.write(RunJournalEvent.RUN_FINALIZED, runRowId = 41)

        assertEquals(
            listOf("service-created", "run-stopped", "run-finalized"),
            File(folder.root, RunJournal.CURRENT_FILE_NAME).readLines().map { it.substringAfterLast(' ') }
        )
        busy.join()
    }

    /**
     * Hold the journal's one writer thread for [forMillis], from a thread of the test's own.
     *
     * Through [RunJournal.copyTo] because that is a real occupier — it is what the archive does —
     * and because the writer is private, which is the point: a caller cannot queue-jump it.
     */
    private fun RunJournal.occupy(forMillis: Long): Thread {
        val holding = CountDownLatch(1)
        val slow = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) {
                holding.countDown()
                Thread.sleep(forMillis)
            }
        }
        val thread = thread { runBlocking { copyTo(RunJournal.CURRENT_FILE_NAME, slow) } }
        assertTrue("the writer was never taken up", holding.await(5, TimeUnit.SECONDS))
        return thread
    }
}
