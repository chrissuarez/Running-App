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
import com.example.runningapp.export.ExportFileStore
import com.example.runningapp.export.ExportFormat
import com.garmin.fit.Decode
import com.garmin.fit.Factory
import com.garmin.fit.Mesg
import com.garmin.fit.MesgListener
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import java.io.ByteArrayInputStream
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
class SessionDetailViewModelExportTest {

    private val startTime = 1_753_500_000_000L // 2025-07-26T03:20:00Z
    private val dispatcher = StandardTestDispatcher()

    /** Captures what the view model asked to be written, and what it does with a null Uri back. */
    private class RecordingExportFileStore(private val uriToReturn: Uri? = null) : ExportFileStore {
        var fileName: String? = null
        var contents: ByteArray? = null
        var calls = 0

        override suspend fun write(fileName: String, contents: ByteArray): Uri? {
            calls++
            this.fileName = fileName
            this.contents = contents
            return uriToReturn
        }

        val text: String get() = contents!!.toString(Charsets.UTF_8)
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

    private fun sample(elapsedSeconds: Long, rawBpm: Int) = HrSample(
        sessionId = 7L,
        elapsedSeconds = elapsedSeconds,
        rawBpm = rawBpm,
        smoothedBpm = 100,
        connectionState = "Connected",
        timestampMillis = startTime + elapsedSeconds * 1000
    )

    // -- What a run must have before it can be written at all --------------------------------------

    @Test
    fun `a run with neither Strap nor GPS is still a FIT file stating what the runner said`() = runTest(dispatcher) {
        // The treadmill Run started without the strap on (#329): no track, no samples, and still a
        // Duration and a Stated Distance. This is the case the FIT export exists for, so it is the
        // one case that must not be refused.
        val store = RecordingExportFileStore(uriToReturn = mock<Uri>())
        val viewModel = SessionDetailViewModel(
            repository(session = session().copy(distanceKm = 5.0)),
            store,
            dispatcher
        )

        viewModel.shareRun(7L, ExportFormat.FIT)
        advanceUntilIdle()

        assertEquals(1, store.calls)
        assertNull(viewModel.exportShareFailed.value)
        assertTrue("file name was ${store.fileName}", store.fileName!!.endsWith(".fit"))
        val messages = decode(store.contents!!)
        assertTrue(messages.filterIsInstance<RecordMesg>().isEmpty())
        val fitSession = messages.filterIsInstance<SessionMesg>().single()
        assertEquals(60.0f, fitSession.totalElapsedTime, 0.001f)
        assertEquals(5000.0f, fitSession.totalDistance, 0.01f)
    }

    @Test
    fun `a run with no GPS track can be no GPX, and reports so`() = runTest(dispatcher) {
        // A trackpoint needs a position; a treadmill run has none to give it.
        val store = RecordingExportFileStore()
        val viewModel = SessionDetailViewModel(
            repository(trackPoints = emptyList(), samples = listOf(sample(0, 120))),
            store,
            dispatcher
        )

        viewModel.shareRun(7L, ExportFormat.GPX)
        advanceUntilIdle()

        assertEquals(7L, viewModel.exportShareFailed.value)
        assertEquals(0, store.calls)
    }

    @Test
    fun `a deleted run reports a failed share`() = runTest(dispatcher) {
        val store = RecordingExportFileStore()
        val viewModel = SessionDetailViewModel(repository(session = null), store, dispatcher)

        viewModel.shareRun(7L, ExportFormat.FIT)
        advanceUntilIdle()

        assertEquals(7L, viewModel.exportShareFailed.value)
        assertEquals(0, store.calls)
    }

    @Test
    fun `a run still being recorded reports a failed share and never writes a file`() = runTest(dispatcher) {
        // History stays reachable mid-run, so the button can be reached before the run is saved.
        val store = RecordingExportFileStore()
        val viewModel = SessionDetailViewModel(
            repository(session = session().copy(endTime = 0), trackPoints = listOf(gpsPoint(0))),
            store,
            dispatcher
        )

        viewModel.shareRun(7L, ExportFormat.FIT)
        advanceUntilIdle()

        assertEquals(7L, viewModel.exportShareFailed.value)
        assertEquals(0, store.calls)
    }

    // -- GPX ---------------------------------------------------------------------------------------

    @Test
    fun `writes the run's GPX with per-point heart rate under a run-named file`() = runTest(dispatcher) {
        val store = RecordingExportFileStore()
        val viewModel = SessionDetailViewModel(
            repository(
                trackPoints = listOf(gpsPoint(0), gpsPoint(1)),
                samples = listOf(sample(0, 120), sample(1, 122))
            ),
            store,
            dispatcher
        )

        viewModel.shareRun(7L, ExportFormat.GPX)
        advanceUntilIdle()

        assertEquals(1, store.calls)
        assertTrue("file name was ${store.fileName}", store.fileName!!.endsWith(".gpx"))
        val gpx = store.text
        assertTrue(gpx.contains("<gpxtpx:hr>120</gpxtpx:hr>"))
        assertTrue(gpx.contains("<gpxtpx:hr>122</gpxtpx:hr>"))
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
    }

    @Test
    fun `points rejected by the accuracy gate are left out of the file`() = runTest(dispatcher) {
        val store = RecordingExportFileStore()
        val viewModel = SessionDetailViewModel(
            repository(
                trackPoints = listOf(gpsPoint(0), gpsPoint(1).copy(horizontalAccuracyMeters = 120f))
            ),
            store,
            dispatcher
        )

        viewModel.shareRun(7L, ExportFormat.GPX)
        advanceUntilIdle()

        assertEquals(1, Regex("<trkpt ").findAll(store.text).count())
    }

    // -- FIT ---------------------------------------------------------------------------------------

    @Test
    fun `writes the run's FIT under a run-named file the SDK can read back`() = runTest(dispatcher) {
        val store = RecordingExportFileStore()
        val viewModel = SessionDetailViewModel(
            repository(
                trackPoints = listOf(gpsPoint(0), gpsPoint(1)),
                samples = listOf(sample(0, 120), sample(1, 122))
            ),
            store,
            dispatcher
        )

        viewModel.shareRun(7L, ExportFormat.FIT)
        advanceUntilIdle()

        assertEquals(1, store.calls)
        assertTrue("file name was ${store.fileName}", store.fileName!!.endsWith(".fit"))
        val messages = decode(store.contents!!)
        assertEquals(60.0f, messages.filterIsInstance<SessionMesg>().single().totalElapsedTime, 0.001f)
        assertEquals(listOf(120, 122), messages.filterIsInstance<RecordMesg>().map { it.heartRate!!.toInt() })
    }

    @Test
    fun `a run with no GPS at all is still a FIT file with its heart rates in it`() = runTest(dispatcher) {
        // The run GPX cannot describe (#218): a treadmill Run, or one that never found the sky.
        val store = RecordingExportFileStore(uriToReturn = mock<Uri>())
        val viewModel = SessionDetailViewModel(
            repository(trackPoints = emptyList(), samples = listOf(sample(0, 120), sample(1, 124))),
            store,
            dispatcher
        )

        viewModel.shareRun(7L, ExportFormat.FIT)
        advanceUntilIdle()

        assertEquals(1, store.calls)
        val records = decode(store.contents!!).filterIsInstance<RecordMesg>()
        assertEquals(listOf(120, 124), records.map { it.heartRate!!.toInt() })
        records.forEach { assertNull(it.positionLat) }
        assertEquals(ExportFormat.FIT, viewModel.exportShareReady.value?.format)
    }

    // -- What happens to the written file ----------------------------------------------------------

    @Test
    fun `a written file is kept until the screen has opened the share sheet`() = runTest(dispatcher) {
        // The export outlives the screen that asked for it: a result announced into thin air while
        // the activity is being recreated would leave the runner's tap doing nothing at all.
        val store = RecordingExportFileStore(uriToReturn = mock<Uri>())
        val viewModel = SessionDetailViewModel(repository(trackPoints = listOf(gpsPoint(0))), store, dispatcher)

        viewModel.shareRun(7L, ExportFormat.GPX)
        advanceUntilIdle()

        // Named in the device's own time zone, so only the run's id is pinned here.
        val ready = viewModel.exportShareReady.value
        assertTrue("file name was ${ready?.fileName}", ready?.fileName?.endsWith("-7.gpx") == true)
        assertEquals(ExportFormat.GPX, ready?.format)

        viewModel.exportShareHandled()
        assertNull(viewModel.exportShareReady.value)
    }

    @Test
    fun `a result names the run that asked for it`() = runTest(dispatcher) {
        // The screen reading these belongs to the activity, not to one run, and an export is slow
        // enough that the runner can be looking at a different run by the time it lands. Without the
        // id there is nothing to tell it apart from a share sheet opening over the wrong screen.
        val store = RecordingExportFileStore(uriToReturn = mock<Uri>())
        val viewModel = SessionDetailViewModel(repository(trackPoints = listOf(gpsPoint(0))), store, dispatcher)

        viewModel.shareRun(7L, ExportFormat.GPX)
        advanceUntilIdle()

        assertEquals(7L, viewModel.exportShareReady.value?.sessionId)
    }

    @Test
    fun `a store that cannot produce a shareable file reports a failed share`() = runTest(dispatcher) {
        val store = RecordingExportFileStore(uriToReturn = null)
        val viewModel = SessionDetailViewModel(repository(trackPoints = listOf(gpsPoint(0))), store, dispatcher)

        viewModel.shareRun(7L, ExportFormat.GPX)
        advanceUntilIdle()

        assertEquals(1, store.calls)
        assertEquals(7L, viewModel.exportShareFailed.value)
    }

    @Test
    fun `sharing with no file target wired reports a failed share`() = runTest(dispatcher) {
        val viewModel = SessionDetailViewModel(repository(trackPoints = listOf(gpsPoint(0))), null, dispatcher)

        viewModel.shareRun(7L, ExportFormat.GPX)
        advanceUntilIdle()

        assertEquals(7L, viewModel.exportShareFailed.value)
    }

    private fun decode(bytes: ByteArray): List<Mesg> {
        val messages = mutableListOf<Mesg>()
        Decode().read(ByteArrayInputStream(bytes), MesgListener { messages += Factory.createMesg(it) })
        return messages
    }
}
