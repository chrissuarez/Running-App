package com.example.runningapp.diagnostics

import com.example.runningapp.SessionStatus
import com.example.runningapp.run.AcquisitionBlock
import com.example.runningapp.run.AcquisitionPhase
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunJournalWatchTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val idle = JournaledState()
    private val running = JournaledState(sessionStatus = SessionStatus.RUNNING, runRowId = 41)
    private val paused = running.copy(sessionStatus = SessionStatus.PAUSED)
    private val strap = AcquisitionPhase.Connected(address = "AA:BB", name = "HRM-Pro")

    private fun events(before: JournaledState, after: JournaledState) =
        journalEntriesFor(before, after).map { it.event }

    /**
     * The watch fed a run of publishes the way the service feeds it, answering with what reached
     * the journal as `run=<id> <event>` — which is the form a lost Run is diagnosed in, and the
     * only form that shows whether `grep run=41` would have found a line.
     */
    private fun journaled(vararg published: JournaledState): List<String> {
        val journal = RunJournal(directory = folder.root, zone = { ZoneId.of("Europe/London") })
        val watch = RunJournalWatch(journal)
        published.forEach(watch::observe)
        val out = ByteArrayOutputStream()
        runBlocking {
            journal.flush()
            journal.copyTo(RunJournal.CURRENT_FILE_NAME, out)
        }
        return out.toString(Charsets.UTF_8.name())
            .lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val fields = line.split(" ")
                "${fields[2]} ${fields[3].removeSuffix(":")}"
            }
    }

    @Test
    fun `nothing changing writes nothing`() {
        assertEquals(emptyList<RunJournalEntry>(), journalEntriesFor(running, running))
    }

    @Test
    fun `a run beginning is one started line`() {
        assertEquals(listOf(RunJournalEvent.RUN_STARTED), events(idle, running.copy(runRowId = null)))
    }

    @Test
    fun `the row landing is its own line, so a run with no row is visible as one`() {
        val started = running.copy(runRowId = null)
        assertEquals(
            listOf(RunJournalEntry(RunJournalEvent.RUN_ROW_CREATED, runRowId = 41)),
            journalEntriesFor(started, running)
        )
    }

    @Test
    fun `pausing and resuming are told apart`() {
        assertEquals(listOf(RunJournalEvent.RUN_PAUSED), events(running, paused))
        assertEquals(listOf(RunJournalEvent.RUN_RESUMED), events(paused, running))
    }

    @Test
    fun `a run stopping is one line, not one per step of the stop`() {
        val stopping = running.copy(sessionStatus = SessionStatus.STOPPING)
        val stopped = JournaledState(sessionStatus = SessionStatus.STOPPED, runRowId = null)

        assertEquals(listOf(RunJournalEvent.RUN_STOPPED), events(running, stopping))
        assertEquals(emptyList<RunJournalEvent>(), events(stopping, stopped))
    }

    @Test
    fun `the run that stopped is named, though the live run is already gone`() {
        val stopped = JournaledState(sessionStatus = SessionStatus.STOPPED, runRowId = null)

        assertEquals(
            listOf(RunJournalEntry(RunJournalEvent.RUN_STOPPED, runRowId = 41)),
            journalEntriesFor(running, stopped)
        )
    }

    @Test
    fun `a strap arriving and leaving is a line each, named`() {
        val connected = running.copy(
            acquisition = AcquisitionPhase.Connected(address = "AA:BB", name = "HRM-Pro")
        )

        assertEquals(
            listOf(RunJournalEntry(RunJournalEvent.STRAP_CONNECTED, runRowId = 41, detail = "HRM-Pro AA:BB")),
            journalEntriesFor(running, connected)
        )
        assertEquals(
            listOf(RunJournalEntry(RunJournalEvent.STRAP_DISCONNECTED, runRowId = 41, detail = "HRM-Pro AA:BB")),
            journalEntriesFor(connected, running)
        )
    }

    @Test
    fun `a retry is not a disconnection told twice`() {
        val connected = running.copy(
            acquisition = AcquisitionPhase.Connected(address = "AA:BB", name = "HRM-Pro")
        )
        val retrying = running.copy(
            acquisition = AcquisitionPhase.Retrying(
                address = "AA:BB",
                name = "HRM-Pro",
                promoteOnVerify = false,
                attempt = 1,
                dueAt = 0L,
                announcedDelayMs = 1000L,
                nextDelayMs = 2000L,
            )
        )
        val retryingAgain = running.copy(
            acquisition = (retrying.acquisition as AcquisitionPhase.Retrying).copy(attempt = 2)
        )

        assertEquals(listOf(RunJournalEvent.STRAP_DISCONNECTED), events(connected, retrying))
        assertEquals(emptyList<RunJournalEvent>(), events(retrying, retryingAgain))
    }

    @Test
    fun `giving up and being blocked each say so once`() {
        val gaveUp = running.copy(acquisition = AcquisitionPhase.GaveUp)
        val blocked = running.copy(
            acquisition = AcquisitionPhase.Blocked(AcquisitionBlock.BluetoothUnavailable)
        )

        assertEquals(listOf(RunJournalEvent.ACQUISITION_GAVE_UP), events(running, gaveUp))
        assertEquals(
            listOf(RunJournalEntry(RunJournalEvent.ACQUISITION_BLOCKED, runRowId = 41, detail = "BluetoothUnavailable")),
            journalEntriesFor(running, blocked)
        )
    }

    @Test
    fun `the strap going away as the run stops is told alongside the stop`() {
        val connected = running.copy(
            acquisition = AcquisitionPhase.Connected(address = "AA:BB", name = "HRM-Pro")
        )
        val stopped = JournaledState(sessionStatus = SessionStatus.STOPPED, runRowId = null)

        assertEquals(
            listOf(RunJournalEvent.RUN_STOPPED, RunJournalEvent.STRAP_DISCONNECTED),
            events(connected, stopped)
        )
    }

    @Test
    fun `the strap a run let go of names that run, though its row is already cleared`() {
        // What the phone actually does on a normal stop: the Run publishes its cleared row, and the
        // release of the Strap arrives on the publish after it. Named `run=-`, the closing line of
        // every strapped Run falls out of `grep run=41` in a journal holding more than one Run.
        assertEquals(
            listOf(
                "run=41 run-started",
                "run=41 run-row-created",
                "run=41 strap-connected",
                "run=41 run-stopped",
                "run=41 strap-disconnected",
            ),
            journaled(
                running,
                running.copy(acquisition = strap),
                JournaledState(sessionStatus = SessionStatus.STOPPED, acquisition = strap),
                JournaledState(sessionStatus = SessionStatus.STOPPED),
            )
        )
    }

    @Test
    fun `a strap that comes and goes with no run running names no run`() {
        assertEquals(
            listOf("run=- strap-connected", "run=- strap-disconnected"),
            journaled(idle.copy(acquisition = strap), idle)
        )
    }

    @Test
    fun `a strap connecting after a run has ended is not named for that run`() {
        // The gap is closed by remembering the Run a connection was made during, and a connection
        // made during no Run remembers none: a journal that guessed here would be a journal lying
        // about which Run had a Strap on (#310).
        assertEquals(
            listOf(
                "run=41 run-started",
                "run=41 run-row-created",
                "run=41 run-stopped",
                "run=- strap-connected",
                "run=- strap-disconnected",
            ),
            journaled(
                running,
                JournaledState(sessionStatus = SessionStatus.STOPPED),
                JournaledState(sessionStatus = SessionStatus.STOPPED, acquisition = strap),
                JournaledState(sessionStatus = SessionStatus.STOPPED),
            )
        )
    }

    @Test
    fun `a strap worn from before the run is released to the live run, not to no run`() {
        assertEquals(
            listOf(
                "run=- strap-connected",
                "run=41 run-started",
                "run=41 run-row-created",
                "run=41 strap-disconnected",
            ),
            journaled(idle.copy(acquisition = strap), running.copy(acquisition = strap), running)
        )
    }
}
