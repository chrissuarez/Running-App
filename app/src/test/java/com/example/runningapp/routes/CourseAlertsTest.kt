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
        speak = { alert -> cues.record(CueTag.COURSE) { queue.enqueue(alert.spoken) } },
        withdraw = { queue.withdrawAll(cues.takeBack(CueTag.COURSE)) },
        nowMillis = { clockMillis },
    )

    private val originLatitude = 51.5
    private val originLongitude = -0.1
    private val metersPerDegreeLatitude = 111_132.0

    private fun at(northMeters: Double) = RoutePoint(
        latitude = originLatitude + northMeters / metersPerDegreeLatitude,
        longitude = originLongitude,
        elevationMeters = null,
    )

    private val straightKilometre = (0..10).map { at(it * 100.0) }

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
}
