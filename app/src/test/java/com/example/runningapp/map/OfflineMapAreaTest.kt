package com.example.runningapp.map

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.recording.geodesicDistanceMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ground the offline map covers is a ring drawn round the runner, so what is worth being sure of
 * is that the ring is where it says it is: every corner the stated distance out, closed, and whole
 * even where longitude runs out.
 */
class OfflineMapAreaTest {

    private val home = MapFix(latitude = 50.83, longitude = -0.14)

    @Test
    fun `every corner of the ring is the stated distance from the centre`() {
        val ring = offlineAreaRing(home)

        ring.forEach { corner ->
            val meters = geodesicDistanceMeters(home.latitude, home.longitude, corner.latitude, corner.longitude)
            // Within half a percent: the ring is laid out on a sphere and measured on the ellipsoid,
            // and a few tens of metres at 15 km moves no tile.
            assertEquals(OFFLINE_AREA_RADIUS_METERS, meters, OFFLINE_AREA_RADIUS_METERS * 0.005)
        }
    }

    @Test
    fun `the ring is closed, as a GeoJSON polygon must be`() {
        val ring = offlineAreaRing(home)

        assertEquals(ring.first(), ring.last())
        // Corners plus the closing repeat of the first.
        assertEquals(OFFLINE_AREA_CORNERS + 1, ring.size)
    }

    @Test
    fun `the ring goes all the way round, north, east, south and west of the centre`() {
        val ring = offlineAreaRing(home)

        assertTrue(ring.any { it.latitude > home.latitude + 0.1 })
        assertTrue(ring.any { it.latitude < home.latitude - 0.1 })
        assertTrue(ring.any { it.longitude > home.longitude + 0.1 })
        assertTrue(ring.any { it.longitude < home.longitude - 0.1 })
    }

    @Test
    fun `a ring across the date line keeps its longitudes on the map`() {
        // Fiji. East of the centre is past 180, which must come back as a longitude near -180
        // rather than 180.2 — a number no map accepts.
        val ring = offlineAreaRing(MapFix(latitude = -16.5, longitude = 179.95))

        assertTrue(ring.all { it.longitude > -180.0 && it.longitude <= 180.0 })
        assertTrue(ring.any { it.longitude < -179.0 })
    }
}
