package com.example.runningapp.routes

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutePolylineTest {

    @Test
    fun `a course survives being stored and read back`() {
        val points = listOf(
            RoutePoint(51.5074000, -0.1278000, 12.0),
            RoutePoint(-33.8688000, 151.2093000, null),
            RoutePoint(0.0, 0.0, null),
        )

        assertEquals(
            points.map { it.copy(elevationMeters = null) },
            RoutePolyline.decode(RoutePolyline.encode(points)),
        )
    }

    /** Written the way the app writes every other coordinate — seven places, and never a comma. */
    @Test
    fun `is written in one form whatever the device locale`() {
        val was = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals(
                "51.5074000,-0.1278000 51.5075000,-0.1279000",
                RoutePolyline.encode(
                    listOf(RoutePoint(51.5074, -0.1278, null), RoutePoint(51.5075, -0.1279, null))
                ),
            )
        } finally {
            java.util.Locale.setDefault(was)
        }
    }

    @Test
    fun `an empty course is an empty line`() {
        assertEquals("", RoutePolyline.encode(emptyList()))
        assertEquals(emptyList<RoutePoint>(), RoutePolyline.decode(""))
        assertEquals(emptyList<RoutePoint>(), RoutePolyline.decode("   "))
    }

    /** A row damaged in the database must draw nothing rather than take the screen down with it. */
    @Test
    fun `reads nothing out of gibberish`() {
        assertEquals(emptyList<RoutePoint>(), RoutePolyline.decode("north,west"))
        assertEquals(emptyList<RoutePoint>(), RoutePolyline.decode("51.5"))
        assertEquals(
            listOf(RoutePoint(51.5, -0.1, null)),
            RoutePolyline.decode("51.5,-0.1 nonsense 91.0,0.0"),
        )
    }
}
