package com.example.runningapp.ui

import com.example.runningapp.data.RunShapeCandidate
import com.example.runningapp.data.RunShapeDao
import com.example.runningapp.data.SampleDao
import com.example.runningapp.data.SessionDao
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.measureTrack
import com.example.runningapp.data.runShapeRowOf
import com.example.runningapp.segments.runShapeOf
import java.time.LocalDate
import java.util.TimeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * The matched-runs card answering from the zone the phone is in *now* (#73, #320).
 *
 * A Run recorded before #304 carries no offset of its own, so the day it fell on is whatever the
 * live zone says. That makes this screen a calendar reader, and every calendar reader in the app is
 * woken by the zone-change nudge rather than waiting for history to change under it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelMatchedRunsTest {

    private val dispatcher = StandardTestDispatcher()

    /** Sunday 2026-08-02, 23:30 in London — already Monday the 3rd in Auckland. */
    private val sundayNight = 1_785_709_800_000L
    private val aDay = 24 * 60 * 60 * 1000L

    private val zoneChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val shapedRuns = MutableStateFlow<List<RunShapeCandidate>>(emptyList())
    private val startingZone = TimeZone.getDefault()

    @After
    fun tearDown() = TimeZone.setDefault(startingZone)

    private fun viewModel() = SessionDetailViewModel(
        SessionRepository(
            sessionDao = mock<SessionDao>(),
            sampleDao = mock<SampleDao>(),
            runShapeDao = mock<RunShapeDao> { on { getShapedRunsFlow() } doReturn shapedRuns },
        ),
        zoneChanges = zoneChanges,
    )

    /** A Run of 500 m out and back, [day] days after the first, carrying **no** offset of its own. */
    private fun aLegacyRun(id: Long, day: Long): RunShapeCandidate {
        val row = runShapeRowOf(id, runShapeOf(measureTrack(outAndBack(id)))!!)
        return RunShapeCandidate(
            sessionId = id,
            shape = row.shape!!,
            distanceMeters = row.distanceMeters,
            startTime = sundayNight + day * aDay,
            ranAtUtcOffsetSeconds = null,
            durationSeconds = 1_800L,
            movingTimeSeconds = 1_700L,
            avgPaceMinPerKm = 6.0,
        )
    }

    private fun outAndBack(id: Long): List<TrackPoint> {
        val metersPerDegreeLongitude = 69_000.0
        val places = buildList {
            var out = 0.0
            while (out <= 500.0) { add(out); out += 10.0 }
            var back = 500.0
            while (back >= 0.0) { add(back); back -= 10.0 }
        }
        return places.mapIndexed { i, meters ->
            TrackPoint(
                sessionId = id,
                latitude = 51.5,
                longitude = -0.1 + meters / metersPerDegreeLongitude,
                timestampMillis = sundayNight + i * 5_000L,
                source = "GPS",
            )
        }
    }

    @Test
    fun `a zone change moves the dates on the matched-runs card`() = runTest(dispatcher) {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        shapedRuns.value = listOf(aLegacyRun(1L, day = 0), aLegacyRun(2L, day = 7))
        val dates = mutableListOf<List<LocalDate>>()
        val collecting = launch {
            viewModel().matchedRuns(1L).collect { group ->
                dates += group!!.runs.map { it.date }
            }
        }
        advanceUntilIdle()

        assertTrue(LocalDate.of(2026, 8, 2) in dates.last())

        // Nothing about history moves: the zone change is the whole input. Before this nudge was
        // fed in, the card went on naming the Sunday until some Run happened to be written.
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        assertTrue(zoneChanges.tryEmit(Unit))
        advanceUntilIdle()

        assertTrue(LocalDate.of(2026, 8, 3) in dates.last())
        assertEquals(2, dates.size)
        collecting.cancel()
    }
}
