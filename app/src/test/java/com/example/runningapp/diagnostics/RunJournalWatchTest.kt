package com.example.runningapp.diagnostics

import com.example.runningapp.SessionStatus
import com.example.runningapp.run.AcquisitionBlock
import com.example.runningapp.run.AcquisitionPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class RunJournalWatchTest {

    private val idle = RunVitals()
    private val running = RunVitals(sessionStatus = SessionStatus.RUNNING, runRowId = 41)
    private val paused = running.copy(sessionStatus = SessionStatus.PAUSED)

    private fun events(before: RunVitals, after: RunVitals) =
        journalEntriesFor(before, after).map { it.event }

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
        val stopped = RunVitals(sessionStatus = SessionStatus.STOPPED, runRowId = null)

        assertEquals(listOf(RunJournalEvent.RUN_STOPPED), events(running, stopping))
        assertEquals(emptyList<RunJournalEvent>(), events(stopping, stopped))
    }

    @Test
    fun `the run that stopped is named, though the live run is already gone`() {
        val stopped = RunVitals(sessionStatus = SessionStatus.STOPPED, runRowId = null)

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
        val stopped = RunVitals(sessionStatus = SessionStatus.STOPPED, runRowId = null)

        assertEquals(
            listOf(RunJournalEvent.RUN_STOPPED, RunJournalEvent.STRAP_DISCONNECTED),
            events(connected, stopped)
        )
    }
}
