package com.example.runningapp.ui

import com.example.runningapp.analysis.DistancePoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two rules the combined chart's drawing rests on (#46): what the readout says under the
 * finger, and where a line is allowed to be drawn.
 *
 * Neither needs a screen. Everything else in the chart is arithmetic on the figures
 * [com.example.runningapp.analysis.DistanceChart] already worked out and tested.
 */
class RunAnalysisChartTest {

    // -- The readout -----------------------------------------------------------------------------

    @Test
    fun `the readout says how far in, then pace, heart rate and height`() {
        val readout = readoutFor(
            DistancePoint(distanceMeters = 1_240.0, paceMinPerKm = 5.7, metersAboveLowestPoint = 18.4, bpm = 148)
        )

        // A rise above the run's own low point, not an altitude — the app does not know one.
        assertEquals("1.24 km · 5:42 /km · 148 bpm · +18 m above the run's low point", readout)
    }

    @Test
    fun `the readout leaves out what the run did not record there`() {
        // A strapless run over ground with no barometer, at a point banked as rest: the distance is
        // all there is to say, and saying "0 bpm" or "0 m" would be inventing three of them.
        val readout = readoutFor(
            DistancePoint(distanceMeters = 300.0, paceMinPerKm = null, metersAboveLowestPoint = null, bpm = null)
        )

        assertEquals("300 m", readout)
    }

    // -- Where a line may be drawn ---------------------------------------------------------------

    @Test
    fun `a dropout cuts the line rather than closing up over it`() {
        // The Strap gave up for a stretch in the middle. Two lines, each keeping its own place along
        // the run — joining them would draw a heart rate through a minute nothing was measured in.
        val points = listOf(
            aPoint(0.0, bpm = 130),
            aPoint(100.0, bpm = 134),
            aPoint(200.0, bpm = null),
            aPoint(300.0, bpm = null),
            aPoint(400.0, bpm = 150),
        )

        val runs = points.stretchesOf { it.bpm }

        assertEquals(2, runs.size)
        assertEquals(listOf(0.0, 100.0), runs[0].map { it.first.distanceMeters })
        assertEquals(listOf(150), runs[1].map { it.second })
    }

    @Test
    fun `a series recorded the whole way is one line`() {
        val points = (0..4).map { aPoint(it * 100.0, bpm = 130 + it) }

        assertEquals(1, points.stretchesOf { it.bpm }.size)
    }

    @Test
    fun `a series never recorded is no line at all`() {
        val points = (0..4).map { aPoint(it * 100.0, bpm = null) }

        assertEquals(emptyList<Any>(), points.stretchesOf { it.bpm })
    }

    private fun aPoint(distanceMeters: Double, bpm: Int?) = DistancePoint(
        distanceMeters = distanceMeters,
        paceMinPerKm = null,
        metersAboveLowestPoint = null,
        bpm = bpm,
    )
}
