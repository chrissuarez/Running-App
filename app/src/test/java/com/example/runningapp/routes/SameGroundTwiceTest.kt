package com.example.runningapp.routes

import com.example.runningapp.data.RouteSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A library that ends up holding one piece of ground twice says so (#402).
 *
 * The residue #354 and #399 could not reach. A course saved from a Run before #354 was thinned from
 * places that had not been snapped first, so its line cannot be drawn again from that Run's own GPX;
 * hand the GPX back and the line is a hair different, the identity read misses, and a second row
 * lands under the same name with nothing in the table to tell the pair apart.
 *
 * The remedy is a sentence, not a merge. Which of two courses over one piece of ground is the real
 * one is the runner's call — the same call [courseRecognising] declines to make on their behalf — so
 * the pair is reported the moment it appears and the runner deletes whichever they do not want.
 *
 * Walked through the real [com.example.runningapp.data.RouteDao.keepRoute] rather than asked of the
 * rule in the abstract: what is being tested is that the news reaches whoever wrote the row.
 */
class SameGroundTwiceTest {

    private val metersPerDegree = 111_320.0

    /** A place so far north and east of one corner of Regent's Park. */
    private fun place(northMeters: Double, eastMeters: Double, at: Double = 51.5) = RoutePoint(
        latitude = at + northMeters / metersPerDegree,
        longitude = -0.1 + eastMeters / (metersPerDegree * 0.6225),
        elevationMeters = 10.0,
    )

    /** A kilometre of straight road, as a row thinned before #354 would hold it: two places. */
    private val asThinnedLongAgo = listOf(place(0.0, 0.0), place(1_000.0, 0.0))

    /**
     * The very same road as that Run's own GPX draws it today: the middle place sits a hair off the
     * two-metre line, so today's snap-then-thin keeps it and the legacy thinning dropped it. One
     * road, two lines — which is the whole of the fault.
     */
    private val asDrawnToday =
        listOf(place(0.0, 0.0), place(500.0, 1.9995), place(1_000.0, 0.0))

    /** A road a county away. */
    private val elsewhere = listOf(place(0.0, 0.0, at = 52.5), place(1_000.0, 0.0, at = 52.5))

    private suspend fun FakeRouteDao.keep(name: String, places: List<RoutePoint>) = keepRoute(
        courseOf(places).asRoute(name, createdAtMillis = 1_700_000_000_000L, source = RouteSource.FROM_RUN),
        remeasuring = false,
    )

    @Test
    fun `the two lines really are two rows, which is why there is anything to say`() = runTest {
        val dao = FakeRouteDao()

        dao.keep("Run 27 Aug 2026, 12:35", asThinnedLongAgo)
        dao.keep("Run 27 Aug 2026, 12:35", asDrawnToday)

        assertEquals(2, dao.stored.size)
    }

    @Test
    fun `a second row over ground already kept names the course already kept`() = runTest {
        val dao = FakeRouteDao()
        dao.keep("Cuckoo Trail", asThinnedLongAgo)

        val kept = dao.keep("Run 27 Aug 2026, 12:35", asDrawnToday)

        assertEquals("Cuckoo Trail", kept.sameGroundAs)
    }

    /** The first course a runner keeps has nothing to be a twin of. */
    @Test
    fun `the first course over a piece of ground says nothing`() = runTest {
        val dao = FakeRouteDao()

        assertNull(dao.keep("Cuckoo Trail", asThinnedLongAgo).sameGroundAs)
    }

    @Test
    fun `a course over other ground says nothing`() = runTest {
        val dao = FakeRouteDao()
        dao.keep("Cuckoo Trail", asThinnedLongAgo)

        assertNull(dao.keep("Somewhere else", elsewhere).sameGroundAs)
    }

    /**
     * The line the library already holds is *the same row*, not a second one. Nothing was written,
     * so there is no pair, and telling the runner about one would be an invention.
     */
    @Test
    fun `handing over a line the library already holds says nothing`() = runTest {
        val dao = FakeRouteDao()
        dao.keep("Cuckoo Trail", asThinnedLongAgo)

        val again = dao.keep("Cuckoo Trail", asThinnedLongAgo)

        assertEquals(1, dao.stored.size)
        assertNull(again.sameGroundAs)
    }

    /** A line too short to hold a shape has no ground to be recognised on. */
    @Test
    fun `a course too short to be shaped says nothing`() = runTest {
        val dao = FakeRouteDao()
        val aShortHop = listOf(place(0.0, 0.0), place(50.0, 0.0))
        dao.keep("Round the block", aShortHop)

        assertNull(dao.keep("Round the block again", listOf(place(0.0, 0.0), place(51.0, 0.0))).sameGroundAs)
    }
}
