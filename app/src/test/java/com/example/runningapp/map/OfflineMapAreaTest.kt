package com.example.runningapp.map

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.recording.geodesicDistanceMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The ground the offline map covers is a ring drawn round the runner, so what is worth being sure of
 * is that the ring is where it says it is: every corner the stated distance out, closed, and whole
 * even where longitude runs out — there it is two rings, cut at the date line, each on the map.
 */
class OfflineMapAreaTest {

    private val home = MapFix(latitude = 50.83, longitude = -0.14)

    /** Fiji. East of the centre is past 180°. */
    private val fiji = MapFix(latitude = -16.5, longitude = 179.95)

    /** Samoa's side. West of the centre is past -180°. */
    private val samoaSide = MapFix(latitude = -14.0, longitude = -179.95)

    @Test
    fun `far from the date line the area is exactly one ring`() {
        assertEquals(1, offlineAreaRings(home).size)
    }

    @Test
    fun `every corner of the ring is the stated distance from the centre`() {
        val ring = offlineAreaRings(home).single()

        ring.forEach { corner ->
            val meters = geodesicDistanceMeters(home.latitude, home.longitude, corner.latitude, corner.longitude)
            // Within half a percent: the ring is laid out on a sphere and measured on the ellipsoid,
            // and a few tens of metres at 15 km moves no tile.
            assertEquals(OFFLINE_AREA_RADIUS_METERS, meters, OFFLINE_AREA_RADIUS_METERS * 0.005)
        }
    }

    @Test
    fun `the ring is closed, as a GeoJSON polygon must be`() {
        val ring = offlineAreaRings(home).single()

        assertEquals(ring.first(), ring.last())
        // Corners plus the closing repeat of the first.
        assertEquals(OFFLINE_AREA_CORNERS + 1, ring.size)
    }

    @Test
    fun `the ring goes all the way round, north, east, south and west of the centre`() {
        val ring = offlineAreaRings(home).single()

        assertTrue(ring.any { it.latitude > home.latitude + 0.1 })
        assertTrue(ring.any { it.latitude < home.latitude - 0.1 })
        assertTrue(ring.any { it.longitude > home.longitude + 0.1 })
        assertTrue(ring.any { it.longitude < home.longitude - 0.1 })
    }

    @Test
    fun `across the date line the area is two closed rings, each on the map and unbroken`() {
        listOf(fiji, samoaSide, MapFix(-16.5, 180.0), MapFix(-16.5, -180.0)).forEach { center ->
            val rings = offlineAreaRings(center)

            assertEquals("$center", 2, rings.size)
            rings.forEach { ring ->
                assertEquals("$center", ring.first(), ring.last())
                assertTrue("$center", ring.size >= 4)
                assertTrue("$center", ring.all { it.longitude in -180.0..180.0 })
                // A 30 km circle's neighbouring corners are a fraction of a degree apart. An edge
                // wider than that runs the other way round the world.
                ring.zipWithNext().forEach { (a, b) ->
                    assertTrue("$center: $a to $b", abs(a.longitude - b.longitude) <= 1.0)
                }
            }
        }
    }

    @Test
    fun `the two pieces meet along the date line, at the same latitudes`() {
        listOf(fiji, samoaSide).forEach { center ->
            val (first, second) = offlineAreaRings(center)
            val eastPiece = listOf(first, second).single { ring -> ring.all { it.longitude > 0 } }
            val westPiece = listOf(first, second).single { ring -> ring.all { it.longitude < 0 } }

            val eastCut = eastPiece.filter { it.longitude == 180.0 }.map { it.latitude }.toSet()
            val westCut = westPiece.filter { it.longitude == -180.0 }.map { it.latitude }.toSet()
            assertEquals("$center", 2, eastCut.size)
            assertEquals("$center", eastCut, westCut)
        }
    }

    @Test
    fun `cut in two, every corner is still the stated distance from the centre`() {
        val rings = offlineAreaRings(fiji)

        rings.flatten().filter { abs(it.longitude) != 180.0 }.forEach { corner ->
            val meters = geodesicDistanceMeters(fiji.latitude, fiji.longitude, corner.latitude, corner.longitude)
            assertEquals(OFFLINE_AREA_RADIUS_METERS, meters, OFFLINE_AREA_RADIUS_METERS * 0.005)
        }
        // No corner is lost to the cut: every one of them lands in one piece or the other.
        assertEquals(OFFLINE_AREA_CORNERS, rings.flatten().filter { abs(it.longitude) != 180.0 }.toSet().size)
    }
}
