package com.example.runningapp.ui

import com.example.runningapp.data.RouteHeader
import com.example.runningapp.data.RouteSource
import com.example.runningapp.routes.GpxRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class RouteModelsTest {

    private fun route(distanceMeters: Double, elevationGainMeters: Double?) = RouteHeader(
        id = 1,
        name = "Regent's Park loop",
        distanceMeters = distanceMeters,
        elevationGainMeters = elevationGainMeters,
        createdAtMillis = 1_700_000_000_000L,
        source = RouteSource.IMPORTED,
    )

    @Test
    fun `says how far and how much climbing`() {
        assertEquals("4.20 km · 38 m up", routeRowSubtitle(route(4_200.0, 37.6)))
    }

    /**
     * Not "0 m up". A flat route and a file that never said are different things, and a nought would
     * tell a runner the hill they are about to run up is not there.
     */
    @Test
    fun `says when the file carried no heights`() {
        assertEquals("4.20 km · No elevation in file", routeRowSubtitle(route(4_200.0, null)))
        assertEquals("4.20 km · 0 m up", routeRowSubtitle(route(4_200.0, 0.0)))
    }

    /** A device set to German must not write "4,20 km" into a screen the rest of which is in km. */
    @Test
    fun `writes the distance one way whatever the device locale`() {
        val was = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("4.20 km", routeDistanceLabel(4_200.0))
        } finally {
            Locale.setDefault(was)
        }
    }

    /** Every refusal has to say what the runner can do next, and that nothing was kept. */
    @Test
    fun `every refusal has words of its own`() {
        val messages = GpxRefusal.entries.map { gpxRefusalMessage(it) }

        assertEquals(GpxRefusal.entries.size, messages.toSet().size)
        messages.forEach { message ->
            assertTrue(message, message.endsWith("."))
            assertTrue(message, message.length > 40)
        }
    }

    @Test
    fun `names the route it just saved`() {
        assertEquals("Saved “Park loop” to your routes.", routeImportedMessage("Park loop"))
    }

    /**
     * Names the other course and says what to do about it: "covers the same ground" is a fact, and
     * the runner is being asked to settle a pair the app will not settle for them (#402).
     */
    @Test
    fun `warns that the library now holds one piece of ground twice`() {
        assertEquals(
            "Saved “Run 27 Aug 2026, 12:35” to your routes. It covers the same ground as " +
                "“Cuckoo Trail”, which you already keep. If that was not meant to be a second " +
                "copy, delete whichever one you do not want.",
            routeImportedMessage("Run 27 Aug 2026, 12:35") + routeSameGroundNote("Cuckoo Trail"),
        )
    }

    /** No such course reads as nothing at all, so neither door has to decide that for itself. */
    @Test
    fun `a course with no twin says only that it was saved`() {
        assertEquals(
            "Saved “Park loop” to your routes.",
            routeImportedMessage("Park loop") + routeSameGroundNote(null),
        )
    }

    /** Says outright that nothing was added, so the runner is not left looking for a new row. */
    @Test
    fun `names the route it already had`() {
        assertEquals(
            "That route is already in your routes, as “Park loop”. Nothing was added.",
            routeAlreadySavedMessage("Park loop"),
        )
    }

    /** Says which numbers moved: the row is the only other place the change shows. */
    @Test
    fun `says a kept route now carries this file's numbers`() {
        assertEquals(
            "“Park loop” is already in your routes. Its distance and climb now come from this file.",
            routeRemeasuredMessage("Park loop"),
        )
    }

    /** The start line's two questions in one line: the right course, pointing the right way. */
    @Test
    fun `the pre-run card names the course and which way round`() {
        assertEquals(
            "Regent's Park loop · 5.20 km · as drawn",
            runRouteChoiceSummary(route(5200.0, null), reversed = false),
        )
        assertEquals(
            "Regent's Park loop · 5.20 km · backwards",
            runRouteChoiceSummary(route(5200.0, null), reversed = true),
        )
    }

    /** Following nothing is a choice the card states, not a blank where a name would be. */
    @Test
    fun `no course chosen says so, in either direction`() {
        assertEquals("No route — just go for a run", runRouteChoiceSummary(null, reversed = false))
        assertEquals("No route — just go for a run", runRouteChoiceSummary(null, reversed = true))
    }

    /** Both doors into the library, because the second one surprises people (#55). */
    @Test
    fun `an empty library says where routes come from`() {
        assertEquals(
            "No routes yet. Import a GPX under Open Routes, or save a run you've already been " +
                "for as one.",
            runRouteLibraryEmptyLine(),
        )
    }
}
