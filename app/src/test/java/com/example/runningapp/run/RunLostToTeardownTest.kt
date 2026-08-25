package com.example.runningapp.run

import com.example.runningapp.SessionStatus
import com.example.runningapp.ZoneSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which teardowns took a Run with them, and what is left of the Run they took (#309, #314).
 */
class RunLostToTeardownTest {

    @Test
    fun `a teardown that arrives while the Run is recording loses it`() {
        assertEquals(
            RunLostToTeardown.HasRow(9133L),
            runLostToTeardown(SessionStatus.RUNNING, 9133L),
        )
    }

    @Test
    fun `a Run paused when the teardown came is lost too`() {
        // Paused is not over: the runner is standing at a crossing, and nothing will resume a Run
        // whose service has gone.
        assertEquals(
            RunLostToTeardown.HasRow(9133L),
            runLostToTeardown(SessionStatus.PAUSED, 9133L),
        )
    }

    @Test
    fun `the teardown that follows an ordinary stop loses nothing`() {
        assertNull(runLostToTeardown(SessionStatus.STOPPED, 9133L))
        assertNull(runLostToTeardown(SessionStatus.IDLE, null))
    }

    @Test
    fun `a Run still waiting on its row id was stopped, not lost`() {
        // STOPPING is the runner's own STOP arriving before the insert did. The finalize is held
        // and goes out when the id lands, on a scope that outlives the service.
        assertNull(runLostToTeardown(SessionStatus.STOPPING, 9133L))
    }

    private fun heldSample(second: Long) = PendingRowWork.SaveHrSample(
        HrSampleReading(
            elapsedSeconds = second,
            atMillis = 1_700_000_000_000L + second * 1_000L,
            rawBpm = 132,
            smoothedBpm = 130,
            connectionStatus = "Connected",
        )
    )

    private val heldPause = PendingRowWork.SavePause(
        PauseTaken(startedAtMillis = 1_700_000_000_100L, endedAtMillis = 1_700_000_000_400L)
    )

    private val heldFinalize = PendingRowWork.Finalize(
        RunTotals(
            durationSeconds = 0,
            pausedSeconds = 0,
            endedAtMillis = 1_700_000_000_500L,
            runType = null,
            averageBpm = 0,
            maxBpm = 0,
            zoneSeconds = ZoneSeconds(),
            noDataSeconds = 0,
            effortScore = null,
            walkBreaks = 0,
        )
    )

    @Test
    fun `a Run recording with no row yet is lost with its row still on its way`() {
        // #314: the insert is in flight and the seconds so far are buffered in memory. There is no
        // row to finish, but there is a row about to exist and something to finish it from — so
        // this is a loss with an answer of its own, not a loss with nothing to say.
        assertEquals(
            RunLostToTeardown.AwaitingItsRow(emptyList()),
            runLostToTeardown(SessionStatus.RUNNING, null),
        )
    }

    @Test
    fun `a stopped Run with no row is not a loss even though it has no row`() {
        assertNull(runLostToTeardown(SessionStatus.STOPPING, null))
        assertNull(runLostToTeardown(SessionStatus.IDLE, null))
    }

    @Test
    fun `a Run holding a banked second has something to save`() {
        val lost = runLostToTeardown(SessionStatus.RUNNING, null, listOf(heldSample(1)))
                as RunLostToTeardown.AwaitingItsRow

        assertTrue(lost.hasSomethingToSave)
        assertFalse(lost.runnerStopped)
    }

    @Test
    fun `a Run holding only a Pause has nothing to save`() {
        // A Pause is bookkeeping about seconds, not a second. With no sample and no fix behind it
        // the rebuild has nothing to measure, so the runner is not sent looking for a Run.
        val lost = runLostToTeardown(SessionStatus.RUNNING, null, listOf(heldPause))
                as RunLostToTeardown.AwaitingItsRow

        assertFalse(lost.hasSomethingToSave)
    }

    @Test
    fun `a Run recorded without a Strap has nothing to save`() {
        val lost = runLostToTeardown(SessionStatus.RUNNING, null)
                as RunLostToTeardown.AwaitingItsRow

        assertFalse(lost.hasSomethingToSave)
    }

    @Test
    fun `a held finalize says the runner stopped it after all`() {
        // The STOP was dispatched between the teardown's snapshot and its look at the held work,
        // so the state still says RUNNING while the Run's own totals sit in the buffer.
        val lost = runLostToTeardown(SessionStatus.RUNNING, null, listOf(heldSample(1), heldFinalize))
                as RunLostToTeardown.AwaitingItsRow

        assertTrue(lost.runnerStopped)
    }

    @Test
    fun `a Run with a row always has something to save`() {
        assertTrue(runLostToTeardown(SessionStatus.RUNNING, 9133L)!!.hasSomethingToSave)
    }
}
