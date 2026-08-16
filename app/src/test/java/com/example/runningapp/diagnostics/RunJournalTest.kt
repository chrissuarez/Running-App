package com.example.runningapp.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
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
}
