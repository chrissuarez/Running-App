package com.example.runningapp.ui

import android.net.Uri
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.SampleDao
import com.example.runningapp.data.SessionDao
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointDao
import com.example.runningapp.data.TrackPointSource
import com.example.runningapp.export.GpxFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelGpxTest {

    private val startTime = 1_753_500_000_000L // 2025-07-26T03:20:00Z
    private val dispatcher = StandardTestDispatcher()

    /** Captures what the view model asked to be written, and what it does with a null Uri back. */
    private class RecordingGpxFileStore(private val uriToReturn: Uri? = null) : GpxFileStore {
        var fileName: String? = null
        var contents: String? = null
        var calls = 0

        override suspend fun write(fileName: String, contents: String): Uri? {
            calls++
            this.fileName = fileName
            this.contents = contents
            return uriToReturn
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun session() = RunnerSession(
        id = 7L,
        startTime = startTime,
        endTime = startTime + 60_000,
        durationSeconds = 60,
        avgBpm = 130,
        maxBpm = 150,
        targetZone = 2
    )

    private fun repository(
        session: RunnerSession? = session(),
        trackPoints: List<TrackPoint> = emptyList(),
        samples: List<HrSample> = emptyList()
    ) = SessionRepository(
        sessionDao = mock<SessionDao> { onBlocking { getSessionById(7L) } doReturn session },
        sampleDao = mock<SampleDao> { onBlocking { getSamplesForSessionOnce(7L) } doReturn samples },
        trackPointDao = mock<TrackPointDao> {
            onBlocking { getTrackPointsForSessionOnce(7L) } doReturn trackPoints
        }
    )

    private fun gpsPoint(offsetSeconds: Long) = TrackPoint(
        sessionId = 7L,
        latitude = 51.5074,
        longitude = -0.1278,
        altitudeMeters = 12.3,
        horizontalAccuracyMeters = 5f,
        timestampMillis = startTime + offsetSeconds * 1000,
        source = TrackPointSource.GPS
    )

    @Test
    fun `a run with no GPS track reports a failed share and never writes a file`() = runTest(dispatcher) {
        val store = RecordingGpxFileStore()
        val viewModel = SessionDetailViewModel(repository(trackPoints = emptyList()), store, dispatcher)

        viewModel.shareGpx(7L)
        advanceUntilIdle()

        assertTrue(viewModel.gpxShareFailed.value)
        assertEquals(0, store.calls)
        assertNull(store.contents)
    }

    @Test
    fun `a deleted run reports a failed share`() = runTest(dispatcher) {
        val store = RecordingGpxFileStore()
        val viewModel = SessionDetailViewModel(repository(session = null), store, dispatcher)

        viewModel.shareGpx(7L)
        advanceUntilIdle()

        assertTrue(viewModel.gpxShareFailed.value)
        assertEquals(0, store.calls)
    }

    @Test
    fun `a run still being recorded reports a failed share and never writes a file`() = runTest(dispatcher) {
        // History stays reachable mid-run, so the button can be reached before the run is saved.
        val store = RecordingGpxFileStore()
        val viewModel = SessionDetailViewModel(
            repository(session = session().copy(endTime = 0), trackPoints = listOf(gpsPoint(0))),
            store,
            dispatcher
        )

        viewModel.shareGpx(7L)
        advanceUntilIdle()

        assertTrue(viewModel.gpxShareFailed.value)
        assertEquals(0, store.calls)
    }

    @Test
    fun `writes the run's GPX with per-point heart rate under a run-named file`() = runTest(dispatcher) {
        val store = RecordingGpxFileStore()
        val viewModel = SessionDetailViewModel(
            repository(
                trackPoints = listOf(gpsPoint(0), gpsPoint(1)),
                samples = listOf(
                    HrSample(sessionId = 7L, elapsedSeconds = 0, rawBpm = 120, smoothedBpm = 100, connectionState = "Connected"),
                    HrSample(sessionId = 7L, elapsedSeconds = 1, rawBpm = 122, smoothedBpm = 100, connectionState = "Connected")
                )
            ),
            store,
            dispatcher
        )

        viewModel.shareGpx(7L)
        advanceUntilIdle()

        assertEquals(1, store.calls)
        assertTrue("file name was ${store.fileName}", store.fileName!!.endsWith(".gpx"))
        val gpx = store.contents!!
        assertTrue(gpx.contains("<gpxtpx:hr>120</gpxtpx:hr>"))
        assertTrue(gpx.contains("<gpxtpx:hr>122</gpxtpx:hr>"))
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
    }

    @Test
    fun `points rejected by the accuracy gate are left out of the file`() = runTest(dispatcher) {
        val store = RecordingGpxFileStore()
        val viewModel = SessionDetailViewModel(
            repository(
                trackPoints = listOf(gpsPoint(0), gpsPoint(1).copy(horizontalAccuracyMeters = 120f))
            ),
            store,
            dispatcher
        )

        viewModel.shareGpx(7L)
        advanceUntilIdle()

        assertEquals(1, Regex("<trkpt ").findAll(store.contents!!).count())
    }

    @Test
    fun `a written file is kept until the screen has opened the share sheet`() = runTest(dispatcher) {
        // The export outlives the screen that asked for it: a result announced into thin air while
        // the activity is being recreated would leave the runner's tap doing nothing at all.
        val store = RecordingGpxFileStore(uriToReturn = mock<Uri>())
        val viewModel = SessionDetailViewModel(repository(trackPoints = listOf(gpsPoint(0))), store, dispatcher)

        viewModel.shareGpx(7L)
        advanceUntilIdle()

        // Named in the device's own time zone, so only the run's id is pinned here.
        val ready = viewModel.gpxShareReady.value
        assertTrue("file name was ${ready?.fileName}", ready?.fileName?.endsWith("-7.gpx") == true)

        viewModel.gpxShareHandled()
        assertNull(viewModel.gpxShareReady.value)
    }

    @Test
    fun `a store that cannot produce a shareable file reports a failed share`() = runTest(dispatcher) {
        val store = RecordingGpxFileStore(uriToReturn = null)
        val viewModel = SessionDetailViewModel(repository(trackPoints = listOf(gpsPoint(0))), store, dispatcher)

        viewModel.shareGpx(7L)
        advanceUntilIdle()

        assertEquals(1, store.calls)
        assertTrue(viewModel.gpxShareFailed.value)
    }

    @Test
    fun `sharing with no file target wired reports a failed share`() = runTest(dispatcher) {
        val viewModel = SessionDetailViewModel(repository(trackPoints = listOf(gpsPoint(0))), null, dispatcher)

        viewModel.shareGpx(7L)
        advanceUntilIdle()

        assertTrue(viewModel.gpxShareFailed.value)
    }
}
