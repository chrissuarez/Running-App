package com.example.runningapp.ui

import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteSource
import com.example.runningapp.routes.GpxRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class RouteModelsTest {

    private fun route(distanceMeters: Double, elevationGainMeters: Double?) = Route(
        id = 1,
        name = "Regent's Park loop",
        distanceMeters = distanceMeters,
        elevationGainMeters = elevationGainMeters,
        polyline = "51.5000000,-0.1000000 51.5010000,-0.1010000",
        createdAtMillis = 1_700_000_000_000L,
        source = RouteSource.IMPORTED,
    )

    @Test
    fun saysHowFarAndHowMuchClimbing() {
        assertEquals("4.20 km · 38 m up", routeRowSubtitle(route(4_200.0, 37.6)))
    }

    /**
     * Not "0 m up". A flat route and a file that never said are different things, and a nought would
     * tell a runner the hill they are about to run up is not there.
     */
    @Test
    fun saysWhenTheFileCarriedNoHeights() {
        assertEquals("4.20 km · No elevation in file", routeRowSubtitle(route(4_200.0, null)))
        assertEquals("4.20 km · 0 m up", routeRowSubtitle(route(4_200.0, 0.0)))
    }

    /** A device set to German must not write "4,20 km" into a screen the rest of which is in km. */
    @Test
    fun writesTheDistanceOneWayWhateverTheDeviceLocale() {
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
    fun everyRefusalHasWordsOfItsOwn() {
        val messages = GpxRefusal.entries.map { gpxRefusalMessage(it) }

        assertEquals(GpxRefusal.entries.size, messages.toSet().size)
        messages.forEach { message ->
            assertTrue(message, message.endsWith("."))
            assertTrue(message, message.length > 40)
        }
    }

    @Test
    fun namesTheRouteItJustSaved() {
        assertEquals("Saved “Park loop” to your routes.", routeImportedMessage("Park loop"))
    }
}
