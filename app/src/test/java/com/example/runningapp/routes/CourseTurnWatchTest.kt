package com.example.runningapp.routes

import com.example.runningapp.recording.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a turn is spoken, and when it is not (#456).
 *
 * The course is the same one throughout: four hundred metres due north, then six hundred due west.
 * So there is exactly one turn, it is a left, and it is four hundred metres in — which makes every
 * test in here a sentence about ground. A fix is written as how far along the course the runner is
 * and how far off the line, so "at 355" is five metres past the point the warning is owed and "at
 * 355, sixty metres off" is the same ground with the runner over on the far pavement.
 *
 * What is being pinned is every rule #58 established, kept: silent until the course is reached,
 * silent on a fix that is not trusted, silent while auto-paused, and nothing ever said twice.
 */
class CourseTurnWatchTest {

    private val originLatitude = 51.5
    private val originLongitude = -0.1
    private val metersPerDegreeLatitude = 111_132.0
    private val metersPerDegreeLongitude = 69_300.0

    private fun at(northMeters: Double, eastMeters: Double = 0.0) = RoutePoint(
        latitude = originLatitude + northMeters / metersPerDegreeLatitude,
        longitude = originLongitude + eastMeters / metersPerDegreeLongitude,
        elevationMeters = null,
    )

    /** North four hundred metres, then west six hundred: one left turn, four hundred metres in. */
    private val roadWithACorner: List<RoutePoint> =
        (0..16).map { at(it * 25.0) } + (1..24).map { at(400.0, -it * 25.0) }

    /**
     * A place on the line [alongMeters] into the course, pushed [offMeters] to the runner's right.
     *
     * The right rather than the left because the corner turns left: the outside of it is one place
     * on open ground, while a runner pushed off to the inside is near the far arm of the L as well
     * as the near one, and this course would place them on whichever they happened to be nearer.
     *
     * The metres along are the test's own flat ones, so a fix written at 350 lands within a metre of
     * the 350 the course itself counts — near enough for every threshold in here, which are tens of
     * metres apart.
     */
    private fun onTheCourse(alongMeters: Double, offMeters: Double = 0.0): RoutePoint =
        if (alongMeters <= 400.0) at(alongMeters, eastMeters = offMeters)
        else at(400.0 + offMeters, eastMeters = -(alongMeters - 400.0))

    private fun fix(
        alongMeters: Double,
        offMeters: Double = 0.0,
        accuracyMeters: Float? = 5f,
    ) = onTheCourse(alongMeters, offMeters).let {
        LocationFix(
            latitude = it.latitude,
            longitude = it.longitude,
            accuracyMeters = accuracyMeters,
            speedMps = 3f,
            timestampMs = 0L,
        )
    }

    private fun watch() =
        CourseTurnWatch(CourseLine.of(roadWithACorner)!!, courseTurnsOf(roadWithACorner))

    /** What the watch said, as the runner would hear it. */
    private fun CourseTurnWatch.at(
        alongMeters: Double,
        offMeters: Double = 0.0,
        accuracyMeters: Float? = 5f,
        autoPaused: Boolean = false,
    ): List<String> = onFix(fix(alongMeters, offMeters, accuracyMeters), autoPaused).said.map { it.cue.spoken }

    private val warning = "Turn left in 50 metres."
    private val theTurn = "Turn left."
    private val nothing = emptyList<String>()

    /** Walk the runner onto the start of the course, which is what arms the cues. */
    private fun CourseTurnWatch.reachTheCourse() = at(0.0)

    @Test
    fun `the corner is announced before it and again at it`() {
        val watch = watch()
        assertEquals(nothing, watch.reachTheCourse())

        assertEquals(nothing, watch.at(300.0))
        assertEquals(listOf(warning), watch.at(355.0))
        assertEquals(nothing, watch.at(380.0))
        assertEquals(listOf(theTurn), watch.at(402.0))
        assertEquals(nothing, watch.at(500.0))
    }

    /**
     * The jog from the front door is not a missed turn. The runner here is a street east of the
     * course for the whole of it, and passes the ground both cues are owed at without ever having
     * been on the line.
     */
    @Test
    fun `nothing is said until the runner has reached the course`() {
        val watch = watch()
        for (along in listOf(0.0, 300.0, 355.0, 402.0, 500.0)) {
            assertEquals(nothing, watch.at(along, offMeters = 200.0))
        }
    }

    /** A fix the app does not trust is not read against the course at all (#38). */
    @Test
    fun `a poor fix says nothing, and the next good one still does`() {
        val watch = watch()
        watch.reachTheCourse()

        assertEquals(nothing, watch.at(355.0, accuracyMeters = 40f))
        assertEquals(listOf(warning), watch.at(356.0))
    }

    /** Standing still is not approaching a turn (#39). */
    @Test
    fun `nothing is said while the Run is auto-paused`() {
        val watch = watch()
        watch.reachTheCourse()

        assertEquals(nothing, watch.at(355.0, autoPaused = true))
        assertEquals(listOf(warning), watch.at(356.0))
    }

    /**
     * A course drawn down the middle of a dual carriageway leaves an honest runner sixty metres off
     * the line for the whole Run — far enough to be told they are off course, and the runner who
     * most needs to know the course turns left. How far off the line they are is not asked here.
     */
    @Test
    fun `a runner running wide of the line is still told about the corner`() {
        val watch = watch()
        watch.reachTheCourse()

        assertEquals(listOf(warning), watch.at(355.0, offMeters = 60.0))
        assertEquals(listOf(theTurn), watch.at(402.0, offMeters = 60.0))
    }

    /**
     * A runner wobbling either side of the trigger point — a fix a metre back, the next a metre on —
     * is told once. The pointer over the cues only ever goes forwards.
     */
    @Test
    fun `a turn already announced is not announced again`() {
        val watch = watch()
        watch.reachTheCourse()

        assertEquals(listOf(warning), watch.at(352.0))
        assertEquals(nothing, watch.at(348.0))
        assertEquals(nothing, watch.at(353.0))
        assertEquals(nothing, watch.at(351.0))
    }

    /**
     * A cue about ground the runner covered a hundred metres ago is not a late cue, it is a wrong
     * one. Here the fixes stop for a street — a pocket that lost the sky — and the next one lands
     * past the warning's ground; the warning is dropped and the turn itself still arrives.
     */
    @Test
    fun `a cue the runner has already run past is not said`() {
        val watch = watch()
        watch.reachTheCourse()

        assertEquals(nothing, watch.at(390.0))
        assertEquals(listOf(theTurn), watch.at(402.0))
    }

    /**
     * A runner who joins the course at its halfway point has not missed its turns — they are running
     * the half in front of them. Everything behind them is stepped over without a word.
     */
    @Test
    fun `joining the course past a turn says nothing about it`() {
        val watch = watch()

        assertEquals(nothing, watch.at(500.0))
        assertEquals(nothing, watch.at(600.0))
        assertEquals(nothing, watch.at(700.0))
    }

    /**
     * The out-and-back case, which is the one that looks like a repeat and is not.
     *
     * The course goes north five hundred, steps ten metres east and comes back — one turn, at the
     * far end. Coming back down the same ground is *forward* along the line, so the cues behind the
     * runner stay behind them and nothing is said a second time.
     */
    @Test
    fun `coming back down an out-and-back says nothing a second time`() {
        val outAndBack = (0..20).map { at(it * 25.0) } +
            at(500.0, 10.0) +
            (0..20).map { at(500.0 - it * 25.0, 10.0) }
        val line = CourseLine.of(outAndBack)!!
        val watch = CourseTurnWatch(line, courseTurnsOf(outAndBack))

        fun say(northMeters: Double, eastMeters: Double): List<String> {
            val place = at(northMeters, eastMeters)
            return watch.onFix(
                LocationFix(place.latitude, place.longitude, 5f, 3f, 0L),
                autoPaused = false,
            ).said.map { it.cue.spoken }
        }

        assertEquals(nothing, say(0.0, 0.0))
        assertEquals(nothing, say(300.0, 0.0))
        assertEquals(listOf("Turn right in 50 metres."), say(455.0, 0.0))
        assertEquals(listOf("Turn right."), say(500.0, 0.0))
        // Back down the other side of the road, over every metre of ground already covered.
        for (north in listOf(490.0, 400.0, 300.0, 150.0, 0.0)) {
            assertEquals(nothing, say(north, 10.0))
        }
    }
}
