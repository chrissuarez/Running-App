package com.example.runningapp.routes

import com.example.runningapp.OutstandingCues
import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteSource
import com.example.runningapp.recording.LocationFix
import com.example.runningapp.run.CueTag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the app has waiting to be said about the course, when the course goes (#377).
 *
 * The queue here is a stand-in that says nothing at all: every cue enqueued is a cue still waiting,
 * which is the phone in the middle of a split announcement. The bookkeeping in front of it is the
 * real [OutstandingCues], so a cue is taken back the way the service takes one back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CourseAlertsTest {

    /**
     * A stand-in for the cue queue: cues wait in it until [speakNext] is called, which is the phone
     * working through a sentence. A cue already spoken cannot be unsaid, and taking its ticket back
     * does nothing — the same as the real queue (`AudioCueManager.withdrawAll`).
     */
    private class WaitingCues {
        private val waiting = LinkedHashMap<Long, String>()
        private var lastTicket = 0L

        /** What has been said out loud, oldest first. */
        val spoken = mutableListOf<String>()

        fun enqueue(text: String): Long {
            val ticket = ++lastTicket
            waiting[ticket] = text
            return ticket
        }

        fun withdrawAll(tickets: Collection<Long>) = tickets.forEach { waiting.remove(it) }

        /** Say the cue at the front of the queue, as the engine does when it finishes a sentence. */
        fun speakNext() {
            val next = waiting.keys.firstOrNull() ?: return
            spoken += waiting.remove(next)!!
        }

        fun texts(): List<String> = waiting.values.toList()
    }

    private val queue = WaitingCues()
    private val cues = OutstandingCues()
    private var clockMillis = 0L
    private val alerts = CourseAlerts(
        speak = { saying -> cues.record(CueTag.COURSE) { queue.enqueue(saying.spoken) } },
        withdraw = { queue.withdrawAll(cues.takeBack(CueTag.COURSE)) },
        withdrawCues = { tickets -> queue.withdrawAll(cues.takeBackTickets(tickets)) },
        nowMillis = { clockMillis },
    )

    private val originLatitude = 51.5
    private val originLongitude = -0.1
    private val metersPerDegreeLatitude = 111_132.0

    private val metersPerDegreeLongitude = 69_300.0

    private fun at(northMeters: Double, eastMeters: Double = 0.0) = RoutePoint(
        latitude = originLatitude + northMeters / metersPerDegreeLatitude,
        longitude = originLongitude + eastMeters / metersPerDegreeLongitude,
        elevationMeters = null,
    )

    private val straightKilometre = (0..10).map { at(it * 100.0) }

    /**
     * Five hundred metres north, a right-angle turn, five hundred metres east. The one corner sits
     * five hundred metres along it, so its warning is earned at four hundred and fifty.
     */
    private val courseWithOneTurn =
        (0..5).map { at(it * 100.0) } + (1..5).map { at(500.0, it * 100.0) }

    /**
     * A dog-leg: five hundred metres north, sixty east, then north again. Two turns — a right at
     * five hundred and a left at five hundred and sixty — and sixty metres apart they are two
     * instructions, not one ([TURNS_TOGETHER_METERS] merges only what is nearer than fifty).
     *
     * The gap is what matters. The second turn's warning is earned at five hundred and ten, which
     * is inside the twenty metres the first turn's own cue is still worth hearing for, so a busy
     * queue holds them both at once — and they stop being true thirty metres apart.
     */
    private val courseWithTwoTurns =
        (0..5).map { at(it * 100.0) } +
            (1..3).map { at(500.0, it * 20.0) } +
            (1..10).map { at(500.0 + it * 40.0, 60.0) }

    /** A fix [alongMeters] along [courseWithTwoTurns]. */
    private fun onTheDogLeg(alongMeters: Double) = when {
        alongMeters <= 500.0 -> at(alongMeters)
        alongMeters <= 560.0 -> at(500.0, alongMeters - 500.0)
        else -> at(500.0 + (alongMeters - 560.0), 60.0)
    }.let {
        LocationFix(it.latitude, it.longitude, 5f, 3f, 0L)
    }

    /**
     * The corner of [courseWithOneTurn] exactly as the library stores it — the polyline is kept to
     * five decimal places, so a place written here and the same place read back are not the same
     * number, and only the one read back can be landed on exactly.
     *
     * A fix here projects onto the turn's own vertex, which makes its `alongMeters` bit-for-bit the
     * turn's own: the equality boundary, which is otherwise unreachable from a made-up fix.
     */
    private val theCornerAsKept = RoutePolyline.decode(RoutePolyline.encode(courseWithOneTurn))[5]

    /** A fix [alongMeters] along [courseWithOneTurn], and [offMeters] to the left of the line. */
    private fun onTheTurningCourse(alongMeters: Double, offMeters: Double = 0.0) =
        (if (alongMeters <= 500.0) at(alongMeters, -offMeters) else at(500.0 + offMeters, alongMeters - 500.0))
            .let {
                LocationFix(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    accuracyMeters = 5f,
                    speedMps = 3f,
                    timestampMs = 0L,
                )
            }

    private fun fixAt(eastMeters: Double) = LocationFix(
        latitude = at(500.0).latitude,
        longitude = originLongitude + eastMeters / 69_300.0,
        accuracyMeters = 5f,
        speedMps = 3f,
        timestampMs = 0L,
    )

    /** Halfway along the course, on the line. */
    private val onTheLine = fixAt(0.0)

    /** The same place, sixty metres east of it — past [OFF_COURSE_METERS], so worth a sentence. */
    private val offTheLine = fixAt(60.0)

    private suspend fun FakeRouteDao.keep(points: List<RoutePoint>): Long = insertRoute(
        Route(
            name = "A course",
            polyline = RoutePolyline.encode(points),
            distanceMeters = routeDistanceMeters(points),
            elevationGainMeters = null,
            createdAtMillis = 0L,
            source = RouteSource.IMPORTED,
        )
    )

    /**
     * A Route in the library, and the Run out on it — the collection every test starts from. It is
     * left running in [backgroundScope], which cancels it when the test ends.
     */
    private fun TestScope.runningTheCourse(dao: FakeRouteDao, routeId: Long): Job {
        val watching = backgroundScope.launch {
            alerts.follow(courseToWatchFlow(dao, routeId, reversed = false))
        }
        runCurrent()
        return watching
    }

    /** One fix, at [clockMillis] seconds into the Run. */
    private fun fix(at: LocationFix, secondsIn: Long) {
        clockMillis = secondsIn * 1_000L
        alerts.onFix(at, autoPaused = false)
    }

    /** Run out on the line, then sixty metres off it for longer than the app waits. */
    private fun strayOffTheCourse() {
        fix(onTheLine, secondsIn = 0)
        fix(offTheLine, secondsIn = 1)
        fix(offTheLine, secondsIn = 12)
    }

    /**
     * The whole of #377: the sentence is true when it is made and the line is gone before it is
     * said. Nothing else in the app is still showing that course — the live map stopped drawing it
     * the moment the row went — so speaking it tells the runner about a course that is not there.
     */
    @Test
    fun `a course cue waiting behind a sentence is taken back when the Route is deleted`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)
        val watching = runningTheCourse(dao, routeId)

        strayOffTheCourse()
        assertEquals(listOf(CourseAlert.OFF_COURSE.spoken), queue.texts())

        dao.deleteRoute(routeId)
        runCurrent()

        assertEquals(emptyList<String>(), queue.texts())
        watching.cancel()
    }

    /**
     * Both of them, when both are waiting: a slow enough sentence in front holds the whole pair, and
     * taking back only the newer would say "Off course." about a course that has gone.
     */
    @Test
    fun `both course cues waiting are taken back together`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)
        val watching = runningTheCourse(dao, routeId)

        strayOffTheCourse()
        fix(onTheLine, secondsIn = 20)
        assertEquals(
            listOf(CourseAlert.OFF_COURSE.spoken, CourseAlert.BACK_ON_COURSE.spoken),
            queue.texts(),
        )

        dao.deleteRoute(routeId)
        runCurrent()

        assertEquals(emptyList<String>(), queue.texts())
        watching.cancel()
    }

    /**
     * The other side of it. Room hands the query its rows again whenever the table is written to,
     * and a Route renamed elsewhere in the library is not this course going anywhere — the runner is
     * still off the line, and still has to be told so.
     */
    @Test
    fun `a write elsewhere in the library leaves what is waiting alone`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)
        val someOtherRoute = dao.keep(straightKilometre.take(3))
        val watching = runningTheCourse(dao, routeId)

        strayOffTheCourse()
        dao.renameRoute(someOtherRoute, "Somewhere else entirely")
        runCurrent()

        assertEquals(listOf(CourseAlert.OFF_COURSE.spoken), queue.texts())
        watching.cancel()
    }

    /** The end of a routed Run stops the watch, and takes back what it never got to say. */
    @Test
    fun `stopping the watch takes back what the course had waiting`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)
        val watching = runningTheCourse(dao, routeId)

        strayOffTheCourse()
        watching.cancel()
        alerts.stop()

        assertEquals(emptyList<String>(), queue.texts())
        // And a fix after it is measured against nothing at all.
        fix(offTheLine, secondsIn = 40)
        assertEquals(emptyList<String>(), queue.texts())
    }

    /**
     * A cue made after the change is the new course's and stays: the withdrawal is of what the old
     * line left behind, not a sweep of everything the app has to say about a course.
     */
    @Test
    fun `a cue the new course makes is not taken back by the change that began it`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)
        val watching = runningTheCourse(dao, routeId)

        strayOffTheCourse()
        // The Route is edited to a shorter line over the same start — a course that has genuinely
        // changed shape, so the watch is replaced and what the old one left waiting goes with it.
        dao.replaceLine(routeId, RoutePolyline.encode(straightKilometre.take(6)))
        runCurrent()
        assertEquals(emptyList<String>(), queue.texts())

        strayOffTheCourse()

        assertEquals(listOf(CourseAlert.OFF_COURSE.spoken), queue.texts())
        watching.cancel()
    }

    /**
     * Half the pair spoken and half of it waiting, which is the window #377 describes with the queue
     * actually moving. What has been said cannot be unsaid; what is still waiting goes.
     */
    @Test
    fun `a pair half spoken loses only the half nobody has heard`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)
        val watching = runningTheCourse(dao, routeId)

        strayOffTheCourse()
        queue.speakNext()
        fix(onTheLine, secondsIn = 20)
        assertEquals(listOf(CourseAlert.BACK_ON_COURSE.spoken), queue.texts())

        dao.deleteRoute(routeId)
        runCurrent()

        assertEquals(emptyList<String>(), queue.texts())
        assertEquals(listOf(CourseAlert.OFF_COURSE.spoken), queue.spoken)
        watching.cancel()
    }

    /**
     * Cancelling a collection is a request, not an act: a course already on its way to a collector
     * that has been stopped must not put itself back. Here the collection is never cancelled at all
     * and is stopped only by [CourseAlerts.stop], which is the harder half of the same case.
     */
    @Test
    fun `a course arriving after the watch was stopped is not watched`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)
        val watching = runningTheCourse(dao, routeId)

        alerts.stop()
        dao.replaceLine(routeId, RoutePolyline.encode(straightKilometre.take(6)))
        runCurrent()

        strayOffTheCourse()
        assertEquals(emptyList<String>(), queue.texts())
        watching.cancel()
    }

    /**
     * The whole of the P1 #460's review found. A warning is a claim about ground fifty metres ahead,
     * the queue drops nothing (#53), and a sentence already being spoken holds the warning while the
     * runner covers that ground. Heard then, it would send them fifty metres past the turning they
     * are standing on — so reaching the turn takes it back, and the at-the-turn cue is the one true
     * sentence left.
     */
    @Test
    fun `a turn warning still waiting when the turn is reached is taken back`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(courseWithOneTurn)
        val watching = runningTheCourse(dao, routeId)

        fix(onTheTurningCourse(400.0), secondsIn = 0)
        fix(onTheTurningCourse(460.0), secondsIn = 10)
        assertEquals(listOf("Turn right in 50 metres."), queue.texts())

        fix(onTheTurningCourse(505.0), secondsIn = 20)

        assertEquals(listOf("Turn right."), queue.texts())
        watching.cancel()
    }

    /**
     * And it takes back the warning alone. An "Off course." waiting beside it is about the runner
     * being sixty metres off the line, which reaching the turn does nothing to — they are still off
     * it, and still have to be told.
     */
    @Test
    fun `taking back a turn warning leaves an off-course cue waiting beside it`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(courseWithOneTurn)
        val watching = runningTheCourse(dao, routeId)

        fix(onTheTurningCourse(400.0), secondsIn = 0)
        fix(onTheTurningCourse(460.0, offMeters = 60.0), secondsIn = 1)
        assertEquals(listOf("Turn right in 50 metres."), queue.texts())

        fix(onTheTurningCourse(505.0, offMeters = 60.0), secondsIn = 12)

        assertEquals(listOf(CourseAlert.OFF_COURSE.spoken, "Turn right."), queue.texts())
        watching.cancel()
    }

    /**
     * The same rule at the turn itself, which is where #460's second round found it. "Turn left."
     * is merely *late* for a while after the corner — twenty metres of it, the same allowance that
     * decides whether the cue is worth making at all — and wrong after that. So it survives a fix
     * still inside the allowance and goes on the first fix past it.
     */
    @Test
    fun `the turn's own cue is taken back once the runner is well past the corner`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(courseWithOneTurn)
        val watching = runningTheCourse(dao, routeId)

        fix(onTheTurningCourse(400.0), secondsIn = 0)
        fix(onTheTurningCourse(460.0), secondsIn = 10)
        fix(onTheTurningCourse(505.0), secondsIn = 20)
        assertEquals(listOf("Turn right."), queue.texts())

        // Fifteen metres past the corner: still worth hearing, still waiting.
        fix(onTheTurningCourse(515.0), secondsIn = 22)
        assertEquals(listOf("Turn right."), queue.texts())

        // Thirty. The corner is behind them and the sentence has stopped being true.
        fix(onTheTurningCourse(530.0), secondsIn = 25)
        assertEquals(emptyList<String>(), queue.texts())
        watching.cancel()
    }

    /**
     * And it takes back the turn cues alone. An "Off course." waiting beside them is about the
     * runner being sixty metres off the line, which running past a corner does nothing to — they are
     * still off it, and still have to be told.
     */
    @Test
    fun `taking back stale turn cues leaves an off-course cue waiting beside them`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(courseWithOneTurn)
        val watching = runningTheCourse(dao, routeId)

        fix(onTheTurningCourse(400.0), secondsIn = 0)
        fix(onTheTurningCourse(460.0, offMeters = 60.0), secondsIn = 1)
        fix(onTheTurningCourse(505.0, offMeters = 60.0), secondsIn = 12)
        assertEquals(listOf(CourseAlert.OFF_COURSE.spoken, "Turn right."), queue.texts())

        fix(onTheTurningCourse(530.0, offMeters = 60.0), secondsIn = 20)

        assertEquals(listOf(CourseAlert.OFF_COURSE.spoken), queue.texts())
        watching.cancel()
    }

    /**
     * #460's third round, and why the deadline lives on the cue rather than on the watch.
     *
     * Two turns sixty metres apart put two cues in the queue together — the first turn's own
     * "Turn right." and the second turn's "Turn left in 50 metres." — and they stop being true
     * thirty metres apart. Held under one deadline, either the live one goes with the dead one or
     * the dead one waits for the live one; both are a wrong sentence in the runner's ear. Each cue
     * carries its own, so thirty metres past the first corner exactly one of them goes.
     */
    @Test
    fun `a stale turn cue goes and the live one waiting behind it stays`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(courseWithTwoTurns)
        val watching = runningTheCourse(dao, routeId)

        fix(onTheDogLeg(400.0), secondsIn = 0)
        fix(onTheDogLeg(460.0), secondsIn = 10)
        assertEquals(listOf("Turn right in 50 metres."), queue.texts())

        // At the first corner: its own cue is earned, the second corner's warning with it, and the
        // warning about the corner they are standing on goes.
        fix(onTheDogLeg(512.0), secondsIn = 20)
        assertEquals(listOf("Turn right.", "Turn left in 50 metres."), queue.texts())

        // Thirty metres past the first corner. "Turn right." has stopped being true; the second
        // corner is still thirty metres ahead and its warning is still owed.
        fix(onTheDogLeg(530.0), secondsIn = 24)

        assertEquals(listOf("Turn left in 50 metres."), queue.texts())
        watching.cancel()
    }

    /**
     * The boundary #460's fourth round found, on the warning: a fix that lands exactly on the turn.
     *
     * The warning is false *from* the turn, not from a step past it. Left standing there because
     * the runner is not yet strictly past the corner, it would be spoken from on top of the corner
     * and send them fifty metres beyond it. Reaching the ground is what kills it.
     */
    @Test
    fun `a fix landing exactly on the turn takes the warning back`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(courseWithOneTurn)
        val watching = runningTheCourse(dao, routeId)

        fix(onTheTurningCourse(400.0), secondsIn = 0)
        fix(onTheTurningCourse(460.0), secondsIn = 10)
        assertEquals(listOf("Turn right in 50 metres."), queue.texts())

        fix(LocationFix(theCornerAsKept.latitude, theCornerAsKept.longitude, 5f, 3f, 0L), secondsIn = 18)

        assertEquals(listOf("Turn right."), queue.texts())
        watching.cancel()
    }
}
