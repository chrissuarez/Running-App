package com.example.runningapp.ui

import com.example.runningapp.data.RouteHeader
import com.example.runningapp.data.RouteSource
import com.example.runningapp.routes.GpxRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            "Regent's Park loop · 5.20 km · usual way round",
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

    // --- Where a route the runner has never run comes from (#448) ---

    /** The two sites, in the order the screen offers them: the one to start with first. */
    @Test
    fun `the screen points at two free route builders`() {
        assertEquals(
            listOf("gpx.studio", "On The Go Map"),
            routeBuilderPointers.map { it.name },
        )
        assertEquals(
            listOf("https://gpx.studio", "https://onthegomap.com"),
            routeBuilderPointers.map { it.url },
        )
    }

    /**
     * The rule this list exists under: a pointer that sends the runner somewhere they must pay is
     * worse than no pointer.
     *
     * Strava, Komoot and Footpath all charge for the export, and Garmin Connect's free course file
     * carries no heights at all — so a course built there can never show a climb. None of the four
     * may be named as a place to draw a route.
     */
    @Test
    fun `no route builder that charges or drops heights is named`() {
        val everythingSaid = (
            listOf(ROUTE_BUILDERS_HEADING, ROUTE_BUILDERS_BLURB, ROUTE_BUILDERS_THEN) +
                routeBuilderPointers.map { "${it.name} ${it.url} ${it.note}" }
            ).joinToString(" ").lowercase()

        listOf("strava", "komoot", "footpath", "garmin").forEach { paidOrHeightless ->
            assertFalse(
                "the Routes screen names $paidOrHeightless as somewhere to draw a route",
                everythingSaid.contains(paidOrHeightless),
            )
        }
    }

    /**
     * The empty library and the card under it are read together, so they may not disagree about
     * where a runner is sent. The card names two free sites and bars four; this names none.
     */
    @Test
    fun `the empty Routes screen names both doors and no site`() {
        assertEquals(
            "No routes yet.\n\nImport a GPX file, or save a run you've already been for as one. " +
                "Both land here.",
            ROUTES_EMPTY_LINE,
        )
        listOf("strava", "komoot", "footpath", "garmin").forEach {
            assertFalse(ROUTES_EMPTY_LINE.lowercase().contains(it))
        }
    }

    /**
     * The two doors that exist, and no third. The app registers `ACTION_VIEW` only, so it has no
     * entry in the phone's share sheet — describing one would be a door that is not there (#384).
     */
    @Test
    fun `getting the drawn file in names only the doors the app has`() {
        assertEquals(
            "Then Import GPX here, or find the file and choose Open with → Running App.",
            ROUTE_BUILDERS_THEN,
        )
        assertFalse(ROUTE_BUILDERS_THEN.lowercase().contains("share"))
    }

    /**
     * On The Go Map's note is a warning and not a description: its file carries no heights unless
     * the elevation profile has been switched on and allowed to finish, and a route with none reads
     * `No elevation in file` for ever, because a Route's climb is never worked out again.
     */
    @Test
    fun `the site that can lose the heights says so`() {
        val onTheGoMap = routeBuilderPointers.single { it.name == "On The Go Map" }

        assertTrue(onTheGoMap.note.contains("elevation profile on"))
        assertTrue(onTheGoMap.note.contains("no heights"))
    }
}
