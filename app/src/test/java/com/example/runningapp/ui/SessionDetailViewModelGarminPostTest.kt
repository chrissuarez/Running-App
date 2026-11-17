package com.example.runningapp.ui

import android.net.Uri
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.SampleDao
import com.example.runningapp.data.SessionDao
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.TrackPointDao
import com.example.runningapp.export.ExportFileStore
import com.example.runningapp.export.GarminPostLedger
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

/** "Post this run to Garmin?" after a Run (#544). */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelGarminPostTest {

    private val dispatcher = StandardTestDispatcher()
    private val end = 1_763_400_000_000L

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeLedger(var askedThrough: Long = 0L) : GarminPostLedger {
        override suspend fun askedThroughRunId() = askedThrough
        override suspend fun markAsked(runId: Long) {
            if (runId > askedThrough) askedThrough = runId
        }
    }

    private class FakeStore(private val uri: Uri?) : ExportFileStore {
        var written: String? = null
        override suspend fun write(fileName: String, contents: ByteArray): Uri? {
            written = fileName
            return uri
        }
    }

    private fun run(runMode: String = "outdoor", distanceKm: Double = 5.0) = RunnerSession(
        id = 7L,
        startTime = end - 1_800_000,
        endTime = end,
        durationSeconds = 1_800,
        runMode = runMode,
        distanceKm = distanceKm,
    )

    private fun viewModel(
        ledger: GarminPostLedger?,
        store: ExportFileStore? = FakeStore(mock()),
        stored: RunnerSession = run(),
    ) = SessionDetailViewModel(
        SessionRepository(
            sessionDao = mock<SessionDao> { onBlocking { getSessionById(7L) } doReturn stored },
            sampleDao = mock<SampleDao> { onBlocking { getSamplesForSessionOnce(7L) } doReturn emptyList() },
            trackPointDao = mock<TrackPointDao> {
                onBlocking { getTrackPointsForSessionOnce(7L) } doReturn emptyList()
            },
        ),
        store,
        dispatcher,
        garminPostLedger = ledger,
        clock = { end + 60_000 },
    )

    @Test
    fun `a Run's page opened just after the Run asks`() = runTest(dispatcher) {
        val vm = viewModel(FakeLedger())
        vm.considerGarminPost(run())
        advanceUntilIdle()
        assertEquals(7L, vm.garminPostOffer.value)
    }

    @Test
    fun `a Run already asked about does not ask`() = runTest(dispatcher) {
        val vm = viewModel(FakeLedger(askedThrough = 7L))
        vm.considerGarminPost(run())
        advanceUntilIdle()
        assertNull(vm.garminPostOffer.value)
    }

    @Test
    fun `a treadmill Run asks only once its distance is saved`() = runTest(dispatcher) {
        val vm = viewModel(FakeLedger())
        vm.considerGarminPost(run(runMode = "treadmill", distanceKm = 0.0))
        advanceUntilIdle()
        assertNull(vm.garminPostOffer.value)
        vm.considerGarminPost(run(runMode = "treadmill", distanceKm = 6.0))
        advanceUntilIdle()
        assertEquals(7L, vm.garminPostOffer.value)
    }

    @Test
    fun `no is remembered, so the Run never asks again`() = runTest(dispatcher) {
        val ledger = FakeLedger()
        val store = FakeStore(mock())
        val vm = viewModel(ledger, store)
        vm.considerGarminPost(run())
        advanceUntilIdle()
        vm.answerGarminPost(7L, post = false)
        advanceUntilIdle()
        assertNull(vm.garminPostOffer.value)
        assertEquals(7L, ledger.askedThrough)
        assertNull(store.written)
        assertNull(vm.garminUploadReady.value)

        vm.considerGarminPost(run())
        advanceUntilIdle()
        assertNull(vm.garminPostOffer.value)
    }

    @Test
    fun `yes writes the Run's FIT and hands its Uri to the Garmin page`() = runTest(dispatcher) {
        val ledger = FakeLedger()
        val uri = mock<Uri>()
        val store = FakeStore(uri)
        val vm = viewModel(ledger, store)
        vm.considerGarminPost(run())
        advanceUntilIdle()
        vm.answerGarminPost(7L, post = true)
        advanceUntilIdle()
        assertEquals(7L, ledger.askedThrough)
        assertTrue(store.written!!.endsWith(".fit"))
        assertEquals(7L, vm.garminUploadReady.value?.sessionId)
        assertEquals(uri, vm.garminUploadReady.value?.uri)

        vm.garminUploadHandled()
        assertNull(vm.garminUploadReady.value)
    }

    @Test
    fun `yes with no file to give reports the failure instead`() = runTest(dispatcher) {
        val vm = viewModel(FakeLedger(), FakeStore(null))
        vm.answerGarminPost(7L, post = true)
        advanceUntilIdle()
        assertNull(vm.garminUploadReady.value)
        assertEquals(7L, vm.exportShareFailed.value)
    }

    @Test
    fun `a question left unanswered on another Run gives way to this one`() = runTest(dispatcher) {
        val vm = viewModel(FakeLedger())
        vm.considerGarminPost(run().copy(id = 6L))
        advanceUntilIdle()
        assertEquals(6L, vm.garminPostOffer.value)
        vm.considerGarminPost(run())
        advanceUntilIdle()
        assertEquals(7L, vm.garminPostOffer.value)
    }

    @Test
    fun `with nowhere to remember the answer, nothing asks`() = runTest(dispatcher) {
        val vm = viewModel(ledger = null)
        vm.considerGarminPost(run())
        advanceUntilIdle()
        assertNull(vm.garminPostOffer.value)
    }
}
