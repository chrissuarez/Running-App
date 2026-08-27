package com.example.runningapp.routes

import com.example.runningapp.recording.LocationFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Scripted fixes over scripted courses (#58).
 *
 * The course is a straight kilometre due north of one spot in London and every fix is written in
 * metres north and east of the same spot, so "east 60" means "sixty metres off the line" and the
 * test reads as the sentence the runner has to be told: sixty metres off for ten seconds is a wrong
 * turn, and back within thirty is the end of it.
 *
 * Time is written the same way — the clock is handed in a fix at a time — because the whole of what
 * this module decides beyond the distance is *how long*, and a test that could not move the clock
 * could not test it.
 */
class OffCourseTest {

    private val originLatitude = 51.5
    private val originLongitude = -0.1

    /** Metres in a degree at the origin, for writing a course and its fixes in metres. */
    private val metersPerDegreeLatitude = 111_132.0
    private val metersPerDegreeLongitude = 69_300.0

    private fun at(northMeters: Double, eastMeters: Double = 0.0) = RoutePoint(
        latitude = originLatitude + northMeters / metersPerDegreeLatitude,
        longitude = originLongitude + eastMeters / metersPerDegreeLongitude,
        elevationMeters = null,
    )

    /** A course due north: eleven points a hundred metres apart, so a straight kilometre. */
    private val straightKilometre: List<RoutePoint> = (0..10).map { at(it * 100.0) }

    /** A fix good enough to be read, at [northMeters] up the course and [eastMeters] off it. */
    private fun fix(
        northMeters: Double,
        eastMeters: Double = 0.0,
        accuracyMeters: Float? = 5f,
    ) = at(northMeters, eastMeters).let {
        LocationFix(
            latitude = it.latitude,
            longitude = it.longitude,
            accuracyMeters = accuracyMeters,
            speedMps = 3f,
            timestampMs = 0L,
        )
    }

    /** The clock, in seconds since the Run began — every test writes its waits in seconds. */
    private fun seconds(value: Long) = value * 1000L

    private fun watch() = OffCourseWatch.of(straightKilometre)!!

    /**
     * Walk the runner up the course to arm the alerts, at [second] on the clock. The first fix is
     * on the line, which is all arming asks for.
     */
    private fun OffCourseWatch.reachTheCourse(second: Long = 0L): CourseAlert? =
        onFix(fix(0.0), seconds(second), autoPaused = false)

    @Test
    fun `a Run following no course has nothing to watch`() {
        assertNull(OffCourseWatch.of(emptyList()))
        assertNull(OffCourseWatch.of(listOf(at(0.0))))
    }

    @Test
    fun `the walk from the front door is not a wrong turn`() {
        val watch = watch()

        // Two hundred metres off the line, for a minute, without ever having been on the course.
        var alert: CourseAlert? = null
        for (second in 0..60L) {
            alert = alert ?: watch.onFix(fix(0.0, eastMeters = 200.0), seconds(second), autoPaused = false)
        }

        assertNull(alert)
    }

    @Test
    fun `off the line for ten seconds is a wrong turn`() {
        val watch = watch()
        watch.reachTheCourse()

        // Sixty metres east of the line from second one.
        assertNull(watch.onFix(fix(100.0, eastMeters = 60.0), seconds(1), autoPaused = false))
        assertNull(watch.onFix(fix(100.0, eastMeters = 60.0), seconds(10), autoPaused = false))
        assertEquals(
            CourseAlert.OFF_COURSE,
            watch.onFix(fix(100.0, eastMeters = 60.0), seconds(11), autoPaused = false),
        )
    }

    @Test
    fun `it is said once and not again while the runner stays out there`() {
        val watch = watch()
        watch.reachTheCourse()
        watch.onFix(fix(100.0, eastMeters = 60.0), seconds(1), autoPaused = false)
        assertEquals(
            CourseAlert.OFF_COURSE,
            watch.onFix(fix(100.0, eastMeters = 60.0), seconds(11), autoPaused = false),
        )

        for (second in 12..120L) {
            assertNull(watch.onFix(fix(100.0, eastMeters = 200.0), seconds(second), autoPaused = false))
        }
    }

    @Test
    fun `a detour shorter than the wait says nothing at all`() {
        val watch = watch()
        watch.reachTheCourse()

        assertNull(watch.onFix(fix(100.0, eastMeters = 60.0), seconds(1), autoPaused = false))
        assertNull(watch.onFix(fix(150.0, eastMeters = 60.0), seconds(8), autoPaused = false))
        // Back on the line before the ten seconds were up, and then out again: the wait starts over,
        // so nine seconds plus eight is not seventeen seconds of wrong turn.
        assertNull(watch.onFix(fix(200.0), seconds(9), autoPaused = false))
        assertNull(watch.onFix(fix(250.0, eastMeters = 60.0), seconds(10), autoPaused = false))
        assertNull(watch.onFix(fix(300.0, eastMeters = 60.0), seconds(19), autoPaused = false))
    }

    @Test
    fun `coming back within thirty metres closes it`() {
        val watch = watch()
        watch.reachTheCourse()
        watch.onFix(fix(100.0, eastMeters = 60.0), seconds(1), autoPaused = false)
        watch.onFix(fix(100.0, eastMeters = 60.0), seconds(11), autoPaused = false)

        // Forty metres out is still out: nothing is said between the two cues.
        assertNull(watch.onFix(fix(100.0, eastMeters = 40.0), seconds(12), autoPaused = false))
        assertEquals(
            CourseAlert.BACK_ON_COURSE,
            watch.onFix(fix(100.0, eastMeters = 20.0), seconds(13), autoPaused = false),
        )
    }

    @Test
    fun `back on course is said once`() {
        val watch = watch()
        watch.reachTheCourse()
        watch.onFix(fix(100.0, eastMeters = 60.0), seconds(1), autoPaused = false)
        watch.onFix(fix(100.0, eastMeters = 60.0), seconds(11), autoPaused = false)
        watch.onFix(fix(100.0, eastMeters = 20.0), seconds(12), autoPaused = false)

        for (second in 13..60L) {
            assertNull(watch.onFix(fix(200.0), seconds(second), autoPaused = false))
        }
    }

    @Test
    fun `a second wrong turn is told the same as the first`() {
        val watch = watch()
        watch.reachTheCourse()
        watch.onFix(fix(100.0, eastMeters = 60.0), seconds(1), autoPaused = false)
        watch.onFix(fix(100.0, eastMeters = 60.0), seconds(11), autoPaused = false)
        watch.onFix(fix(100.0, eastMeters = 0.0), seconds(12), autoPaused = false)

        assertNull(watch.onFix(fix(300.0, eastMeters = 60.0), seconds(100), autoPaused = false))
        assertEquals(
            CourseAlert.OFF_COURSE,
            watch.onFix(fix(300.0, eastMeters = 60.0), seconds(110), autoPaused = false),
        )
    }

    @Test
    fun `a fix too coarse to trust is not heard`() {
        val watch = watch()
        watch.reachTheCourse()

        // Ten seconds of sixty-metres-out, every one of them on a fix accurate to a hundred metres.
        for (second in 1..30L) {
            assertNull(
                watch.onFix(
                    fix(100.0, eastMeters = 60.0, accuracyMeters = 100f),
                    seconds(second),
                    autoPaused = false,
                ),
            )
        }
    }

    @Test
    fun `a fix with no accuracy at all is not heard`() {
        val watch = watch()
        watch.reachTheCourse()

        for (second in 1..30L) {
            assertNull(
                watch.onFix(
                    fix(100.0, eastMeters = 60.0, accuracyMeters = null),
                    seconds(second),
                    autoPaused = false,
                ),
            )
        }
    }

    @Test
    fun `the wait starts again after signal comes back`() {
        val watch = watch()
        watch.reachTheCourse()

        assertNull(watch.onFix(fix(100.0, eastMeters = 60.0), seconds(1), autoPaused = false))
        // Nine seconds of it lost to a coarse fix, so the wait is not nearly up when it clears.
        assertNull(
            watch.onFix(
                fix(100.0, eastMeters = 60.0, accuracyMeters = 100f),
                seconds(9),
                autoPaused = false,
            ),
        )
        assertNull(watch.onFix(fix(100.0, eastMeters = 60.0), seconds(10), autoPaused = false))
        assertNull(watch.onFix(fix(100.0, eastMeters = 60.0), seconds(19), autoPaused = false))
        assertEquals(
            CourseAlert.OFF_COURSE,
            watch.onFix(fix(100.0, eastMeters = 60.0), seconds(20), autoPaused = false),
        )
    }

    @Test
    fun `nothing is said while the Run is auto-paused`() {
        val watch = watch()
        watch.reachTheCourse()

        for (second in 1..30L) {
            assertNull(watch.onFix(fix(100.0, eastMeters = 60.0), seconds(second), autoPaused = true))
        }
        // And the wait begins at the fix after it, not back where the standstill started.
        assertNull(watch.onFix(fix(100.0, eastMeters = 60.0), seconds(31), autoPaused = false))
        assertNull(watch.onFix(fix(100.0, eastMeters = 60.0), seconds(40), autoPaused = false))
        assertEquals(
            CourseAlert.OFF_COURSE,
            watch.onFix(fix(100.0, eastMeters = 60.0), seconds(41), autoPaused = false),
        )
    }

    @Test
    fun `being off course survives a stretch of fixes that were not heard`() {
        val watch = watch()
        watch.reachTheCourse()
        watch.onFix(fix(100.0, eastMeters = 60.0), seconds(1), autoPaused = false)
        assertEquals(
            CourseAlert.OFF_COURSE,
            watch.onFix(fix(100.0, eastMeters = 60.0), seconds(11), autoPaused = false),
        )

        // The phone loses the sky out there, and finds it again back on the line.
        assertNull(
            watch.onFix(
                fix(150.0, eastMeters = 60.0, accuracyMeters = 100f),
                seconds(20),
                autoPaused = false,
            ),
        )
        assertEquals(
            CourseAlert.BACK_ON_COURSE,
            watch.onFix(fix(200.0), seconds(30), autoPaused = false),
        )
    }

    @Test
    fun `rejoining the course far ahead of where it was left still closes it`() {
        val watch = watch()
        watch.reachTheCourse()
        watch.onFix(fix(50.0, eastMeters = 200.0), seconds(1), autoPaused = false)
        assertEquals(
            CourseAlert.OFF_COURSE,
            watch.onFix(fix(50.0, eastMeters = 200.0), seconds(11), autoPaused = false),
        )

        // A wrong turn that cuts a corner and comes back on 900 m up the course — further ahead than
        // the stretch of line the fix before it opened a window on. Read from the old place, the
        // runner would be hundreds of metres from the line for the rest of the Run.
        assertEquals(
            CourseAlert.BACK_ON_COURSE,
            watch.onFix(fix(950.0), seconds(200), autoPaused = false),
        )
    }

    @Test
    fun `the course is read from where the runner rejoined it`() {
        val watch = watch()
        watch.reachTheCourse()
        watch.onFix(fix(50.0, eastMeters = 200.0), seconds(1), autoPaused = false)
        watch.onFix(fix(50.0, eastMeters = 200.0), seconds(11), autoPaused = false)
        watch.onFix(fix(950.0), seconds(200), autoPaused = false)

        // Running on from there is running along the course, not away from it: the window reopened
        // around the far end, so the last fifty metres read as nothing to say.
        assertNull(watch.onFix(fix(1000.0), seconds(210), autoPaused = false))
        assertNull(watch.onFix(fix(1000.0), seconds(230), autoPaused = false))
    }

    @Test
    fun `a Pause does not count towards the wait`() {
        val watch = watch()
        watch.reachTheCourse()
        assertNull(watch.onFix(fix(100.0, eastMeters = 60.0), seconds(1), autoPaused = false))

        // Five minutes of standing still with GPS torn down, then one fix on the far side of it.
        watch.recordingBroke()
        assertNull(watch.onFix(fix(100.0, eastMeters = 60.0), seconds(300), autoPaused = false))
        assertNull(watch.onFix(fix(100.0, eastMeters = 60.0), seconds(309), autoPaused = false))
        assertEquals(
            CourseAlert.OFF_COURSE,
            watch.onFix(fix(100.0, eastMeters = 60.0), seconds(310), autoPaused = false),
        )
    }

    @Test
    fun `a Pause taken out there leaves the runner off course`() {
        val watch = watch()
        watch.reachTheCourse()
        watch.onFix(fix(100.0, eastMeters = 60.0), seconds(1), autoPaused = false)
        watch.onFix(fix(100.0, eastMeters = 60.0), seconds(11), autoPaused = false)

        watch.recordingBroke()

        // Still off course on the far side of it, so coming back is still the closing cue and is
        // still said only once.
        assertEquals(
            CourseAlert.BACK_ON_COURSE,
            watch.onFix(fix(100.0), seconds(300), autoPaused = false),
        )
        assertNull(watch.onFix(fix(200.0), seconds(310), autoPaused = false))
    }

    @Test
    fun `the two sentences are the two the runner hears`() {
        assertEquals("Off course.", CourseAlert.OFF_COURSE.spoken)
        assertEquals("Back on course.", CourseAlert.BACK_ON_COURSE.spoken)
    }
}
