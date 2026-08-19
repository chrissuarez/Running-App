package com.example.runningapp.ui

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.data.Segment
import com.example.runningapp.segments.SegmentCut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentModelsTest {

    @Test
    fun `a stretch shorter than a kilometre is said in metres`() {
        assertEquals("320 m", segmentDistanceLabel(319.6))
    }

    @Test
    fun `a longer stretch is said in kilometres, as every other distance is`() {
        assertEquals("1.62 km", segmentDistanceLabel(1_620.0))
    }

    @Test
    fun `a kilometre exactly is a kilometre, not a thousand metres`() {
        assertEquals("1.00 km", segmentDistanceLabel(1_000.0))
    }

    @Test
    fun `a segment whose run is gone says so, and says the segment is fine`() {
        val orphan = segmentSourceLabel(segment(sourceSessionId = null))!!

        assertTrue(orphan.contains("deleted"))
        assertTrue(orphan.contains("unaffected"))
    }

    @Test
    fun `a segment whose run is still there says nothing about it`() {
        // True of every Segment there is, so saying it costs a line and tells the runner nothing.
        assertNull(segmentSourceLabel(segment(sourceSessionId = 12)))
    }

    @Test
    fun `a marked stretch reports its own length`() {
        val cut = SegmentCut.Cut(fixes = listOf(MapFix(51.5, -0.1), MapFix(51.6, -0.1)), distanceMeters = 450.0)

        assertEquals("450 m of this run", segmentCutSummary(cut))
    }

    @Test
    fun `a stretch across a break says what to do about it`() {
        val words = segmentCutSummary(SegmentCut.SpansABreak)

        assertTrue(words.contains("gap in the recording"))
        assertTrue(words.contains("Move a handle"))
    }

    @Test
    fun `two marks in one place say to move them apart`() {
        assertTrue(segmentCutSummary(SegmentCut.TooShort).contains("Move the handles apart"))
    }

    private fun segment(sourceSessionId: Long?) = Segment(
        id = 1,
        name = "Cemetery Hill",
        polyline = "",
        distanceMeters = 400.0,
        sourceSessionId = sourceSessionId,
        createdAtMillis = 0,
    )
}
