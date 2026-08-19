package com.example.runningapp.segments

import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.Segment
import com.example.runningapp.data.SegmentEffort
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
import com.example.runningapp.routes.RoutePoint
import com.example.runningapp.routes.RoutePolyline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Runs are put to a Segment, when, and what a second scan leaves behind (#70).
 *
 * The matching itself is pinned next door ([SegmentMatchingTest]). What is pinned here is everything
 * around it: who is eligible, that a Segment arrives with its history already measured, and that
 * running the whole thing again changes nothing — which is the promise the backfill is made of.
 */
class SegmentTimingTest {

    /** A hundred metres due north from one spot, which is both the Segment and what gets run. */
    private val ground = (0..10).map { RoutePoint(51.5 + it * 0.00009, -0.1, elevationMeters = null) }

    private val segment = Segment(
        id = 7,
        name = "The hill",
        polyline = RoutePolyline.encode(ground),
        distanceMeters = 100.0,
        sourceSessionId = 1,
        createdAtMillis = 0L,
    )

    /** A Run straight up the ground above, setting off before it and carrying on past it. */
    private fun ranIt(sessionId: Long): List<TrackPoint> = (-4..14).mapIndexed { i, step ->
        TrackPoint(
            sessionId = sessionId,
            latitude = 51.5 + step * 0.00009,
            longitude = -0.1,
            horizontalAccuracyMeters = 5f,
            timestampMillis = 1_700_000_000_000L + i * 4_000L,
            source = TrackPointSource.GPS,
        )
    }

    private fun aRun(id: Long, isWalk: Boolean = false, runMode: String = "outdoor", finished: Boolean = true) =
        RunnerSession(
            id = id,
            startTime = 1_700_000_000_000L,
            endTime = if (finished) 1_700_000_100_000L else 0L,
            durationSeconds = 100,
            runMode = runMode,
            isWalk = isWalk,
        )

    /**
     * The tables this pass reads and writes, in memory — with the two behaviours that matter: a
     * pair's efforts are replaced rather than added to, and each side's debt is a row of its own.
     */
    private class Book(
        val segments: List<Segment>,
        var runs: List<RunnerSession>,
        val tracks: Map<Long, List<TrackPoint>>,
    ) : SegmentTimingStore {
        val rows = mutableMapOf<Pair<Long, Long>, List<SegmentEffort>>()
        val segmentsTimed = mutableSetOf<Long>()
        val runsTimed = mutableSetOf<Long>()

        val all: List<SegmentEffort> get() = rows.values.flatten()

        override suspend fun segments() = segments
        override suspend fun segment(segmentId: Long) = segments.firstOrNull { it.id == segmentId }
        override suspend fun segmentsMissingHistory() = segments.filterNot { it.id in segmentsTimed }
        override suspend fun runs() = runs
        override suspend fun run(sessionId: Long) = runs.firstOrNull { it.id == sessionId }
        override suspend fun runsMissingTiming() =
            runs.filter { it.endTime > 0 && it.id !in runsTimed }.map { it.id }

        override suspend fun track(sessionId: Long) = tracks[sessionId].orEmpty()

        override suspend fun replaceEfforts(segmentId: Long, sessionId: Long, efforts: List<SegmentEffort>) {
            rows[segmentId to sessionId] = efforts
        }

        override suspend fun markSegmentTimed(segmentId: Long) {
            segmentsTimed += segmentId
        }

        override suspend fun markRunTimed(sessionId: Long) {
            runsTimed += sessionId
        }
    }

    private fun book(
        runs: List<RunnerSession>,
        segments: List<Segment> = listOf(segment),
        tracks: Map<Long, List<TrackPoint>> = runs.associate { it.id to ranIt(it.id) },
    ) = Book(segments, runs, tracks)

    @Test
    fun `a new Segment is born with its history already measured`() = runTest {
        val book = book(listOf(aRun(1), aRun(2), aRun(3)))

        SegmentTiming(book).timeAgainstHistory(segment.id)

        assertEquals(3, book.all.size)
        assertEquals(setOf(1L, 2L, 3L), book.all.map { it.sessionId }.toSet())
        assertTrue(book.all.all { it.segmentId == segment.id })
    }

    @Test
    fun `measuring the same history again leaves the same efforts`() = runTest {
        val book = book(listOf(aRun(1), aRun(2)))
        val timing = SegmentTiming(book)

        timing.timeAgainstHistory(segment.id)
        val first = book.all.map { it.startedAtMillis to it.finishedAtMillis }
        timing.timeAgainstHistory(segment.id)

        assertEquals(2, book.all.size)
        assertEquals(first, book.all.map { it.startedAtMillis to it.finishedAtMillis })
    }

    @Test
    fun `a Walk holds no efforts`() = runTest {
        val book = book(listOf(aRun(1, isWalk = true)))

        SegmentTiming(book).timeAgainstHistory(segment.id)

        assertEquals(emptyList<SegmentEffort>(), book.all)
    }

    @Test
    fun `a treadmill Run holds no efforts`() = runTest {
        val book = book(listOf(aRun(1, runMode = "treadmill")))

        SegmentTiming(book).timeAgainstHistory(segment.id)

        assertEquals(emptyList<SegmentEffort>(), book.all)
    }

    @Test
    fun `a Run still being recorded holds no efforts`() = runTest {
        val book = book(listOf(aRun(1, finished = false)))

        SegmentTiming(book).timeAgainstHistory(segment.id)

        assertEquals(emptyList<SegmentEffort>(), book.all)
    }

    @Test
    fun `a Run that finishes is put to every Segment`() = runTest {
        val other = segment.copy(id = 9, name = "The same hill again")
        val book = book(listOf(aRun(1)), segments = listOf(segment, other))

        SegmentTiming(book).timeAgainstEverySegment(1)

        assertEquals(setOf(7L, 9L), book.all.map { it.segmentId }.toSet())
    }

    @Test
    fun `marking a Run a Walk takes its efforts off every Segment`() = runTest {
        val book = book(listOf(aRun(1)))
        SegmentTiming(book).timeAgainstEverySegment(1)
        assertEquals(1, book.all.size)

        book.runs = listOf(aRun(1, isWalk = true))
        SegmentTiming(book).timeAgainstEverySegment(1)

        assertEquals(emptyList<SegmentEffort>(), book.all)
    }

    @Test
    fun `a Run that never went near a Segment writes nothing`() = runTest {
        val book = book(listOf(aRun(1)), tracks = mapOf(1L to ranIt(1).map { it.copy(longitude = -0.2) }))

        SegmentTiming(book).timeAgainstHistory(segment.id)

        assertEquals(emptyList<SegmentEffort>(), book.all)
    }

    @Test
    fun `the effort is the time between the gates`() = runTest {
        val book = book(listOf(aRun(1)))

        SegmentTiming(book).timeAgainstHistory(segment.id)

        val effort = book.all.single()
        // Ten metres a fix, four seconds a fix: a hundred metres is forty seconds.
        assertEquals(40_000L, effort.finishedAtMillis - effort.startedAtMillis)
    }

    // --- The debts either side carries, and the launch pass that pays them ---

    @Test
    fun `a Segment walked against history owes nothing afterwards`() = runTest {
        val book = book(listOf(aRun(1)))

        SegmentTiming(book).timeAgainstHistory(segment.id)

        assertEquals(emptyList<Segment>(), book.segmentsMissingHistory())
    }

    @Test
    fun `a Run walked against the Segments owes nothing afterwards`() = runTest {
        val book = book(listOf(aRun(1)))

        SegmentTiming(book).timeAgainstEverySegment(1)

        assertEquals(emptyList<Long>(), book.runsMissingTiming())
    }

    @Test
    fun `a Run with no Segments to be walked against still owes nothing`() = runTest {
        val book = book(listOf(aRun(1)), segments = emptyList())

        SegmentTiming(book).timeAgainstEverySegment(1)

        assertEquals(emptyList<Long>(), book.runsMissingTiming())
    }

    @Test
    fun `the launch pass measures a Segment cut before efforts existed`() = runTest {
        val book = book(listOf(aRun(1)))

        SegmentTiming(book).payWhatIsOwed()

        assertEquals(1, book.all.size)
        assertEquals(emptyList<Segment>(), book.segmentsMissingHistory())
        assertEquals(emptyList<Long>(), book.runsMissingTiming())
    }

    @Test
    fun `the launch pass measures a Run whose own walk was lost`() = runTest {
        val book = book(listOf(aRun(1), aRun(2)))
        // Segment 7 has already had its history walked; run 2 finished afterwards and its own walk
        // never happened — the process was reclaimed between the two.
        book.markSegmentTimed(segment.id)
        book.markRunTimed(1)

        SegmentTiming(book).payWhatIsOwed()

        assertEquals(listOf(2L), book.all.map { it.sessionId })
    }

    @Test
    fun `a launch with nothing owed writes nothing`() = runTest {
        val book = book(listOf(aRun(1)))
        book.markSegmentTimed(segment.id)
        book.markRunTimed(1)

        SegmentTiming(book).payWhatIsOwed()

        assertEquals(emptyList<SegmentEffort>(), book.all)
    }
}
