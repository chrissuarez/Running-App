package com.example.runningapp.ui

import com.example.runningapp.data.RunnerSession
import com.example.runningapp.run.RunMode
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRowStatsTest {

    private fun runOf(
        runMode: RunMode = RunMode.OUTDOOR,
        distanceKm: Double = 8.2,
        durationSeconds: Long = 3_090,
        movingTimeSeconds: Long? = null,
        avgBpm: Int = 148,
        inTargetSeconds: Long = 1_992,
    ) = RunnerSession(
        startTime = 0L,
        endTime = 1L,
        durationSeconds = durationSeconds,
        movingTimeSeconds = movingTimeSeconds,
        avgBpm = avgBpm,
        runMode = runMode.settingValue,
        distanceKm = distanceKm,
        zone2Seconds = inTargetSeconds,
        targetZone = 2,
    )

    @Test
    fun `the row reads distance, pace, heart rate, target - in that order`() {
        val stats = historyRowStats(runOf())

        assertEquals(listOf("Dist", "Pace", "Avg HR", "Target"), stats.map { it.label })
        assertEquals(listOf("8.20", "6:17", "148", "33:12"), stats.map { it.value })
    }

    @Test
    fun `a treadmill run with a stated distance reads the same four columns`() {
        val stats = historyRowStats(runOf(runMode = RunMode.TREADMILL, distanceKm = 5.0, durationSeconds = 1_800))

        assertEquals(listOf("Dist", "Pace", "Avg HR", "Target"), stats.map { it.label })
        assertEquals("5.00", stats[0].value)
        assertEquals("6:00", stats[1].value)
    }

    @Test
    fun `a treadmill run nobody stated a distance for keeps its columns and dashes the two it lacks`() {
        val stats = historyRowStats(runOf(runMode = RunMode.TREADMILL, distanceKm = 0.0, durationSeconds = 2_530))

        assertEquals(listOf("Dist", "Pace", "Avg HR", "Target"), stats.map { it.label })
        assertEquals(listOf("--", "--", "148", "33:12"), stats.map { it.value })
    }

    @Test
    fun `an outdoor run whose GPS recorded nothing dashes too, rather than claiming zero`() {
        val stats = historyRowStats(runOf(runMode = RunMode.OUTDOOR, distanceKm = 0.0))

        assertEquals("--", stats[0].value)
        assertEquals("--", stats[1].value)
    }

    @Test
    fun `pace is measured over moving time when the run has one`() {
        val stats = historyRowStats(runOf(distanceKm = 5.0, durationSeconds = 1_800, movingTimeSeconds = 1_500))

        assertEquals("5:00", stats[1].value)
    }

    // --- The width the row has to survive (#232) ---

    @Test
    fun `a stats row that fits is left at its own size`() {
        assertEquals(1f, fitToWidthScale(contentWidth = 300, availableWidth = 400))
        assertEquals(1f, fitToWidthScale(contentWidth = 400, availableWidth = 400))
    }

    @Test
    fun `a stats row too wide for its column is shrunk exactly enough to fit`() {
        assertEquals(0.5f, fitToWidthScale(contentWidth = 800, availableWidth = 400))
        assertEquals(0.8f, fitToWidthScale(contentWidth = 500, availableWidth = 400))
    }

    @Test
    fun `the columns share the row equally, and the gaps come out first`() {
        assertEquals(100, statColumnWidth(rowWidth = 424, gapWidth = 8, columns = 4))
        // A row that will not divide evenly leaves the remainder unclaimed rather than widening one
        // column: four equal columns is the whole point.
        assertEquals(100, statColumnWidth(rowWidth = 427, gapWidth = 8, columns = 4))
    }

    @Test
    fun `a row with no width limit yet has no width to share out`() {
        assertEquals(Int.MAX_VALUE, statColumnWidth(rowWidth = Int.MAX_VALUE, gapWidth = 8, columns = 4))
    }

    @Test
    fun `a row narrower than its own gaps gives its columns nothing rather than a negative width`() {
        assertEquals(0, statColumnWidth(rowWidth = 10, gapWidth = 8, columns = 4))
    }

    @Test
    fun `on the narrowest screen the app supports, a column is 41dp wide`() {
        // 320dp at 2x density: page padding, card padding, the 56dp square and its 12dp gap leave
        // 188dp of row, and four columns and three 8dp gaps have to live in it (#232).
        val rowWidthPx = 188 * 2
        assertEquals(82, statColumnWidth(rowWidth = rowWidthPx, gapWidth = 8 * 2, columns = 4))
    }

    @Test
    fun `nothing to measure, or nothing measuring it, leaves the size alone`() {
        assertEquals(1f, fitToWidthScale(contentWidth = 0, availableWidth = 400))
        assertEquals(1f, fitToWidthScale(contentWidth = 300, availableWidth = 0))
        assertEquals(1f, fitToWidthScale(contentWidth = 300, availableWidth = Int.MAX_VALUE))
    }
}
