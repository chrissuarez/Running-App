package com.example.runningapp.ui

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.data.Segment
import com.example.runningapp.data.SegmentEffortRow
import com.example.runningapp.segments.SegmentCut
import java.time.ZoneId
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

    // --- The times run at a Segment (#70) ---

    private val london = ZoneId.of("Europe/London")

    /** Ten past eight on the morning of 3 June 2024, in London. */
    private val aMorning = 1_717_398_600_000L

    private fun effort(id: Long, day: Long, elapsedMillis: Long, offsetSeconds: Int? = 3_600) =
        SegmentEffortRow(
            effortId = id,
            sessionId = id,
            startedAtMillis = aMorning + day * 86_400_000L,
            elapsedMillis = elapsedMillis,
            ranAtUtcOffsetSeconds = offsetSeconds,
        )

    @Test
    fun `the efforts are newest first, with the quickest of them marked`() {
        val efforts = listOf(
            effort(1, day = 0, elapsedMillis = 100_000),
            effort(2, day = 1, elapsedMillis = 92_000),
            effort(3, day = 2, elapsedMillis = 95_000),
        )

        val shown = segmentEffortsUi(efforts, distanceMeters = 400.0, zone = london)

        assertEquals(listOf(3L, 2L, 1L), shown.map { it.effortId })
        assertEquals(listOf(false, true, false), shown.map { it.isRecord })
    }

    @Test
    fun `an effort says the day it was run, in the runner's own day`() {
        // Ten past nine in the morning in Madrid, which is ten past eight in London — the same
        // instant, and the same date either way. What is being pinned is that the Run's own offset
        // is what the date is read under (#304).
        val shown = segmentEffortsUi(
            listOf(effort(1, day = 0, elapsedMillis = 90_000, offsetSeconds = 7_200)),
            distanceMeters = 400.0,
            zone = london,
        )

        assertEquals("3 Jun 2024", shown.single().dateLabel)
    }

    @Test
    fun `an effort says its time and its pace`() {
        val shown = segmentEffortsUi(
            listOf(effort(1, day = 0, elapsedMillis = 92_000)),
            distanceMeters = 400.0,
            zone = london,
        ).single()

        assertEquals("01:32", shown.timeLabel)
        // Four hundred metres in ninety-two seconds is three minutes fifty a kilometre.
        assertEquals("3:50 /km", shown.paceLabel)
    }

    @Test
    fun `matching the quickest time does not take the record off it`() {
        val efforts = listOf(
            effort(1, day = 0, elapsedMillis = 92_000),
            effort(2, day = 1, elapsedMillis = 92_000),
        )

        val shown = segmentEffortsUi(efforts, distanceMeters = 400.0, zone = london)

        assertEquals(1L, shown.single { it.isRecord }.effortId)
    }

    @Test
    fun `the record is the quickest time ever run at the Segment`() {
        val efforts = listOf(
            effort(1, day = 0, elapsedMillis = 100_000),
            effort(2, day = 1, elapsedMillis = 92_400),
        )

        assertEquals("01:32", segmentRecordLabel(efforts))
    }

    @Test
    fun `a Segment nobody has run has no record and says so`() {
        assertNull(segmentRecordLabel(emptyList()))
        assertEquals("No efforts yet", segmentEffortCountLabel(0))
        assertTrue(NO_SEGMENT_EFFORTS_MESSAGE.contains("not run this segment yet"))
    }

    @Test
    fun `one effort is not one efforts`() {
        assertEquals("1 effort", segmentEffortCountLabel(1))
        assertEquals("14 efforts", segmentEffortCountLabel(14))
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
