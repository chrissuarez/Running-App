package com.example.runningapp.routes

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a course bends, and which way (#456).
 *
 * Every course here is written in metres north and east of one spot in London, so a test reads as
 * the shape it is about: "north four hundred, then west" is a left turn four hundred metres in.
 * Nothing here speaks: what is being pinned is which bends of a line are turns, where each of them
 * sits along the course, and which of them are one instruction rather than four.
 */
class CourseTurnsTest {

    private val originLatitude = 51.5
    private val originLongitude = -0.1

    /** Metres in a degree at the origin, for writing a course in metres. */
    private val metersPerDegreeLatitude = 111_132.0
    private val metersPerDegreeLongitude = 69_300.0

    private fun at(northMeters: Double, eastMeters: Double = 0.0) = RoutePoint(
        latitude = originLatitude + northMeters / metersPerDegreeLatitude,
        longitude = originLongitude + eastMeters / metersPerDegreeLongitude,
        elevationMeters = null,
    )

    /**
     * The turns of a course, written as "left at 400 m" so a failure reads as a sentence.
     *
     * The metres are the course's own, measured along it on the ground, so they run a metre or so
     * past the round numbers a test writes its shape in: the shape is laid out with a flat metre in
     * a degree and the course is measured on the round earth, which is the measurement
     * the app really uses.
     */
    private fun turnsOf(course: List<RoutePoint>): List<String> =
        courseTurnsOf(course).map { "${it.direction.name.lowercase()} at ${Math.round(it.alongMeters)} m" }

    /** A straight stretch of [meters], a place every [every] metres, starting where the last ended. */
    private fun List<RoutePoint>.thenGo(
        meters: Double,
        northPerMeter: Double,
        eastPerMeter: Double,
        every: Double = 25.0,
    ): List<RoutePoint> {
        val from = last()
        val north = (from.latitude - originLatitude) * metersPerDegreeLatitude
        val east = (from.longitude - originLongitude) * metersPerDegreeLongitude
        val steps = Math.ceil(meters / every).toInt().coerceAtLeast(1)
        return this + (1..steps).map { step ->
            val covered = meters * step / steps
            at(north + covered * northPerMeter, east + covered * eastPerMeter)
        }
    }

    private fun List<RoutePoint>.north(meters: Double, every: Double = 25.0) =
        thenGo(meters, northPerMeter = 1.0, eastPerMeter = 0.0, every = every)

    private fun List<RoutePoint>.south(meters: Double, every: Double = 25.0) =
        thenGo(meters, northPerMeter = -1.0, eastPerMeter = 0.0, every = every)

    private fun List<RoutePoint>.west(meters: Double, every: Double = 25.0) =
        thenGo(meters, northPerMeter = 0.0, eastPerMeter = -1.0, every = every)

    private fun List<RoutePoint>.east(meters: Double, every: Double = 25.0) =
        thenGo(meters, northPerMeter = 0.0, eastPerMeter = 1.0, every = every)

    private val start = listOf(at(0.0))

    @Test
    fun `a course too short to bend has no turns`() {
        assertEquals(emptyList<String>(), turnsOf(emptyList()))
        assertEquals(emptyList<String>(), turnsOf(listOf(at(0.0))))
        assertEquals(emptyList<String>(), turnsOf(listOf(at(0.0), at(100.0))))
    }

    @Test
    fun `a straight road bends nowhere`() {
        assertEquals(emptyList<String>(), turnsOf(start.north(1000.0)))
    }

    @Test
    fun `a road turning west is one left turn, where it turns`() {
        assertEquals(listOf("left at 400 m"), turnsOf(start.north(400.0).west(600.0)))
    }

    @Test
    fun `the same road run the other way round turns right`() {
        assertEquals(listOf("right at 601 m"), turnsOf(start.north(400.0).west(600.0).reversed()))
    }

    /**
     * The reason the turns are read off the course's shape and not off its places (#456).
     *
     * A course saved off a Run holds a fix a second or two, and a runner going straight down a road
     * still wanders a metre either side of it. Read place by place, every one of those wanders is a
     * bend of sixty degrees and the road is a hundred turns; read off the shape, it is a straight
     * road, which is what it is.
     */
    @Test
    fun `a runner's wander down a straight road is not a hundred turns`() {
        val wandering = (0..100).map { at(it * 5.0, eastMeters = if (it % 2 == 0) 1.5 else -1.5) }
        assertEquals(emptyList<String>(), turnsOf(wandering))
    }

    /**
     * A road that curves round is not a turn, however far round it eventually goes: the runner
     * follows it without deciding anything, and a cue about it is a cue about nothing.
     */
    @Test
    fun `a road curving a quarter of the way round is not a turn`() {
        val curve = (0..90).map { degree ->
            val radians = Math.toRadians(degree.toDouble())
            at(northMeters = 400.0 * Math.sin(radians), eastMeters = 400.0 - 400.0 * Math.cos(radians))
        }
        assertEquals(emptyList<String>(), turnsOf(curve))
    }

    /**
     * The same corner as above, taken at the width a road corner actually has. It is one decision
     * and it is told once — the whole point of reading the shape at ten metres.
     */
    @Test
    fun `a corner with a kerb radius is still one turn`() {
        val corner = (0..8).map { step ->
            val radians = Math.toRadians(step * 90.0 / 8)
            at(northMeters = 400.0 - 10.0 + 10.0 * Math.sin(radians), eastMeters = -10.0 + 10.0 * Math.cos(radians))
        }
        val course = start.north(390.0) + corner + at(400.0, -600.0)
        assertEquals(listOf("left at 400 m"), turnsOf(course))
    }

    /**
     * A dog-leg round a building: two hard bends thirty metres apart. Told one at a time the runner
     * hears the second while they are still turning into the first, so they are one instruction —
     * placed at the first of them, because that is the ground they reach first.
     */
    @Test
    fun `a dog-leg round a building is one instruction`() {
        val course = start.north(300.0).west(30.0, every = 30.0).north(300.0)
        assertEquals(listOf("left at 300 m"), turnsOf(course))
    }

    /** Two corners far enough apart to be two decisions are two cues. */
    @Test
    fun `two corners a street apart are two turns`() {
        val course = start.north(300.0).west(200.0).north(300.0)
        assertEquals(listOf("left at 300 m", "right at 501 m"), turnsOf(course))
    }

    /**
     * An out-and-back turns round at its far end, and turning round is the sharpest turn there is.
     * The way back here is ten metres to the side, as a real one is — two right angles ten metres
     * apart, which is one decision and told once.
     */
    @Test
    fun `the far end of an out-and-back is one turn`() {
        val course = start.north(500.0).east(10.0, every = 10.0).south(500.0)
        assertEquals(listOf("right at 501 m"), turnsOf(course))
    }
}
