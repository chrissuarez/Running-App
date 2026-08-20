package com.example.runningapp.ui

import com.example.runningapp.analysis.Medal
import com.example.runningapp.data.RunSegmentEffortRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Segments card on a Run's page says (#71).
 *
 * The medal is the whole point of the card, and a medal is a claim about every other effort at the
 * same ground — so the cases worth pinning are the ones where two efforts are close: a tie, a
 * fourth place, and the same Run holding two of the three places itself.
 */
class RunSegmentEffortsTest {

    private fun row(
        effortId: Long,
        sessionId: Long,
        startedAtMillis: Long,
        elapsedMillis: Long,
        segmentId: Long = 1L,
        segmentName: String = "Cemetery Hill",
        distanceMeters: Double = 500.0,
    ) = RunSegmentEffortRow(
        effortId = effortId,
        segmentId = segmentId,
        segmentName = segmentName,
        distanceMeters = distanceMeters,
        sessionId = sessionId,
        startedAtMillis = startedAtMillis,
        elapsedMillis = elapsedMillis,
    )

    @Test
    fun `the quickest effort ever run at the segment takes gold`() {
        val rows = listOf(
            row(effortId = 1, sessionId = 10, startedAtMillis = 1_000, elapsedMillis = 200_000),
            row(effortId = 2, sessionId = 20, startedAtMillis = 2_000, elapsedMillis = 150_000),
        )

        val ui = runSegmentEffortsUi(rows, sessionId = 20)

        assertEquals(listOf(Medal.GOLD), ui.map { it.medal })
    }

    @Test
    fun `an effort outside the top three carries no medal at all`() {
        val rows = listOf(
            row(effortId = 1, sessionId = 10, startedAtMillis = 1_000, elapsedMillis = 100_000),
            row(effortId = 2, sessionId = 11, startedAtMillis = 2_000, elapsedMillis = 110_000),
            row(effortId = 3, sessionId = 12, startedAtMillis = 3_000, elapsedMillis = 120_000),
            row(effortId = 4, sessionId = 20, startedAtMillis = 4_000, elapsedMillis = 130_000),
        )

        val ui = runSegmentEffortsUi(rows, sessionId = 20)

        assertEquals(1, ui.size)
        assertNull(ui.single().medal)
    }

    @Test
    fun `silver and bronze go to the second and third quickest`() {
        val rows = listOf(
            row(effortId = 1, sessionId = 10, startedAtMillis = 1_000, elapsedMillis = 100_000),
            row(effortId = 2, sessionId = 20, startedAtMillis = 2_000, elapsedMillis = 110_000),
            row(effortId = 3, sessionId = 21, startedAtMillis = 3_000, elapsedMillis = 120_000),
        )

        assertEquals(Medal.SILVER, runSegmentEffortsUi(rows, sessionId = 20).single().medal)
        assertEquals(Medal.BRONZE, runSegmentEffortsUi(rows, sessionId = 21).single().medal)
    }

    @Test
    fun `matching a time already run does not take the place off it`() {
        // The record book's rule (#49), and the Segment page's: a place is the runner's until
        // somebody actually beats it, and running the same time is not beating it.
        val rows = listOf(
            row(effortId = 1, sessionId = 10, startedAtMillis = 1_000, elapsedMillis = 100_000),
            row(effortId = 2, sessionId = 20, startedAtMillis = 2_000, elapsedMillis = 100_000),
        )

        val ui = runSegmentEffortsUi(rows, sessionId = 20)

        assertEquals(Medal.SILVER, ui.single().medal)
    }

    @Test
    fun `a run that went over the same segment twice can hold two places`() {
        val rows = listOf(
            row(effortId = 1, sessionId = 20, startedAtMillis = 1_000, elapsedMillis = 100_000),
            row(effortId = 2, sessionId = 20, startedAtMillis = 5_000, elapsedMillis = 110_000),
            row(effortId = 3, sessionId = 10, startedAtMillis = 500, elapsedMillis = 130_000),
        )

        val ui = runSegmentEffortsUi(rows, sessionId = 20)

        assertEquals(listOf(Medal.GOLD, Medal.SILVER), ui.map { it.medal })
    }

    @Test
    fun `the card lists the segments in the order the run went over them`() {
        val rows = listOf(
            row(effortId = 1, sessionId = 20, startedAtMillis = 9_000, elapsedMillis = 100_000, segmentId = 2, segmentName = "The Straight"),
            row(effortId = 2, sessionId = 20, startedAtMillis = 1_000, elapsedMillis = 100_000, segmentId = 1, segmentName = "Cemetery Hill"),
        )

        val ui = runSegmentEffortsUi(rows, sessionId = 20)

        assertEquals(listOf("Cemetery Hill", "The Straight"), ui.map { it.segmentName })
    }

    @Test
    fun `efforts run by other runs are rivals and never rows of their own`() {
        val rows = listOf(
            row(effortId = 1, sessionId = 10, startedAtMillis = 1_000, elapsedMillis = 100_000),
            row(effortId = 2, sessionId = 20, startedAtMillis = 2_000, elapsedMillis = 110_000),
        )

        val ui = runSegmentEffortsUi(rows, sessionId = 20)

        assertEquals(listOf(2L), ui.map { it.effortId })
    }

    @Test
    fun `a run that went over nothing has no card to show`() {
        assertTrue(runSegmentEffortsUi(emptyList(), sessionId = 20).isEmpty())
    }

    @Test
    fun `each row says how long the stretch took and how quick that was`() {
        val rows = listOf(
            row(
                effortId = 1,
                sessionId = 20,
                startedAtMillis = 1_000,
                elapsedMillis = 150_000,
                distanceMeters = 500.0,
            )
        )

        val ui = runSegmentEffortsUi(rows, sessionId = 20).single()

        assertEquals("02:30", ui.timeLabel)
        assertEquals("5:00 /km", ui.paceLabel)
    }

    @Test
    fun `a place at one segment is never counted against efforts at another`() {
        val rows = listOf(
            row(effortId = 1, sessionId = 10, startedAtMillis = 1_000, elapsedMillis = 10_000, segmentId = 1),
            row(effortId = 2, sessionId = 10, startedAtMillis = 2_000, elapsedMillis = 11_000, segmentId = 1),
            row(effortId = 3, sessionId = 10, startedAtMillis = 3_000, elapsedMillis = 12_000, segmentId = 1),
            row(effortId = 4, sessionId = 20, startedAtMillis = 4_000, elapsedMillis = 99_000, segmentId = 2, segmentName = "The Straight"),
        )

        val ui = runSegmentEffortsUi(rows, sessionId = 20).single()

        assertEquals("The Straight", ui.segmentName)
        assertEquals(Medal.GOLD, ui.medal)
    }
}
