package com.example.runningapp.segments

import com.example.runningapp.analysis.MapFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * What counts as having run a Segment, and what does not (#70).
 *
 * Scripted ground rather than recorded ground, because every rule here is a boundary: a tolerance
 * pinned by a real Run is a tolerance pinned by whatever that Run happened to do. The tracks below
 * are written in metres east and north of one origin and turned into fixes, so a test says "forty
 * metres off the line for one second" and the constants have something to be wrong against.
 */
class SegmentMatchingTest {

    // -- Scripting ground ---------------------------------------------------------------------

    private val originLatitude = 51.5
    private val originLongitude = -0.1

    /** A point [east] metres east and [north] metres north of the origin. */
    private fun at(east: Double, north: Double): MapFix {
        val latitudeRadians = originLatitude * PI / 180.0
        val a = 6378137.0
        val eSquared = 1.0 - (6356752.3142 / a).pow(2)
        val w = 1.0 - eSquared * sin(latitudeRadians).pow(2)
        val metersPerDegreeLatitude = PI / 180.0 * a * (1.0 - eSquared) / w.pow(1.5)
        val metersPerDegreeLongitude = PI / 180.0 * a / kotlin.math.sqrt(w) * cos(latitudeRadians)
        return MapFix(
            latitude = originLatitude + north / metersPerDegreeLatitude,
            longitude = originLongitude + east / metersPerDegreeLongitude,
        )
    }

    /** The corners of a stretch of ground, walked at [stepMeters] a fix. */
    private fun walk(corners: List<Pair<Double, Double>>, stepMeters: Double): List<Pair<Double, Double>> {
        val walked = mutableListOf(corners.first())
        var carried = 0.0
        for (leg in 0 until corners.lastIndex) {
            val (fromEast, fromNorth) = corners[leg]
            val (toEast, toNorth) = corners[leg + 1]
            val legMeters = kotlin.math.hypot(toEast - fromEast, toNorth - fromNorth)
            var walkedOnThisLeg = stepMeters - carried
            while (walkedOnThisLeg <= legMeters) {
                val fraction = walkedOnThisLeg / legMeters
                walked += (fromEast + (toEast - fromEast) * fraction) to
                    (fromNorth + (toNorth - fromNorth) * fraction)
                walkedOnThisLeg += stepMeters
            }
            carried = legMeters - (walkedOnThisLeg - stepMeters)
        }
        return walked
    }

    /** Those points as a track, one fix every [stepMillis]. */
    private fun track(
        points: List<Pair<Double, Double>>,
        stepMillis: Long = 2_000L,
        startAtMillis: Long = 1_000_000L,
        breakBefore: Set<Int> = emptySet(),
    ): List<SegmentTrackFix> = points.mapIndexed { i, (east, north) ->
        val fix = at(east, north)
        SegmentTrackFix(
            latitude = fix.latitude,
            longitude = fix.longitude,
            timestampMillis = startAtMillis + i * stepMillis,
            followsABreak = i in breakBefore,
        )
    }

    /** An L: two hundred metres east, then two hundred metres north. */
    private val elbow = listOf(at(0.0, 0.0), at(200.0, 0.0), at(200.0, 200.0))

    /** A Run that covers the whole of [elbow], setting off before it and carrying on past it. */
    private fun ranTheElbow(stepMeters: Double = 5.0) =
        walk(listOf(-50.0 to 0.0, 200.0 to 0.0, 200.0 to 250.0), stepMeters)

    // -- The rules ----------------------------------------------------------------------------

    @Test
    fun `a Run over the whole stretch is one effort`() {
        val efforts = segmentTraversalsIn(elbow, track(ranTheElbow()))

        assertEquals(1, efforts.size)
    }

    @Test
    fun `the effort is the wall clock between the two gates, interpolated`() {
        // Fixes every twenty metres, four seconds apart — five metres a second. The gates fall
        // halfway between fixes at either end, so a matcher that rounded to the nearest fix would
        // be two seconds out at each of them.
        val straight = listOf(at(0.0, 0.0), at(100.0, 0.0))
        val ran = (0..6).map { (-10.0 + it * 20.0) to 0.0 }

        val efforts = segmentTraversalsIn(straight, track(ran, stepMillis = 4_000L))

        assertEquals(1, efforts.size)
        // A hundred metres at five metres a second, whatever the fixes are stamped.
        assertEquals(20_000L, efforts.single().elapsedMillis)
    }

    @Test
    fun `a Run that skips part of the stretch is no effort`() {
        // Straight from the start of the elbow to its end: near both gates, and nowhere near the
        // ground between them.
        val cut = walk(listOf(0.0 to 0.0, 200.0 to 200.0), stepMeters = 5.0)

        assertEquals(emptyList<SegmentTraversal>(), segmentTraversalsIn(elbow, track(cut)))
    }

    @Test
    fun `a brief blip off the line still counts`() {
        val ran = ranTheElbow().toMutableList()
        // One fix flung forty metres north of the line, then straight back onto it.
        val blipped = ran.indexOfFirst { it.first >= 100.0 }
        ran[blipped] = ran[blipped].first to 40.0

        val efforts = segmentTraversalsIn(elbow, track(ran))

        assertEquals(1, efforts.size)
        assertEquals(
            segmentTraversalsIn(elbow, track(ranTheElbow())).single().elapsedMillis,
            efforts.single().elapsedMillis,
        )
    }

    @Test
    fun `the wrong way round is no effort`() {
        val backwards = ranTheElbow().reversed()

        assertEquals(emptyList<SegmentTraversal>(), segmentTraversalsIn(elbow, track(backwards)))
    }

    @Test
    fun `a Run that never goes near the stretch is no effort`() {
        val elsewhere = walk(listOf(0.0 to 500.0, 200.0 to 500.0), stepMeters = 5.0)

        assertEquals(emptyList<SegmentTraversal>(), segmentTraversalsIn(elbow, track(elsewhere)))
    }

    @Test
    fun `a stretch the recording does not cover is no effort`() {
        val ran = ranTheElbow()
        val midway = ran.indexOfFirst { it.first >= 100.0 }

        val efforts = segmentTraversalsIn(elbow, track(ran, breakBefore = setOf(midway)))

        assertEquals(emptyList<SegmentTraversal>(), efforts)
    }

    @Test
    fun `a Run that stops inside the stretch is no effort`() {
        val stoppedShort = walk(listOf(-50.0 to 0.0, 200.0 to 0.0, 200.0 to 150.0), stepMeters = 5.0)

        assertEquals(emptyList<SegmentTraversal>(), segmentTraversalsIn(elbow, track(stoppedShort)))
    }

    @Test
    fun `joining the stretch half way along is no effort`() {
        // Onto the elbow at its corner and out of the end gate: the second half run, the first
        // half never even approached.
        val halfOfIt = walk(listOf(150.0 to -60.0, 200.0 to 0.0, 200.0 to 250.0), stepMeters = 5.0)

        assertEquals(emptyList<SegmentTraversal>(), segmentTraversalsIn(elbow, track(halfOfIt)))
    }

    @Test
    fun `a long detour off the line is no effort, even though it rejoins`() {
        // Out to the parallel street forty metres north for a hundred metres, then back on.
        val detoured = walk(
            listOf(-50.0 to 0.0, 60.0 to 0.0, 70.0 to 40.0, 170.0 to 40.0, 180.0 to 0.0, 200.0 to 0.0, 200.0 to 250.0),
            stepMeters = 5.0,
        )

        assertEquals(emptyList<SegmentTraversal>(), segmentTraversalsIn(elbow, track(detoured)))
    }

    @Test
    fun `a Run over the stretch twice is two efforts`() {
        val there = ranTheElbow()
        val andBack = there.reversed().drop(1)
        val andAgain = there.drop(1)

        val efforts = segmentTraversalsIn(elbow, track(there + andBack + andAgain))

        assertEquals(2, efforts.size)
        assertEquals(efforts.first().elapsedMillis, efforts.last().elapsedMillis)
        assertTrue(efforts.first().finishedAtMillis < efforts.last().startedAtMillis)
    }

    @Test
    fun `a slower Run over the same stretch is the slower effort`() {
        val quick = segmentTraversalsIn(elbow, track(ranTheElbow(), stepMillis = 2_000L)).single()
        val slow = segmentTraversalsIn(elbow, track(ranTheElbow(), stepMillis = 4_000L)).single()

        assertEquals(2 * quick.elapsedMillis, slow.elapsedMillis)
    }

    @Test
    fun `ground with fewer than two points is no stretch at all`() {
        assertEquals(emptyList<SegmentTraversal>(), segmentTraversalsIn(listOf(at(0.0, 0.0)), track(ranTheElbow())))
        assertEquals(emptyList<SegmentTraversal>(), segmentTraversalsIn(emptyList(), track(ranTheElbow())))
    }
}
