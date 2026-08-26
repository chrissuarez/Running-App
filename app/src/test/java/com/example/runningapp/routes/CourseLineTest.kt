package com.example.runningapp.routes

import com.example.runningapp.analysis.MapFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scripted fixes over scripted courses (#57).
 *
 * Every course here is written in metres north of one spot in London and every fix is written the
 * same way, so what each test says is "the runner is 300 m up a 1 km course, so 700 m is left" — the
 * sentence the screen has to be right about. Distances are asserted against
 * [routeDistanceMeters], the same measurement the Route's own row was banked with, rather than
 * against numbers typed out here: a test that agreed with a hand-typed 1000.0 and disagreed with the
 * library screen would be the wrong test passing.
 */
class CourseLineTest {

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

    private fun fix(northMeters: Double, eastMeters: Double = 0.0) =
        MapFix(at(northMeters, eastMeters).latitude, at(northMeters, eastMeters).longitude)

    /** A course due north: [count] points, [spacingMeters] apart. */
    private fun northwards(count: Int, spacingMeters: Double): List<RoutePoint> =
        (0 until count).map { at(it * spacingMeters) }

    /** Where the runner is after walking [fixes] in order, from a standing start. */
    private fun CourseLine.after(vararg fixes: MapFix): CourseProgress {
        var progress: CourseProgress? = null
        for (fix in fixes) progress = progressAt(fix.latitude, fix.longitude, progress)
        return progress!!
    }

    @Test
    fun `a course is as long as the ground it covers`() {
        val points = northwards(count = 11, spacingMeters = 100.0)

        val course = CourseLine.of(points)!!

        assertEquals(routeDistanceMeters(points), course.lengthMeters, 0.001)
    }

    @Test
    fun `at the start of a course the whole of it is left`() {
        val points = northwards(count = 11, spacingMeters = 100.0)
        val course = CourseLine.of(points)!!

        val progress = course.after(fix(0.0))

        assertEquals(0.0, progress.alongMeters, 1.0)
        assertEquals(course.lengthMeters, progress.remainingMeters, 1.0)
        assertEquals(0.0, progress.metersFromCourse, 0.5)
    }

    @Test
    fun `part way up a course, what is left is the rest of it`() {
        val course = CourseLine.of(northwards(count = 11, spacingMeters = 100.0))!!

        val progress = course.after(fix(0.0), fix(150.0), fix(300.0))

        assertEquals(300.0, progress.alongMeters, 1.0)
        assertEquals(course.lengthMeters - 300.0, progress.remainingMeters, 1.0)
    }

    @Test
    fun `at the far end of a course there is nothing left`() {
        val course = CourseLine.of(northwards(count = 11, spacingMeters = 100.0))!!

        val progress = course.after(fix(0.0), fix(500.0), fix(1000.0))

        assertEquals(0.0, progress.remainingMeters, 1.0)
    }

    @Test
    fun `running past the end of a course leaves nothing left, never less than nothing`() {
        val course = CourseLine.of(northwards(count = 11, spacingMeters = 100.0))!!

        val progress = course.after(fix(0.0), fix(500.0), fix(1000.0), fix(1200.0))

        assertEquals(0.0, progress.remainingMeters, 0.0)
        assertEquals(200.0, progress.metersFromCourse, 2.0)
    }

    @Test
    fun `a runner off to one side is still measured along the course`() {
        val course = CourseLine.of(northwards(count = 11, spacingMeters = 100.0))!!

        val progress = course.after(fix(0.0), fix(300.0, eastMeters = 40.0))

        assertEquals(300.0, progress.alongMeters, 2.0)
        assertEquals(40.0, progress.metersFromCourse, 2.0)
    }

    /**
     * The out-and-back case the ticket names. Five hundred metres north and back again puts two
     * pieces of line under every spot on it, and which one the runner is on is the difference
     * between "half way" and "nearly home".
     */
    private fun outAndBack(): List<RoutePoint> =
        northwards(count = 11, spacingMeters = 50.0) + northwards(count = 11, spacingMeters = 50.0).reversed().drop(1)

    @Test
    fun `an out-and-back starts with the whole of it to go, not none of it`() {
        val points = outAndBack()
        val course = CourseLine.of(points)!!

        val progress = course.after(fix(0.0))

        assertEquals(routeDistanceMeters(points), progress.remainingMeters, 1.0)
    }

    @Test
    fun `an out-and-back at its turn is half run`() {
        val course = CourseLine.of(outAndBack())!!

        val progress = course.after(fix(0.0), fix(250.0), fix(500.0))

        assertEquals(500.0, progress.alongMeters, 2.0)
        assertEquals(500.0, progress.remainingMeters, 2.0)
    }

    @Test
    fun `on the way back an out-and-back keeps counting down`() {
        val course = CourseLine.of(outAndBack())!!

        val progress = course.after(fix(0.0), fix(250.0), fix(500.0), fix(300.0), fix(100.0))

        // 500 m out plus 400 m back: 900 m run, 100 m to go — and emphatically not the 900 m left
        // that reading the runner onto the outward line at 100 m would have given.
        assertEquals(900.0, progress.alongMeters, 3.0)
        assertEquals(100.0, progress.remainingMeters, 3.0)
    }

    @Test
    fun `a course run the other way round counts down from the other end`() {
        val points = northwards(count = 11, spacingMeters = 100.0)
        val course = CourseLine.of(points.reversed())!!

        val progress = course.after(fix(1000.0), fix(700.0))

        assertEquals(300.0, progress.alongMeters, 1.0)
        assertEquals(700.0, progress.remainingMeters, 1.0)
    }

    @Test
    fun `a fix further ahead than the window is read as far along as the window reaches`() {
        val course = CourseLine.of(northwards(count = 31, spacingMeters = 100.0))!!

        val jumped = course.after(fix(0.0), fix(2000.0))
        // The place is not lost: the next fix is read from around the new one, so a Run that lost
        // its signal for a mile is back where it really is within seconds.
        val caughtUp = course.progressAt(fix(2000.0).latitude, fix(2000.0).longitude, jumped)

        assertEquals(500.0, jumped.alongMeters, 2.0)
        assertEquals(1000.0, caughtUp.alongMeters, 2.0)
    }

    @Test
    fun `a course of no ground is no course at all`() {
        assertNull(CourseLine.of(emptyList()))
        assertNull(CourseLine.of(listOf(at(0.0))))
        assertNull(CourseLine.of(listOf(at(0.0), at(0.0), at(0.0))))
    }

    @Test
    fun `points repeated on the same spot are passed over`() {
        val points = listOf(at(0.0), at(0.0), at(500.0), at(500.0), at(1000.0))
        val course = CourseLine.of(points)!!

        assertEquals(routeDistanceMeters(points), course.lengthMeters, 0.001)
        assertEquals(500.0, course.after(fix(0.0), fix(500.0)).remainingMeters, 1.0)
    }

    @Test
    fun `with no course there is nothing to say about what is left`() {
        assertNull(courseRemainingMeters(null, listOf(fix(0.0))))
    }

    @Test
    fun `a course nobody has been fixed on yet has all of itself left`() {
        val course = CourseLine.of(northwards(count = 11, spacingMeters = 100.0))!!

        assertEquals(course.lengthMeters, courseRemainingMeters(course, emptyList())!!, 0.0)
    }

    @Test
    fun `what is left is read from every fix the Run has recorded`() {
        val course = CourseLine.of(outAndBack())!!

        val remaining = courseRemainingMeters(
            course,
            listOf(fix(0.0), fix(250.0), fix(500.0), fix(250.0)),
        )!!

        assertTrue("expected under half left, got $remaining", remaining < 300.0)
    }

    /**
     * The walk from the runner's door to the start of the course, on an out-and-back, where every
     * one of those fixes is as near the way home as the way out.
     */
    @Test
    fun `a Run that starts away from the course still has all of it to go when it reaches it`() {
        val points = outAndBack()
        val course = CourseLine.of(points)!!

        // Two hundred metres off to the side of the near end, then onto the start of the course.
        val progress = course.after(fix(0.0, eastMeters = 200.0), fix(0.0, eastMeters = 90.0), fix(0.0))

        assertEquals(routeDistanceMeters(points), progress.remainingMeters, 1.0)
    }

    @Test
    fun `a runner who joins a course half way along is read from where they joined it`() {
        val course = CourseLine.of(northwards(count = 11, spacingMeters = 100.0))!!

        val progress = course.after(fix(500.0, eastMeters = 300.0), fix(500.0), fix(600.0))

        assertEquals(600.0, progress.alongMeters, 2.0)
        assertEquals(400.0, progress.remainingMeters, 2.0)
    }

    @Test
    fun `a course is not reached from three hundred metres away`() {
        val course = CourseLine.of(northwards(count = 11, spacingMeters = 100.0))!!

        val progress = course.after(fix(400.0, eastMeters = 300.0))

        assertEquals(false, progress.hasReachedTheCourse)
        assertEquals(300.0, progress.metersFromCourse, 3.0)
    }

    /**
     * Once reached, the course is read from around the last fix for the rest of the Run — a runner
     * who has left it is somewhere they ran to, not somewhere to be found again from scratch.
     */
    @Test
    fun `a runner who leaves the course keeps the place they had reached`() {
        val course = CourseLine.of(outAndBack())!!

        val progress = course.after(fix(0.0), fix(250.0), fix(500.0), fix(400.0), fix(350.0, eastMeters = 120.0))

        assertEquals(true, progress.hasReachedTheCourse)
        assertEquals(650.0, progress.alongMeters, 5.0)
        assertEquals(120.0, progress.metersFromCourse, 5.0)
    }
}
