package com.example.runningapp.segments

import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.run.RunMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Runs get a shape taken, when, and what a second pass over the same Run costs (#73).
 *
 * The geometry is [RunMatchingTest]'s subject; this is about the ordering and the eligibility — the
 * two things that decide whether a runner's history is grouped at all.
 */
class RunShapingTest {

    /** The database as the pass sees it, remembering every write so a test can count them. */
    private class Store : RunShapeStore {
        val runs = mutableMapOf<Long, RunnerSession>()
        val tracks = mutableMapOf<Long, List<TrackPoint>>()
        val shapes = mutableMapOf<Long, RunShape?>()
        var writes = 0

        /**
         * The runner acting between the two reads — runs once, after the first of them answers.
         *
         * That gap is the whole window the shaping has to defend: the first read decides whether a
         * track is worth measuring, and the write that follows must not carry an answer the Run has
         * stopped agreeing with.
         */
        var betweenTheReads: (() -> Unit)? = null

        override suspend fun run(sessionId: Long): RunnerSession? {
            val answer = runs[sessionId]
            val interruption = betweenTheReads
            betweenTheReads = null
            interruption?.invoke()
            return answer
        }

        override suspend fun runsMissingShapes(): List<Long> =
            runs.values
                .filter { it.endTime > 0 && it.id !in shapes }
                .sortedBy { it.startTime }
                .map { it.id }

        override suspend fun track(sessionId: Long) = tracks[sessionId].orEmpty()

        override suspend fun putShapeUnlessTheRunMoved(
            sessionId: Long,
            shape: RunShape?,
            measuredAs: RunnerSession,
        ): Boolean {
            val now = runs[sessionId] ?: return false
            if (!now.shapesAs(measuredAs)) return false
            shapes[sessionId] = shape
            writes++
            return true
        }
    }

    private val store = Store()
    private val shaping = RunShaping(store)

    /** A kilometre up the road and back, as a Run that finished. */
    private fun aRunOver(
        id: Long,
        east: Double = 500.0,
        finished: Boolean = true,
        isWalk: Boolean = false,
        mode: RunMode = RunMode.OUTDOOR,
    ) {
        store.runs[id] = RunnerSession(
            id = id,
            startTime = 1_000_000L * id,
            endTime = if (finished) 1_000_000L * id + 600_000L else 0L,
            isWalk = isWalk,
            runMode = mode.settingValue,
        )
        store.tracks[id] = track(id, east)
    }

    private fun track(id: Long, east: Double): List<TrackPoint> {
        val metersPerDegreeLongitude = 69_000.0
        val places = buildList {
            var covered = 0.0
            while (covered <= east) { add(covered); covered += 10.0 }
            var back = east
            while (back >= 0.0) { add(back); back -= 10.0 }
        }
        return places.mapIndexed { i, meters ->
            TrackPoint(
                sessionId = id,
                latitude = 51.5,
                longitude = -0.1 + meters / metersPerDegreeLongitude,
                timestampMillis = 1_000_000L * id + i * 5_000L,
                source = "GPS",
            )
        }
    }

    @Test
    fun `a finished run is measured and its shape written down`() = runTest {
        aRunOver(1L)

        shaping.shapeRun(1L)

        assertEquals(RUN_SHAPE_WAYPOINTS, store.shapes.getValue(1L)!!.waypoints.size)
    }

    @Test
    fun `measuring the same run again writes the same shape`() = runTest {
        aRunOver(1L)

        shaping.shapeRun(1L)
        val first = store.shapes.getValue(1L)
        shaping.shapeRun(1L)

        assertEquals(first, store.shapes.getValue(1L))
        assertEquals(2, store.writes)
        assertEquals(1, store.shapes.size)
    }

    @Test
    fun `a Walk holds no shape, and is written down as holding none`() = runTest {
        aRunOver(1L, isWalk = true)

        shaping.shapeRun(1L)

        assertTrue(1L in store.shapes)
        assertNull(store.shapes.getValue(1L))
    }

    @Test
    fun `a treadmill run holds no shape`() = runTest {
        aRunOver(1L, mode = RunMode.TREADMILL)

        shaping.shapeRun(1L)

        assertNull(store.shapes.getValue(1L))
    }

    @Test
    fun `a run still being recorded holds no shape`() = runTest {
        aRunOver(1L, finished = false)

        shaping.shapeRun(1L)

        assertNull(store.shapes.getValue(1L))
    }

    @Test
    fun `a run that is gone is written as nothing at all`() = runTest {
        shaping.shapeRun(404L)

        assertEquals(0, store.writes)
    }

    @Test
    fun `the launch pass measures the whole of history, oldest first`() = runTest {
        aRunOver(3L)
        aRunOver(1L)
        aRunOver(2L)

        shaping.payWhatIsOwed()

        assertEquals(setOf(1L, 2L, 3L), store.shapes.keys)
        assertEquals(3, store.writes)
    }

    @Test
    fun `the launch pass passes over a run already measured`() = runTest {
        aRunOver(1L)
        shaping.payWhatIsOwed()

        aRunOver(2L)
        shaping.payWhatIsOwed()

        assertEquals(2, store.writes)
    }

    @Test
    fun `a run marked a Walk while its shape was being taken is left owing one`() = runTest {
        aRunOver(1L)
        store.betweenTheReads = { store.runs[1L] = store.runs.getValue(1L).copy(isWalk = true) }

        shaping.shapeRun(1L)

        // Nothing written at all — a shape banked for a Walk could not be undone, because the row's
        // existence is what tells the launch pass this Run has been dealt with.
        assertEquals(0, store.writes)
        assertFalse(1L in store.shapes)
    }

    @Test
    fun `the debt the overtaken run is left owing is paid by the launch pass`() = runTest {
        aRunOver(1L)
        store.betweenTheReads = { store.runs[1L] = store.runs.getValue(1L).copy(isWalk = true) }
        shaping.shapeRun(1L)

        shaping.payWhatIsOwed()

        assertTrue(1L in store.shapes)
        assertNull(store.shapes.getValue(1L))
    }

    @Test
    fun `a Walk unmarked while it was being passed over is left owing a shape`() = runTest {
        aRunOver(1L, isWalk = true)
        store.betweenTheReads = { store.runs[1L] = store.runs.getValue(1L).copy(isWalk = false) }

        shaping.shapeRun(1L)

        // The mirror of the case above, and the same permanent mistake: a row saying "no shape"
        // would take this Run out of every group it belongs to for good.
        assertEquals(0, store.writes)
        assertFalse(1L in store.shapes)
    }

    @Test
    fun `a run deleted while its shape was being taken is written as nothing at all`() = runTest {
        aRunOver(1L)
        store.betweenTheReads = { store.runs.remove(1L) }

        shaping.shapeRun(1L)

        assertEquals(0, store.writes)
    }

    @Test
    fun `a run whose feel changed while it was being measured still gets its shape`() = runTest {
        aRunOver(1L)
        // Everything the Effort backfill and the feel sheet may write mid-measurement. None of it
        // can move a waypoint, so none of it is a reason to throw a measurement away.
        store.betweenTheReads = {
            store.runs[1L] = store.runs.getValue(1L).copy(effortScore = 42, perceivedEffort = 7)
        }

        shaping.shapeRun(1L)

        assertEquals(RUN_SHAPE_WAYPOINTS, store.shapes.getValue(1L)!!.waypoints.size)
    }

    @Test
    fun `the launch pass leaves a run still being recorded alone`() = runTest {
        aRunOver(1L, finished = false)

        shaping.payWhatIsOwed()

        assertEquals(0, store.writes)
    }
}
