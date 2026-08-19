package com.example.runningapp.segments

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.analysis.RouteFix
import com.example.runningapp.analysis.TrackMap
import com.example.runningapp.analysis.TrackStretch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a runner may cut out of their own Run, and what they may not (#69).
 *
 * Pure, so every refusal is pinned here rather than found on a phone: this is the one place that
 * decides whether the geometry a Segment is about to be saved with is ground the Run witnessed.
 */
class SegmentCutTest {

    /** A straight line north, one fix every ten metres — near enough for a metre-level assertion. */
    private fun straightTrack(fixCount: Int, brokenLegs: Set<Int> = emptySet()): TrackMap {
        val fixes = (0 until fixCount).map { MapFix(51.5 + it * 0.00009, -0.1) }
        var run = 0.0
        val route = fixes.mapIndexed { i, fix ->
            if (i > 0) run += 10.0
            RouteFix(distanceMeters = run, fix = fix)
        }
        return TrackMap(
            stretches = listOf(TrackStretch(fixes, zone = null)),
            start = fixes.first(),
            finish = fixes.last(),
            route = route,
            brokenLegs = brokenLegs,
        )
    }

    @Test
    fun `a cut keeps every fix between the two marks, marks included`() {
        val track = straightTrack(10)
        val cut = segmentCutOf(track, 2, 5) as SegmentCut.Cut

        assertEquals(4, cut.fixes.size)
        assertEquals(track.route[2].fix, cut.fixes.first())
        assertEquals(track.route[5].fix, cut.fixes.last())
    }

    @Test
    fun `the cut is as long as the ground the Run counted between the marks`() {
        val cut = segmentCutOf(straightTrack(10), 2, 5) as SegmentCut.Cut

        assertEquals(30.0, cut.distanceMeters, 0.001)
    }

    @Test
    fun `marks given the wrong way round cut the same stretch`() {
        val forwards = segmentCutOf(straightTrack(10), 2, 5) as SegmentCut.Cut
        val backwards = segmentCutOf(straightTrack(10), 5, 2) as SegmentCut.Cut

        assertEquals(forwards.fixes, backwards.fixes)
        assertEquals(forwards.distanceMeters, backwards.distanceMeters, 0.0)
    }

    @Test
    fun `two marks on the same fix are no stretch at all`() {
        assertEquals(SegmentCut.TooShort, segmentCutOf(straightTrack(10), 4, 4))
    }

    @Test
    fun `a stretch the Run counted no ground across is no stretch at all`() {
        // The runner stood still: four fixes arrived, and the Run's total never moved across them.
        // measureTrack gives a leg between two fixes stamped the same moment no metres at all, so
        // this is a recording that happens, not a hypothetical.
        val standing = straightTrack(10).let { track ->
            val route = track.route.mapIndexed { i, fix ->
                if (i in 3..6) RouteFix(track.route[3].distanceMeters, fix.fix) else fix
            }
            track.copy(route = route)
        }

        assertEquals(SegmentCut.TooShort, segmentCutOf(standing, 3, 6))
    }

    @Test
    fun `a stretch that reaches past standing still is kept`() {
        val standing = straightTrack(10).let { track ->
            val route = track.route.mapIndexed { i, fix ->
                if (i in 3..6) RouteFix(track.route[3].distanceMeters, fix.fix) else fix
            }
            track.copy(route = route)
        }

        assertTrue(segmentCutOf(standing, 3, 7) is SegmentCut.Cut)
    }

    @Test
    fun `a stretch across a break in the recording is refused`() {
        // The Run paused, or lost signal, between fixes 3 and 4: nothing witnessed that ground.
        val track = straightTrack(10, brokenLegs = setOf(3))

        assertEquals(SegmentCut.SpansABreak, segmentCutOf(track, 2, 6))
    }

    @Test
    fun `a stretch that stops at the near side of a break is allowed`() {
        val track = straightTrack(10, brokenLegs = setOf(3))

        assertTrue(segmentCutOf(track, 1, 3) is SegmentCut.Cut)
        assertTrue(segmentCutOf(track, 4, 7) is SegmentCut.Cut)
    }

    @Test
    fun `marks outside the recording are pulled back onto it`() {
        val cut = segmentCutOf(straightTrack(5), -3, 99) as SegmentCut.Cut

        assertEquals(5, cut.fixes.size)
    }

    @Test
    fun `a Run with nothing recorded has nothing to cut`() {
        val empty = TrackMap(
            stretches = emptyList(),
            start = MapFix(0.0, 0.0),
            finish = MapFix(0.0, 0.0),
            route = emptyList(),
            brokenLegs = emptySet(),
        )

        assertEquals(SegmentCut.TooShort, segmentCutOf(empty, 0, 0))
    }

    @Test
    fun `a Run that never moved opens on nothing to cut`() {
        // Every fix written down in the same place: unbroken, and no ground anywhere in it.
        val fixes = (0 until 6).map { MapFix(51.5, -0.1) }
        val standing = TrackMap(
            stretches = listOf(TrackStretch(fixes, zone = null)),
            start = fixes.first(),
            finish = fixes.last(),
            route = fixes.map { RouteFix(distanceMeters = 0.0, fix = it) },
            brokenLegs = emptySet(),
        )

        assertNull(defaultMarksFor(standing))
    }

    @Test
    fun `the marks skip a longer stretch that holds no ground`() {
        // Fixes 0..5 are one place the runner stood in; 6..9 is ground they covered. A break at 5
        // keeps the two apart, so the longer stretch by count is the one worth nothing.
        val track = straightTrack(10, brokenLegs = setOf(5)).let { built ->
            val route = built.route.mapIndexed { i, fix ->
                if (i <= 5) RouteFix(0.0, fix.fix) else fix
            }
            built.copy(route = route)
        }

        assertEquals(6..9, defaultMarksFor(track))
    }

    @Test
    fun `the marks a Run opens with are its longest unbroken stretch`() {
        // Breaks after fix 1 and after fix 3: the stretches are 0..1, 2..3 and 4..9.
        val track = straightTrack(10, brokenLegs = setOf(1, 3))

        assertEquals(4..9, defaultMarksFor(track))
    }

    @Test
    fun `the marks a Run opens with always cut something`() {
        val track = straightTrack(10, brokenLegs = setOf(1, 3))
        val marks = defaultMarksFor(track)!!

        assertTrue(segmentCutOf(track, marks.first, marks.last) is SegmentCut.Cut)
    }

    @Test
    fun `a Run with no unbroken stretch offers no marks`() {
        val allBroken = straightTrack(3, brokenLegs = setOf(0, 1))

        assertEquals(null, defaultMarksFor(allBroken))
    }

    @Test
    fun `the lines a Run may be drawn as stop at every break`() {
        val stretches = unbrokenStretchesOf(straightTrack(10, brokenLegs = setOf(1, 3)))

        assertEquals(listOf(2, 2, 6), stretches.map { it.size })
    }

    @Test
    fun `a stretch of one fix is no line, so it is not drawn`() {
        // Breaks either side of fix 2: it is a place the runner was, but no ground to draw.
        val stretches = unbrokenStretchesOf(straightTrack(6, brokenLegs = setOf(1, 2)))

        assertEquals(listOf(2, 3), stretches.map { it.size })
    }
}
