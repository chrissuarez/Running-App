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

    /** The efforts table, with the one behaviour that matters: a pair is replaced, never added to. */
    private class Book {
        val rows = mutableMapOf<Pair<Long, Long>, List<SegmentEffort>>()
        val write: suspend (Long, Long, List<SegmentEffort>) -> Unit = { segmentId, sessionId, efforts ->
            rows[segmentId to sessionId] = efforts
        }
        val all: List<SegmentEffort> get() = rows.values.flatten()
    }

    private fun timing(
        book: Book,
        runs: List<RunnerSession>,
        segments: List<Segment> = listOf(segment),
        tracks: Map<Long, List<TrackPoint>> = runs.associate { it.id to ranIt(it.id) },
    ) = SegmentTiming(
        readSegments = { segments },
        readSegment = { id -> segments.firstOrNull { it.id == id } },
        readRuns = { runs },
        readRun = { id -> runs.firstOrNull { it.id == id } },
        readTrack = { id -> tracks[id].orEmpty() },
        writeEfforts = book.write,
    )

    @Test
    fun `a new Segment is born with its history already measured`() = runTest {
        val book = Book()
        val runs = listOf(aRun(1), aRun(2), aRun(3))

        timing(book, runs).timeAgainstHistory(segment.id)

        assertEquals(3, book.all.size)
        assertEquals(setOf(1L, 2L, 3L), book.all.map { it.sessionId }.toSet())
        assertTrue(book.all.all { it.segmentId == segment.id })
    }

    @Test
    fun `measuring the same history again leaves the same efforts`() = runTest {
        val book = Book()
        val timing = timing(book, listOf(aRun(1), aRun(2)))

        timing.timeAgainstHistory(segment.id)
        val first = book.all
        timing.timeAgainstHistory(segment.id)

        assertEquals(2, book.all.size)
        assertEquals(first.map { it.startedAtMillis to it.finishedAtMillis }, book.all.map { it.startedAtMillis to it.finishedAtMillis })
    }

    @Test
    fun `a Walk holds no efforts`() = runTest {
        val book = Book()

        timing(book, listOf(aRun(1, isWalk = true))).timeAgainstHistory(segment.id)

        assertEquals(emptyList<SegmentEffort>(), book.all)
    }

    @Test
    fun `a treadmill Run holds no efforts`() = runTest {
        val book = Book()

        timing(book, listOf(aRun(1, runMode = "treadmill"))).timeAgainstHistory(segment.id)

        assertEquals(emptyList<SegmentEffort>(), book.all)
    }

    @Test
    fun `a Run still being recorded holds no efforts`() = runTest {
        val book = Book()

        timing(book, listOf(aRun(1, finished = false))).timeAgainstHistory(segment.id)

        assertEquals(emptyList<SegmentEffort>(), book.all)
    }

    @Test
    fun `a Run that finishes is put to every Segment`() = runTest {
        val book = Book()
        val other = segment.copy(id = 9, name = "The same hill again")

        timing(book, listOf(aRun(1)), segments = listOf(segment, other)).timeAgainstEverySegment(1)

        assertEquals(setOf(7L, 9L), book.all.map { it.segmentId }.toSet())
    }

    @Test
    fun `marking a Run a Walk takes its efforts off every Segment`() = runTest {
        val book = Book()
        timing(book, listOf(aRun(1))).timeAgainstEverySegment(1)
        assertEquals(1, book.all.size)

        timing(book, listOf(aRun(1, isWalk = true))).timeAgainstEverySegment(1)

        assertEquals(emptyList<SegmentEffort>(), book.all)
    }

    @Test
    fun `a Run that never went near a Segment writes nothing`() = runTest {
        val book = Book()
        val elsewhere = mapOf(1L to ranIt(1).map { it.copy(longitude = -0.2) })

        timing(book, listOf(aRun(1)), tracks = elsewhere).timeAgainstHistory(segment.id)

        assertEquals(emptyList<SegmentEffort>(), book.all)
    }

    @Test
    fun `the effort is the time between the gates`() = runTest {
        val book = Book()

        timing(book, listOf(aRun(1))).timeAgainstHistory(segment.id)

        val effort = book.all.single()
        // Ten metres a fix, four seconds a fix: a hundred metres is forty seconds.
        assertEquals(40_000L, effort.finishedAtMillis - effort.startedAtMillis)
    }
}
