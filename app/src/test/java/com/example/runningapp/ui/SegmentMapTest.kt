package com.example.runningapp.ui

import com.example.runningapp.analysis.MapFix
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the Segment map's camera is asked to hold (#69).
 *
 * The one thing about this map that is worth pinning without a phone: the frame has to survive a
 * handle being dragged, and a test is the only place that can watch it not move.
 */
class SegmentMapTest {

    private val runBehind = listOf(
        listOf(MapFix(51.50, -0.10), MapFix(51.51, -0.10), MapFix(51.52, -0.10)),
        listOf(MapFix(51.60, -0.10), MapFix(51.61, -0.10)),
    )

    @Test
    fun `the frame is the run, so it does not move when a handle does`() {
        val wide = segmentFramingFixes(segment = runBehind.first(), runBehind = runBehind)
        val narrow = segmentFramingFixes(
            segment = runBehind.first().subList(0, 2),
            runBehind = runBehind,
        )

        assertEquals(wide, narrow)
    }

    @Test
    fun `the frame holds the whole run, breaks and all`() {
        assertEquals(runBehind.flatten(), segmentFramingFixes(runBehind.first(), runBehind))
    }

    @Test
    fun `a segment's own page, with no run behind it, is framed on the segment`() {
        val ground = listOf(MapFix(51.50, -0.10), MapFix(51.51, -0.10))

        assertEquals(ground, segmentFramingFixes(segment = ground, runBehind = emptyList()))
    }
}
