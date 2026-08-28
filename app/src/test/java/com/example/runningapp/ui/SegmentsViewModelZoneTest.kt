package com.example.runningapp.ui

import com.example.runningapp.data.Segment
import com.example.runningapp.data.SegmentEffort
import com.example.runningapp.routes.RoutePoint
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.segments.FakeSegmentDao
import com.example.runningapp.segments.FakeSegmentEffortDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/**
 * A Segment's efforts answering from the zone the phone is in *now* (#320, #343).
 *
 * An effort run before #304 carries no offset of its own, so the day it fell on is whatever the live
 * zone says. That makes this page a calendar reader, and every calendar reader in the app is woken
 * by the zone-change nudge rather than waiting for the efforts table to change under it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SegmentsViewModelZoneTest {

    private val dispatcher = StandardTestDispatcher()
    private val segmentDao = FakeSegmentDao()
    private val effortDao = FakeSegmentEffortDao()
    private val zoneChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val startingZone = TimeZone.getDefault()

    /** Sunday 2026-08-02, 23:30 in London — already Monday the 3rd in Auckland. */
    private val sundayNight = 1_785_709_800_000L

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        TimeZone.setDefault(startingZone)
    }

    @Test
    fun `a Segment's efforts are re-dated when the phone changes zone`() = runTest(dispatcher) {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        val segmentId = segmentDao.insertSegment(
            Segment(
                name = "The hill",
                polyline = RoutePolyline.encode(
                    listOf(RoutePoint(51.5, -0.1, null), RoutePoint(51.501, -0.1, null))
                ),
                distanceMeters = 400.0,
                sourceSessionId = 1,
                createdAtMillis = 0L,
            )
        )
        effortDao.insertEfforts(
            listOf(
                SegmentEffort(
                    segmentId = segmentId,
                    sessionId = 1,
                    startedAtMillis = sundayNight,
                    finishedAtMillis = sundayNight + 120_000L,
                )
            )
        )

        val viewModel = SegmentsViewModel(segmentDao, effortDao, zoneChanges = zoneChanges)
        val dates = mutableListOf<List<LocalDate>>()
        val collecting = launch { viewModel.efforts(segmentId).collect { dates += it.map { row -> row.date } } }
        advanceUntilIdle()

        assertEquals(listOf(LocalDate.of(2026, 8, 2)), dates.last())

        // Nothing about the efforts moves: the zone change is the whole input. Without this nudge
        // the page went on naming the Sunday until some effort happened to be written.
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        assertTrue(zoneChanges.tryEmit(Unit))
        advanceUntilIdle()

        assertEquals(listOf(LocalDate.of(2026, 8, 3)), dates.last())
        collecting.cancel()
    }
}
