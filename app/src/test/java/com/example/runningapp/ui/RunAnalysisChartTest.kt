package com.example.runningapp.ui

import com.example.runningapp.analysis.DistanceChart
import com.example.runningapp.analysis.DistancePoint
import com.example.runningapp.analysis.DistanceTrace
import com.example.runningapp.analysis.ElevationBand
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

    // -- What the heading promises ---------------------------------------------------------------

    @Test
    fun `the heading names all three lines when the run recorded all three`() {
        assertEquals("Pace, Heart Rate & Elevation", headingFor(aChart(pace = 5.5, bpm = 148, height = 12.0)))
    }

    @Test
    fun `the heading leaves out a line the run did not record`() {
        // A strapless outdoor run — first-class since #110 — and the same run over ground with no
        // height recorded. Neither draws the line it is missing, so neither heading may promise it.
        assertEquals("Pace & Elevation", headingFor(aChart(pace = 5.5, bpm = null, height = 12.0)))
        assertEquals("Pace & Heart Rate", headingFor(aChart(pace = 5.5, bpm = 148, height = null)))
        assertEquals("Pace", headingFor(aChart(pace = 5.5, bpm = null, height = null)))
    }

    @Test
    fun `a chart with nothing measured over its ground says only how far`() {
        assertEquals("Distance", headingFor(aChart(pace = null, bpm = null, height = null)))
    }

    // -- Where the finger is, in the Run's own units (#48) ---------------------------------------

    @Test
    fun `a finger across the plot reads from the start of the run to the end of it`() {
        // A plot two hundred pixels wide, starting thirty-six in — the chart's left gutter.
        assertEquals(0f, 36f.toFractionOfRun(plotLeft = 36f, plotWidth = 200f), 1e-6f)
        assertEquals(0.5f, 136f.toFractionOfRun(plotLeft = 36f, plotWidth = 200f), 1e-6f)
        assertEquals(1f, 236f.toFractionOfRun(plotLeft = 36f, plotWidth = 200f), 1e-6f)
    }

    @Test
    fun `a finger dragged off either side of the plot reads its nearest end`() {
        // Into the gutter, and past the right edge of the chart altogether: both are the runner
        // still dragging, and the readout follows to the end of the run rather than blanking.
        assertEquals(0f, 0f.toFractionOfRun(plotLeft = 36f, plotWidth = 200f), 1e-6f)
        assertEquals(1f, 500f.toFractionOfRun(plotLeft = 36f, plotWidth = 200f), 1e-6f)
    }

    @Test
    fun `a plot with no width to it reads the start of the run`() {
        // A chart measured before it has been laid out. Nothing to divide by, and no place along the
        // run that a pixel means — so the run's own beginning, rather than a not-a-number.
        assertEquals(0f, 120f.toFractionOfRun(plotLeft = 36f, plotWidth = 0f), 1e-6f)
    }

    /** A one-stretch chart that recorded exactly the series handed in. */
    private fun aChart(pace: Double?, bpm: Int?, height: Double?) = DistanceChart(
        traces = listOf(
            DistanceTrace(
                (0..2).map { DistancePoint(it * 100.0, paceMinPerKm = pace, metersAboveLowestPoint = height, bpm = bpm) }
            )
        ),
        distanceMetersSpan = 200.0,
        bpmFloor = 100,
        bpmCeiling = 160,
        paceFastestMinPerKm = 5.0,
        paceSlowestMinPerKm = 6.0,
        elevationBand = height?.let { ElevationBand(floorMeters = 0.0, ceilingMeters = 20.0) },
    )

    private fun aPoint(distanceMeters: Double, bpm: Int?) = DistancePoint(
        distanceMeters = distanceMeters,
        paceMinPerKm = null,
        metersAboveLowestPoint = null,
        bpm = bpm,
    )
}
