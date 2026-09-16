package com.example.runningapp.ui

import androidx.compose.runtime.saveable.SaverScope
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.run.RunRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pending route choice written down and read back (#56).
 *
 * Worth a test of its own because what it guards is invisible until it fails: the choice is held
 * above the screen so a walk to the Routes library and back cannot drop it, and the same choice has
 * to survive the process being rebuilt under the runner — a text-size change, a rotation, Android
 * reclaiming the app while they read something else. A saver that quietly loses the direction leaves
 * a runner setting off round a course the wrong way with nothing on screen having changed.
 */
class RunRouteSaverTest {

    /** Nothing here is a Bundle, so everything is savable — the saver's job is the shape, not that. */
    private val anythingCanBeSaved = SaverScope { true }

    private fun roundTrip(route: RunRoute?): RunRoute? {
        val saved = with(RunRouteSaver) { anythingCanBeSaved.save(route) }
        return saved?.let { RunRouteSaver.restore(it) }
    }

    @Test
    fun `a course kept as drawn comes back as drawn`() {
        assertEquals(RunRoute(routeId = 12L, reversed = false), roundTrip(RunRoute(12L, false)))
    }

    /** The half that would be silently wrong rather than visibly missing. */
    @Test
    fun `a course kept backwards comes back backwards`() {
        assertEquals(RunRoute(routeId = 12L, reversed = true), roundTrip(RunRoute(12L, true)))
    }

    @Test
    fun `no course picked comes back as no course`() {
        assertNull(roundTrip(null))
    }

    // ---- what a pick made on a route's own page leaves behind (#496) ----

    @Test
    fun `picking a different course starts it the usual way round`() {
        assertEquals(RunRoute(7L, false), runRouteAfterPick(RunRoute(3L, true), 7L))
    }

    @Test
    fun `picking the course already chosen keeps the direction set on it`() {
        assertEquals(RunRoute(3L, true), runRouteAfterPick(RunRoute(3L, true), 3L))
    }

    @Test
    fun `picking no route clears the choice`() {
        assertNull(runRouteAfterPick(RunRoute(3L, true), null))
    }

    private fun header(id: Long) = RouteHeader(id, "Course $id", 5000.0, null, 0L, "gpx")

    @Test
    fun `a pick still in the library is not no route`() {
        assertFalse(routeChoiceIsNoRoute(RunRoute(3L, false), listOf(header(3L))))
    }

    @Test
    fun `a pick deleted from the library leaves no route as the choice`() {
        assertTrue(routeChoiceIsNoRoute(RunRoute(3L, false), listOf(header(4L))))
    }

    @Test
    fun `nothing picked is no route`() {
        assertTrue(routeChoiceIsNoRoute(null, listOf(header(3L))))
    }
}
