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

    // --- Where each tolerance sits ---
    //
    // Every constant in the matcher is a boundary, and a boundary is only pinned by a pair of tracks
    // either side of it. Each pair below is written so that widening the constant would let the
    // refused one through, and narrowing it would turn the accepted one away.

    @Test
    fun `the corridor is twenty-five metres wide`() {
        // A hundred metres of the elbow's first leg run on a line parallel to it, then back on.
        fun parallelAt(offset: Double) = walk(
            listOf(-50.0 to 0.0, 50.0 to offset, 150.0 to offset, 200.0 to 0.0, 200.0 to 250.0),
            stepMeters = 5.0,
        )

        assertEquals(1, segmentTraversalsIn(elbow, track(parallelAt(24.0))).size)
        assertEquals(emptyList<SegmentTraversal>(), segmentTraversalsIn(elbow, track(parallelAt(26.0))))
    }

    @Test
    fun `a stray off the line may last ten seconds and no longer`() {
        // Fixes two seconds apart, so each fix pushed off the line is two more seconds outside the
        // corridor. Four of them is the ten seconds allowed; six is fourteen.
        fun strayFor(fixes: Int): List<Pair<Double, Double>> {
            val ran = ranTheElbow().toMutableList()
            val from = ran.indexOfFirst { it.first >= 60.0 }
            for (i in from until from + fixes) ran[i] = ran[i].first to 40.0
            return ran
        }

        assertEquals(1, segmentTraversalsIn(elbow, track(strayFor(4))).size)
        assertEquals(emptyList<SegmentTraversal>(), segmentTraversalsIn(elbow, track(strayFor(6))))
    }

    @Test
    fun `a stray off the line may cover forty metres and no more`() {
        // Fixes a second apart at six metres each — quick enough that the ten-second window is not
        // what turns the longer of these away.
        fun strayFor(fixes: Int): List<Pair<Double, Double>> {
            val ran = walk(listOf(-60.0 to 0.0, 200.0 to 0.0, 200.0 to 250.0), stepMeters = 6.0).toMutableList()
            val from = ran.indexOfFirst { it.first >= 60.0 }
            for (i in from until from + fixes) ran[i] = ran[i].first to 40.0
            return ran
        }

        // Five fixes: six seconds outside the corridor, thirty metres of the Segment covered.
        assertEquals(1, segmentTraversalsIn(elbow, track(strayFor(5), stepMillis = 1_000L)).size)
        // Eight: nine seconds, still inside the window, but forty-eight metres of Segment gone by.
        assertEquals(
            emptyList<SegmentTraversal>(),
            segmentTraversalsIn(elbow, track(strayFor(8), stepMillis = 1_000L)),
        )
    }

    @Test
    fun `the forty metres is measured to the fix that comes back, not to the last one outside`() {
        // One fix flung forty metres north of the line, and the next one back on it. The fix that
        // returns is the far end of a leg spent off the Segment, so how much of the Segment went by
        // on that leg is the question — a Run last seen off the line at sixty metres and next seen
        // on it at a hundred and fifty ran ninety metres of ground nobody witnessed it on.
        fun rejoiningAt(east: Double): List<Pair<Double, Double>> =
            walk(listOf(-50.0 to 0.0, 60.0 to 0.0), stepMeters = 5.0) +
                listOf(65.0 to 40.0) +
                walk(listOf(east to 0.0, 200.0 to 0.0, 200.0 to 250.0), stepMeters = 5.0)

        // Back on the line twenty-five metres further up: a blip, and the effort stands.
        assertEquals(1, segmentTraversalsIn(elbow, track(rejoiningAt(85.0))).size)
        // Ninety further up, and inside the ten seconds, so the ground is what turns it away.
        assertEquals(emptyList<SegmentTraversal>(), segmentTraversalsIn(elbow, track(rejoiningAt(150.0))))
    }

    @Test
    fun `coming out of the far end thirty metres wide of it is not coming out of it`() {
        // A Run whose recording skips from just short of the top to well past it — the one way to
        // reach the end of the Segment without ever being at it.
        fun leavingAt(east: Double, north: Double) =
            walk(listOf(-50.0 to 0.0, 200.0 to 0.0, 200.0 to 190.0), stepMeters = 5.0) + (east to north)

        assertEquals(1, segmentTraversalsIn(elbow, track(leavingAt(215.0, 215.0))).size)
        assertEquals(
            emptyList<SegmentTraversal>(),
            segmentTraversalsIn(elbow, track(leavingAt(240.0, 230.0))),
        )
    }

    @Test
    fun `a stray that ends on the end gate answers for itself like any other`() {
        // The end gate is not a way out of the corridor rules. A Run last seen off the line and next
        // seen standing on the Segment's own last point has covered the ground between the two fixes
        // unwitnessed, and it makes no difference to that whether the fix that comes back lands in
        // the middle of the stretch or on the gate at the top of it.
        fun strayingFrom(north: Double) =
            walk(listOf(-50.0 to 0.0, 200.0 to 0.0, 200.0 to north), stepMeters = 5.0) +
                listOf(230.0 to (north + 2.0)) +
                listOf(200.0 to 200.0)

        // Off the line twenty-five metres short of the top and back onto the gate four seconds
        // later: inside both limits, so it is a blip and the effort stands.
        assertEquals(1, segmentTraversalsIn(elbow, track(strayingFrom(175.0))).size)
        // Fifty metres short, so fifty metres of the Segment went by while the Run was off it.
        assertEquals(emptyList<SegmentTraversal>(), segmentTraversalsIn(elbow, track(strayingFrom(150.0))))
        // The same twenty-five metres, at six seconds a fix: twelve seconds off the line.
        assertEquals(
            emptyList<SegmentTraversal>(),
            segmentTraversalsIn(elbow, track(strayingFrom(175.0), stepMillis = 6_000L)),
        )
    }

    @Test
    fun `a Run that starts inside the stretch is no effort`() {
        // The runner pressed Start half way up. They did not cross the bottom gate, so there is no
        // moment to time from and no time that could be compared with the efforts that did — the
        // same rule, at the other end, as a Run that stops inside it.
        val startedHalfWayUp = walk(listOf(100.0 to 0.0, 200.0 to 0.0, 200.0 to 250.0), stepMeters = 5.0)

        assertEquals(emptyList<SegmentTraversal>(), segmentTraversalsIn(elbow, track(startedHalfWayUp)))
    }

    @Test
    fun `ground with fewer than two points is no stretch at all`() {
        assertEquals(emptyList<SegmentTraversal>(), segmentTraversalsIn(listOf(at(0.0, 0.0)), track(ranTheElbow())))
        assertEquals(emptyList<SegmentTraversal>(), segmentTraversalsIn(emptyList(), track(ranTheElbow())))
    }
}
