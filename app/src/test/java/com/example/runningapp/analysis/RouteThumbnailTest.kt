package com.example.runningapp.analysis

import com.example.runningapp.data.MeasuredTrack
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
import com.example.runningapp.data.measureTrack
import com.example.runningapp.recording.geodesicDistanceMeters
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The little route drawn beside a Run in the History list (#51).
 *
 * Every test lays a route out in metres — "300 m east", "a square", "paused at a crossing" — and
 * asks what shape the list is allowed to draw for it. Metres rather than degrees because the shape
 * is the whole subject here, and a route written in degrees is stretched by its own latitude before
 * anything under test has touched it.
 */
class RouteThumbnailTest {

    @Test
    fun `a route across the box keeps both its ends`() {
        val thumbnail = requireNotNull(thumbnailOf(route { east(300.0) }))

        val line = thumbnail.strokes.single()
        assertEquals(0f, line.first().x, TOLERANCE)
        assertEquals(1f, line.last().x, TOLERANCE)
        // Nothing north or south of anything, so the line sits across the middle.
        assertTrue(line.all { abs(it.y - 0.5f) < TOLERANCE })
    }

    @Test
    fun `north is up`() {
        val thumbnail = requireNotNull(thumbnailOf(route { north(300.0) }))

        val line = thumbnail.strokes.single()
        assertTrue("the finish should sit above the start", line.last().y < line.first().y)
    }

    @Test
    fun `a square route is drawn square`() {
        val thumbnail = requireNotNull(thumbnailOf(route { east(200.0); north(200.0); east(-200.0); north(-200.0) }))

        val points = thumbnail.strokes.flatten()
        val width = points.maxOf { it.x } - points.minOf { it.x }
        val height = points.maxOf { it.y } - points.minOf { it.y }
        assertEquals("a square run must not come out an oblong", width, height, SQUARE_TOLERANCE)
    }

    /**
     * The one thing a thumbnail can get wrong before it has drawn anything: a degree of longitude is
     * shorter than a degree of latitude everywhere but the equator, and reading the two as the same
     * unit squashes every route east-west. Norway is where that is impossible to miss — a degree of
     * longitude there is half a degree of latitude.
     */
    @Test
    fun `a square route is drawn square in Norway too`() {
        val thumbnail = requireNotNull(
            thumbnailOf(route(latitude = 60.0) { east(200.0); north(200.0); east(-200.0); north(-200.0) })
        )

        val points = thumbnail.strokes.flatten()
        val width = points.maxOf { it.x } - points.minOf { it.x }
        val height = points.maxOf { it.y } - points.minOf { it.y }
        assertEquals(width, height, SQUARE_TOLERANCE)
    }

    @Test
    fun `an out-and-back is taller than it is wide, and is drawn that way`() {
        val thumbnail = requireNotNull(thumbnailOf(route { north(400.0); east(50.0); north(-400.0) }))

        val points = thumbnail.strokes.flatten()
        val width = points.maxOf { it.x } - points.minOf { it.x }
        val height = points.maxOf { it.y } - points.minOf { it.y }
        // The long side fills the box and the short one keeps its proportion, centred in what is
        // left — a shape squeezed to fill the square would be a different run.
        assertEquals(1f, height, TOLERANCE)
        assertEquals(50.0f / 400.0f, width, 0.02f)
        assertEquals("centred", 0.5f, (points.maxOf { it.x } + points.minOf { it.x }) / 2f, TOLERANCE)
    }

    @Test
    fun `a pause is a break in the line, not a line across it`() {
        val thumbnail = requireNotNull(thumbnailOf(route { east(200.0); pauseAndMoveOn(200.0); east(200.0) }))

        assertEquals(2, thumbnail.strokes.size)
    }

    @Test
    fun `a lost signal is a break too`() {
        val thumbnail = requireNotNull(thumbnailOf(route { east(200.0); lostSignal(seconds = 120, east = 200.0); east(200.0) }))

        assertEquals(2, thumbnail.strokes.size)
    }

    @Test
    fun `a treadmill run has no route to draw`() {
        assertNull(thumbnailOf(MeasuredTrack(emptyList(), emptyList())))
    }

    @Test
    fun `a run of a single fix has no route to draw`() {
        assertNull(thumbnailOf(route { }))
    }

    @Test
    fun `a run that never left the spot is not drawn`() {
        assertNull(thumbnailOf(route { standingStill(seconds = 60) }))
    }

    /**
     * A phone left on a desk does not record the same point sixty times: accepted fixes wander tens
     * of metres around a runner who never moved. Scaled to fill the square, that wander is the most
     * confident-looking drawing in the list and means nothing at all.
     */
    @Test
    fun `a run that only wandered where it stood is not drawn`() {
        assertNull(thumbnailOf(route { jitteredInPlace(seconds = 60, meters = 20.0) }))
    }

    /**
     * Two out and one back leaves the turnaround a kilometre past the finish and dead on the line
     * between start and finish. Judged against that line it is worth nothing, and the whole run
     * flattens into the one-kilometre straight it plainly was not.
     */
    @Test
    fun `an out-and-back that stops short of home keeps its turnaround`() {
        val thumbnail = requireNotNull(thumbnailOf(route { east(2_000.0); east(-1_000.0) }))

        val line = thumbnail.strokes.single()
        assertEquals("the turnaround is the far end of the box", 1f, line.maxOf { it.x }, 0.01f)
        assertTrue("the run has to turn, not run straight: was ${line.size} points", line.size >= 3)
        assertEquals("and it finishes half way back", 0.5f, line.last().x, 0.01f)
    }

    @Test
    fun `detail too small for a thumbnail to show is left out of it`() {
        val thumbnail = requireNotNull(thumbnailOf(route { east(3_000.0) }))

        val line = thumbnail.strokes.single()
        assertTrue("3000 fixes is a route to draw, not a list to walk: was ${line.size}", line.size < 100)
        assertEquals(0f, line.first().x, TOLERANCE)
        assertEquals(1f, line.last().x, TOLERANCE)
    }

    @Test
    fun `a corner survives being drawn small`() {
        val thumbnail = requireNotNull(thumbnailOf(route { east(1_000.0); north(1_000.0) }))

        val points = thumbnail.strokes.single()
        // The turn is the shape of this run. Dropping fixes must not round it off into a diagonal.
        val corner = points.minByOrNull { abs(it.x - 1f) + abs(it.y - 1f) }!!
        assertEquals(1f, corner.x, 0.02f)
        assertEquals(1f, corner.y, 0.02f)
    }

    private fun thumbnailOf(measured: MeasuredTrack) = routeThumbnailOf(measured)

    private fun thumbnailOf(points: List<TrackPoint>) = routeThumbnailOf(measureTrack(points))

    private companion object {
        const val TOLERANCE = 0.001f

        /**
         * A hundredth of the square, for the two tests that ask whether a square run comes out
         * square.
         *
         * The thumbnail shrinks a degree of longitude by the cosine of the latitude, which is the
         * Earth read as a ball; the runs here are laid out in metres by the app's own distance
         * function, which reads it as the squashed thing it is. The two disagree by about a
         * quarter of a percent at these latitudes — a quarter of a pixel across a thumbnail, and
         * an argument about the shape of the planet rather than about the shape of the run.
         */
        const val SQUARE_TOLERANCE = 0.01f
    }
}

/** A route laid out in metres east and north of where it started, one fix a second. */
internal fun route(latitude: Double = 50.79, build: RouteScript.() -> Unit): List<TrackPoint> =
    RouteScript(latitude).apply(build).points

internal class RouteScript(private val startLatitude: Double) {
    val points = mutableListOf<TrackPoint>()
    private var latitude = startLatitude
    private var longitude = 0.22
    private var timestamp = 1_700_000_000_000L

    init {
        add()
    }

    /** Runs [meters] east — or west, given a negative — at a metre a second. */
    fun east(meters: Double) = leg(meters) { moveEast(it) }

    /** Runs [meters] north, or south. */
    fun north(meters: Double) = leg(meters) { moveNorth(it) }

    /** [seconds] of fixes arriving from the same spot — a run that went nowhere at all. */
    fun standingStill(seconds: Int) {
        repeat(seconds) {
            timestamp += 1_000
            add()
        }
    }

    /**
     * [seconds] of fixes from a runner who never moved, wandering up to [meters] from the spot in
     * each direction — which is what a stationary phone actually records.
     *
     * Wandered by trigonometry rather than at random, so the shape under test is the same shape
     * every time it is run.
     */
    fun jitteredInPlace(seconds: Int, meters: Double) {
        val stood = latitude to longitude
        repeat(seconds) { i ->
            latitude = stood.first
            longitude = stood.second
            moveNorth(meters * cos(i.toDouble()))
            moveEast(meters * sin(i * 1.7))
            timestamp += 1_000
            add()
        }
        latitude = stood.first
        longitude = stood.second
    }

    /** The runner stops, covers [meters] east unrecorded, and starts again. */
    fun pauseAndMoveOn(meters: Double) {
        moveEast(meters)
        timestamp += 60_000
        add(startsAfterPause = true)
    }

    /** The signal is lost for [seconds], over [east] metres of ground nothing recorded. */
    fun lostSignal(seconds: Int, east: Double) {
        moveEast(east)
        timestamp += seconds * 1_000L
        add()
    }

    private fun leg(meters: Double, step: (Double) -> Unit) {
        val seconds = abs(meters).toInt()
        val each = if (seconds == 0) 0.0 else meters / seconds
        repeat(seconds) {
            step(each)
            timestamp += 1_000
            add()
        }
    }

    private fun moveNorth(meters: Double) {
        latitude += meters / metersPerDegreeLatitude()
    }

    private fun moveEast(meters: Double) {
        longitude += meters / metersPerDegreeLongitude()
    }

    private fun metersPerDegreeLatitude() =
        geodesicDistanceMeters(latitude, longitude, latitude + 0.001, longitude) * 1_000.0

    private fun metersPerDegreeLongitude() =
        geodesicDistanceMeters(latitude, longitude, latitude, longitude + 0.001) * 1_000.0

    private fun add(startsAfterPause: Boolean = false) {
        points += TrackPoint(
            sessionId = 1,
            latitude = latitude,
            longitude = longitude,
            horizontalAccuracyMeters = 5f,
            timestampMillis = timestamp,
            source = TrackPointSource.GPS,
            startsAfterPause = startsAfterPause,
        )
    }
}
