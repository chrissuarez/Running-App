package com.example.runningapp.navigation

import com.example.runningapp.analysis.RecordType
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {

    @Test
    fun `sessionDetail builds a route that matches the detail route pattern`() {
        val expected = Routes.SESSION_DETAIL.replace("{${Routes.ARG_SESSION_ID}}", "42")
        assertEquals(expected, Routes.sessionDetail(42L))
    }

    // A page that closes itself pops a *filled* address — "session_detail/9", not
    // "session_detail/{sessionId}" — because only a filled one carries the arguments
    // NavDestination.hasRoute compares against, and so only a filled one names the single entry
    // about that Run, Segment, group or Record (#412). A builder that drifted from its own pattern
    // would still compile and would still read correctly, but every such pop would silently match
    // nothing and the page would never close. These lock each builder to its pattern.
    @Test
    fun `matchedRuns builds a route that matches the matched-runs route pattern`() {
        val expected = Routes.MATCHED_RUNS.replace("{${Routes.ARG_SESSION_ID}}", "42")
        assertEquals(expected, Routes.matchedRuns(42L))
    }

    @Test
    fun `segmentDetail builds a route that matches the segment-detail route pattern`() {
        val expected = Routes.SEGMENT_DETAIL.replace("{${Routes.ARG_SEGMENT_ID}}", "7")
        assertEquals(expected, Routes.segmentDetail(7L))
    }

    @Test
    fun `segmentCreate builds a route that matches the segment-create route pattern`() {
        val expected = Routes.SEGMENT_CREATE.replace("{${Routes.ARG_SESSION_ID}}", "7")
        assertEquals(expected, Routes.segmentCreate(7L))
    }

    @Test
    fun `recordDetail builds a route that matches the record-detail route pattern`() {
        val type = RecordType.entries.first()
        val expected = Routes.RECORD_DETAIL.replace("{${Routes.ARG_RECORD_TYPE}}", type.name)
        assertEquals(expected, Routes.recordDetail(type))
    }

    // The name-only overload is what closes a page opened for a Record this app cannot name, so it
    // has to spell the same address the RecordType overload does for every Record it *can* name —
    // otherwise the two disagree exactly where the page is hardest to reach.
    @Test
    fun `recordDetail from a name spells the same address as recordDetail from the Record`() {
        RecordType.entries.forEach { type ->
            assertEquals(Routes.recordDetail(type), Routes.recordDetail(type.name))
        }
    }

    @Test
    fun `all screen routes are distinct`() {
        val routes = listOf(
            Routes.MAIN,
            Routes.SETTINGS,
            Routes.MANAGE_DEVICES,
            Routes.HISTORY,
            Routes.SESSION_DETAIL,
            Routes.TRAINING_PLAN,
            Routes.MAP,
            Routes.PROGRESS
        )
        assertEquals(routes.size, routes.toSet().size)
    }
}
