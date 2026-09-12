package com.example.runningapp.routes

import com.example.runningapp.recording.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
     * A course drawn down the middle of a road, a runner on the far pavement, a file traced off
     * somebody else's Run: honest running sits tens of metres off the line, and that runner most
     * needs to know the course turns left. Every wander [OFF_COURSE_METERS] calls being on the
     * course is still on the course here — the same number the off-course alerts use, so the app
     * never tells a runner they have left the line and where to turn on it in the same breath.
     *
     * Forty metres rather than fifty exactly: the metres in this file are flat ones and the course
     * counts round ones, so a fix written at the boundary lands either side of it by centimetres.
     */
    @Test
    fun `a runner running wide of the line is still told about the corner`() {
        val watch = watch()
        watch.reachTheCourse()

        assertEquals(listOf(warning), watch.at(355.0, offMeters = 40.0))
        assertEquals(listOf(theTurn), watch.at(402.0, offMeters = 40.0))
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

    /**
     * The other side of the same number, at the moment a cue is *made* (#460's fourth round).
     *
     * A turn's own cue is worth saying while the runner is less than twenty metres past the corner,
     * and at twenty metres exactly it is not — one side, and the same side the withdrawal uses, so
     * a cue can never be made at a distance the withdrawal would already call dead.
     */
    @Test
    fun `a cue exactly the late allowance past its ground is not said`() {
        val watch = watch()
        watch.reachTheCourse()

        assertEquals(nothing, watch.at(390.0))
        assertEquals(listOf(theTurn), watch.at(419.9))
    }

    @Test
    fun `a cue a whisker over the late allowance past its ground is not said`() {
        val watch = watch()
        watch.reachTheCourse()

        assertEquals(nothing, watch.at(390.0))
        assertEquals(nothing, watch.at(420.1))
    }

    /**
     * #460's fifth round: a runner far off the line is placed at the edge of the window the last fix
     * opened, and that edge can be a corner.
     *
     * The course is an L — five hundred north, then east — and the runner leaves it near the start
     * and ends up out in the fields to the north-east. The window around where they last were reaches
     * only the north arm, so the nearest place in it is the corner itself, and the arithmetic says
     * they are standing on a corner four hundred metres away.
     */
    @Test
    fun `a runner far off the line is not told about the corner the window ends at`() {
        val ell = (0..20).map { at(it * 25.0) } +
            (1..28).map { at(500.0, it * 25.0) } +
            (1..8).map { at(500.0 + it * 25.0, 700.0) }
        val watch = CourseTurnWatch(CourseLine.of(ell)!!, courseTurnsOf(ell))

        fun say(north: Double, east: Double): List<String> {
            val place = at(north, east)
            return watch.onFix(
                LocationFix(place.latitude, place.longitude, 5f, 3f, 0L),
                autoPaused = false,
            ).said.map { it.cue.spoken }
        }

        assertEquals(nothing, say(0.0, 0.0))
        assertEquals(nothing, say(300.0, 0.0))

        // Two hundred metres past the corner and two hundred west of it: the runner missed the
        // turning and kept going. The nearest place on the course is the corner itself, so the
        // projection lands on it exactly.
        assertEquals(nothing, say(700.0, -200.0))
    }

    /**
     * And the other half of it: coming back to the line is where the runner really is, not where the
     * window last left them. The corners they skipped stay unsaid — they did not turn them — and the
     * next corner in front of them is announced from where they actually rejoined.
     */
    @Test
    fun `rejoining the course far ahead picks up the corners in front, not the ones behind`() {
        val ell = (0..20).map { at(it * 25.0) } +
            (1..28).map { at(500.0, it * 25.0) } +
            (1..8).map { at(500.0 + it * 25.0, 700.0) }
        val watch = CourseTurnWatch(CourseLine.of(ell)!!, courseTurnsOf(ell))

        fun say(north: Double, east: Double): List<String> {
            val place = at(north, east)
            return watch.onFix(
                LocationFix(place.latitude, place.longitude, 5f, 3f, 0L),
                autoPaused = false,
            ).said.map { it.cue.spoken }
        }

        assertEquals(nothing, say(0.0, 0.0))
        assertEquals(nothing, say(300.0, 0.0))
        assertEquals(nothing, say(700.0, -200.0))

        // Back on the line, six hundred metres east along the second arm: 1100 m into the course.
        assertEquals(nothing, say(500.0, 600.0))

        // The last corner, at 1200 m, is now the one in front of them and is announced normally.
        assertEquals(listOf("Turn left in 50 metres."), say(500.0, 660.0))
        assertEquals(listOf("Turn left."), say(505.0, 700.0))
    }

    /**
     * #460's sixth round, on a two-lap loop: the pointer over the cues only ever goes forwards, and
     * re-anchoring must not be the thing that walks it back.
     *
     * The whole-line reading deliberately prefers the *earlier* of two equally near places, so a
     * runner rejoining on the second lap of a loop is reported as being on the first. Believed, the
     * pointer would jump back and every corner of the lap would be announced a second time. The
     * pointer keeps its place instead; the cost is that the rest of that lap stays silent, which is
     * the way round to be wrong.
     */
    @Test
    fun `rejoining a two-lap loop does not announce its corners again`() {
        // A two hundred metre square, run twice. Corners every two hundred metres.
        val lap = (0..7).map { at(it * 25.0) } +
            (0..7).map { at(200.0, it * 25.0) } +
            (0..7).map { at(200.0 - it * 25.0, 200.0) } +
            (0..7).map { at(0.0, 200.0 - it * 25.0) }
        val twoLaps = lap + lap + listOf(at(0.0))
        val watch = CourseTurnWatch(CourseLine.of(twoLaps)!!, courseTurnsOf(twoLaps))

        fun say(north: Double, east: Double): List<String> {
            val place = at(north, east)
            return watch.onFix(
                LocationFix(place.latitude, place.longitude, 5f, 3f, 0L),
                autoPaused = false,
            ).said.map { it.cue.spoken }
        }

        // Round the first lap, hearing its corners.
        assertEquals(nothing, say(0.0, 0.0))
        val heard = mutableListOf<String>()
        for (north in listOf(100.0, 160.0, 200.0)) heard += say(north, 0.0)
        for (east in listOf(100.0, 160.0, 200.0)) heard += say(200.0, east)
        assertEquals(4, heard.size)

        // Off into the fields, then back onto the square's west side — second lap, but the same
        // ground as the first, so the whole-line reading calls it the first.
        assertEquals(nothing, say(100.0, -200.0))
        assertEquals(nothing, say(100.0, 0.0))

        // Nothing from the first lap is said a second time.
        assertEquals(nothing, say(160.0, 0.0))
        assertEquals(nothing, say(200.0, 0.0))
    }

    /**
     * #460's seventh round: rejoining between a warning and its corner still earns the warning.
     *
     * The runner leaves the course before the warning is due and comes back past its trigger but
     * short of the corner. The corner is genuinely in front of them, so they are told — stepping
     * over by where a cue is *triggered* rather than by the turn it is *about* would swallow it and
     * leave the corner announced only once they were standing on it.
     */
    @Test
    fun `rejoining between a warning and its corner still earns the warning`() {
        val watch = watch()
        watch.reachTheCourse()

        assertEquals(nothing, watch.at(300.0))
        // Off the course, well past what OFF_COURSE_METERS tolerates.
        assertEquals(nothing, watch.at(310.0, offMeters = 200.0))
        // Back on the line at 360: past the warning's trigger at 350, short of the corner at 400.
        assertEquals(listOf(warning), watch.at(360.0))
        assertEquals(listOf(theTurn), watch.at(402.0))
    }

    /**
     * And the other side of it: a corner the runner arrives *past* is not announced, however
     * recently its cue came due. They did not run that ground and are not sent back to it.
     */
    @Test
    fun `rejoining just past a corner says nothing about it`() {
        val watch = watch()
        watch.reachTheCourse()

        assertEquals(nothing, watch.at(300.0))
        assertEquals(nothing, watch.at(310.0, offMeters = 200.0))
        // Back on the line at 410, ten metres beyond the corner at 400.
        assertEquals(nothing, watch.at(410.0))
        assertEquals(nothing, watch.at(430.0))
    }

    /**
     * #461: a jump past the window's far edge lands the reading on the edge, and the edge can be a
     * corner the runner has already turned.
     *
     * The course goes north six hundred and thirty metres, turns right for three hundred east, then
     * turns left again. The runner is last seen a hundred metres in, then the fixes stop — a tunnel,
     * a pocket that lost the sky, a Pause spent in a car — and the next one lands thirty metres round
     * the first corner. The window opened at a hundred reaches six hundred, so the piece of course
     * the runner is really on was never looked at; the nearest place in the window is the corner
     * itself, thirty metres away, which is well inside honest wander and so never reads as having
     * left the course.
     *
     * Believed, that says "Turn right." to a runner who has already turned right. The reading is not
     * a place, so the whole line is read instead, and the corner behind them is stepped over in
     * silence. The corner still in front of them is announced normally.
     */
    @Test
    fun `a jump past the window does not announce the corner its edge lands on`() {
        val corner = (0..23).map { at(it * 25.0) } + at(580.0) + at(630.0)
        val ell = corner +
            (1..12).map { at(630.0, it * 25.0) } +
            (1..8).map { at(630.0 + it * 25.0, 300.0) }
        val watch = CourseTurnWatch(CourseLine.of(ell)!!, courseTurnsOf(ell))

        fun say(north: Double, east: Double): List<String> {
            val place = at(north, east)
            return watch.onFix(
                LocationFix(place.latitude, place.longitude, 5f, 3f, 0L),
                autoPaused = false,
            ).said.map { it.cue.spoken }
        }

        assertEquals(nothing, say(0.0, 0.0))
        assertEquals(nothing, say(100.0, 0.0))

        // Five hundred and thirty metres on, and thirty metres the far side of the first corner.
        assertEquals(nothing, say(630.0, 30.0))

        // Read from where they really are, the second corner at 930 m is still theirs to hear.
        assertEquals(nothing, say(630.0, 200.0))
        assertEquals(listOf("Turn left in 50 metres."), say(630.0, 255.0))
        assertEquals(listOf("Turn left."), say(632.0, 300.0))
    }

    /**
     * #471: a jump that lands the runner on an earlier lap takes back what is still waiting.
     *
     * A cue is enqueued and not spoken (#53), so a "Turn right in 50 metres." can still be sitting
     * behind a longer sentence when the next fix lands. Normally the ground kills it: the producer
     * withdraws every cue the runner has reached the ground of. That only works while the ground
     * goes forwards.
     *
     * Here it does not. The course is an eight hundred metre square run twice, so every metre of it
     * is covered twice over. The runner is last seen fifty metres short of the second lap's first
     * corner — the warning about that corner is spoken and goes into the queue — and the next fix
     * lands more than the window is long further on. The whole line is read instead, and the whole
     * line cannot tell the second lap from the first: it answers with the first, which is thousands
     * of metres *behind* the corner the queued warning is about. Judged by ground, that warning is
     * not stale and never will be, and the runner is told to turn right long after they have.
     *
     * So the arrival says so itself: everything waiting goes back, whatever ground it named. The
     * pointer is a separate matter and is not rewound — nothing already said is said again — which
     * is why this fix has nothing of its own to say.
     */
    @Test
    fun `a jump landing on an earlier lap takes back what is waiting`() {
        // An eight hundred metre square, run twice: corners every eight hundred metres, and every
        // place on it is two places on the line.
        val lap = (0..31).map { at(it * 25.0) } +
            (0..31).map { at(800.0, it * 25.0) } +
            (0..31).map { at(800.0 - it * 25.0, 800.0) } +
            (0..31).map { at(0.0, 800.0 - it * 25.0) }
        val twoLaps = lap + lap + listOf(at(0.0))
        val watch = CourseTurnWatch(CourseLine.of(twoLaps)!!, courseTurnsOf(twoLaps))

        /** Where a runner this far along the square is, lap after lap. */
        fun placeAt(alongMeters: Double): RoutePoint {
            val round = alongMeters % 3200.0
            return when {
                round <= 800.0 -> at(round)
                round <= 1600.0 -> at(800.0, round - 800.0)
                round <= 2400.0 -> at(800.0 - (round - 1600.0), 800.0)
                else -> at(0.0, 800.0 - (round - 2400.0))
            }
        }

        fun heardAt(place: RoutePoint): TurnVoice = watch.onFix(
            LocationFix(place.latitude, place.longitude, 5f, 3f, 0L),
            autoPaused = false,
        )

        // Round the first lap and into the second, two hundred metres at a time, hearing the
        // corners as they come.
        var along = 0.0
        while (along <= 3800.0) {
            heardAt(placeAt(along))
            along += 200.0
        }

        // Forty metres short of the second lap's first corner: the warning about it is spoken, and
        // it stops being true at the corner itself, four thousand metres along.
        val warned = heardAt(placeAt(3960.0))
        assertEquals(listOf("Turn right in 50 metres."), warned.said.map { it.cue.spoken })
        assertEquals(4000.0, warned.said.single().falseFromAlongMeters, 10.0)

        // Six hundred metres on — past the corner and well down the square's east side — which is
        // further than the window reaches, so the whole line is read and answers with the first lap.
        val jumped = heardAt(placeAt(4560.0))

        assertTrue("the arrival must take back what is waiting", jumped.takeBackWhatIsWaiting)
        assertEquals(nothing, jumped.said.map { it.cue.spoken })
        // And this is why ground alone cannot be trusted to do it: the reading is behind the ground
        // the queued warning dies at, so no amount of running forwards would ever have killed it.
        assertTrue(
            "the reading should have landed on the earlier lap",
            jumped.alongMeters!! < warned.said.single().falseFromAlongMeters,
        )
    }

    /**
     * #471: a jump that lands honestly wide of the line must not un-reach the course.
     *
     * Reaching the course is a latch — once a runner has been seen on the line they have been seen
     * on it — but a jump past the window's far edge is read against the whole line with nothing
     * before it, so that reading can only latch on how far off the line it landed. Thirty-one to
     * fifty metres off is the gap: far enough that the reading itself says the course has not been
     * reached, near enough that it is honest wander and the reading is kept as where the runner is.
     *
     * Here the runner is last seen a hundred metres in, the fixes stop, and the next one lands
     * seventy metres round the first corner and forty metres out into the field beside the course —
     * a runner on the far verge of the road, which is ordinary. Kept as an anchor with its own "not
     * reached" on it, every fix after it would chain from that, and a runner who stayed forty metres
     * off the line would be told nothing about the corner in front of them. They are told.
     */
    @Test
    fun `a jump landing wide of the line still earns the corners in front of it`() {
        val corner = (0..23).map { at(it * 25.0) } + at(580.0) + at(630.0)
        val ell = corner +
            (1..24).map { at(630.0, it * 25.0) } +
            (1..8).map { at(630.0 + it * 25.0, 600.0) }
        val watch = CourseTurnWatch(CourseLine.of(ell)!!, courseTurnsOf(ell))

        fun say(north: Double, east: Double): List<String> {
            val place = at(north, east)
            return watch.onFix(
                LocationFix(place.latitude, place.longitude, 5f, 3f, 0L),
                autoPaused = false,
            ).said.map { it.cue.spoken }
        }

        assertEquals(nothing, say(0.0, 0.0))
        assertEquals(nothing, say(100.0, 0.0))

        // Six hundred metres on — further than the window reaches — and forty metres out past the
        // second arm, seventy metres beyond the first corner. That corner is stepped over in
        // silence, and the whole-line reading kept as where the runner is says, of itself, that the
        // course has not been reached.
        assertEquals(nothing, say(670.0, 70.0))

        // Forty metres off the line the whole way down the second arm, and still owed the corner at
        // the end of it, twelve hundred and thirty metres in.
        assertEquals(nothing, say(670.0, 150.0))
        assertEquals(nothing, say(670.0, 400.0))
        assertEquals(listOf("Turn left in 50 metres."), say(670.0, 560.0))
        assertEquals(listOf("Turn left."), say(645.0, 635.0))
    }
}
