package com.example.runningapp.ui

import com.example.runningapp.data.SampleDao
import com.example.runningapp.data.SessionDao
import com.example.runningapp.data.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub

/**
 * What the app remembers while a Run is on its way out (#414).
 *
 * The Run's page asks for the delete and is popped somewhere else, so it has to close its forward
 * doors in between — and the record of *which* Run that is has to live exactly as long as the job
 * that will end it. Here is that job, so here is that record: it is set the moment the delete is
 * asked for, it stays on for a delete that lands, because that page is going anyway, and it comes
 * off for a delete that throws, because that page is staying. Held by the page across a
 * save-and-restore instead, a delete the phone reclaimed would leave a page saying "Deleting this
 * run…" for ever over a Run that is still there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelDeleteTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // A store the delete can actually get through: the one thing it asks history before touching a
    // row is which of these Runs fed the coach, and none of them did.
    private val sessionDao = mock<SessionDao> {
        onBlocking { getAiEligibleIdsIn(any()) } doReturn emptyList()
    }

    private fun viewModel() = SessionDetailViewModel(
        SessionRepository(sessionDao = sessionDao, sampleDao = mock<SampleDao>()),
    )

    @Test
    fun `the run being deleted is marked from the moment it is asked for, and stays marked`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val ended = mutableListOf<Long>()
            val listening = launch { viewModel.deleteCompleted.collect { ended += it } }
            advanceUntilIdle()

            viewModel.deleteSession(7L)

            // Before the work has had a chance to run at all: the page is asked to close its doors
            // on the same frame the runner confirmed, not one frame later.
            assertTrue(7L in viewModel.deletePending.value)

            advanceUntilIdle()

            assertEquals(listOf(7L), ended)
            // Still marked. The row has gone and the pop is on its way, so taking the mark off
            // here would only hand the doors back for the frame in between.
            assertTrue(7L in viewModel.deletePending.value)
            listening.cancel()
        }

    @Test
    fun `a delete that fails gives the page back rather than stranding it`() = runTest(dispatcher) {
        sessionDao.stub {
            onBlocking { deleteSessionById(7L) } doThrow IllegalStateException("the row would not go")
        }
        val viewModel = viewModel()
        val ended = mutableListOf<Long>()
        val listening = launch { viewModel.deleteCompleted.collect { ended += it } }
        advanceUntilIdle()

        viewModel.deleteSession(7L)
        assertTrue(7L in viewModel.deletePending.value)

        advanceUntilIdle()

        // The row is still there, so the page is not popped — and must stop saying that the Run is
        // going, or it is a page with nothing on it and no way to try again.
        assertFalse(7L in viewModel.deletePending.value)
        assertTrue(ended.isEmpty())
        listening.cancel()
    }
}
