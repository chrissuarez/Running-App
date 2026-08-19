package com.example.runningapp.ui

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.segments.FakeSegmentEffortDao
import com.example.runningapp.segments.FakeSegmentDao
import com.example.runningapp.segments.SegmentCut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class SegmentsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeSegmentDao()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SegmentsViewModel(dao, FakeSegmentEffortDao(), now = { 1_700_000_000_000L })

    private val cut = SegmentCut.Cut(
        fixes = listOf(MapFix(51.5, -0.1), MapFix(51.50009, -0.1)),
        distanceMeters = 400.0,
    )

    @Test
    fun `a saved segment keeps its own copy of the ground`() = runTest(dispatcher) {
        viewModel().saveSegment(cut, "Cemetery Hill", sourceSessionId = 7)
        advanceUntilIdle()

        val saved = dao.stored.single()
        assertEquals("Cemetery Hill", saved.name)
        assertEquals(400.0, saved.distanceMeters, 0.0)
        assertEquals(7L, saved.sourceSessionId)
        assertEquals(1_700_000_000_000L, saved.createdAtMillis)
        assertEquals(cut.fixes.map { it.latitude }, RoutePolyline.decode(saved.polyline).map { it.latitude })
    }

    @Test
    fun `saving says so, in the name the runner gave`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.saveSegment(cut, "  Cemetery Hill  ", sourceSessionId = 7)
        advanceUntilIdle()

        assertEquals("Cemetery Hill", dao.stored.single().name)
        assertTrue(vm.message.value!!.contains("Cemetery Hill"))
    }

    @Test
    fun `a stretch that could not be cut is never written down`() = runTest(dispatcher) {
        viewModel().saveSegment(SegmentCut.SpansABreak, "Cemetery Hill", sourceSessionId = 7)
        advanceUntilIdle()

        assertTrue(dao.stored.isEmpty())
    }

    @Test
    fun `a nameless stretch is never written down`() = runTest(dispatcher) {
        viewModel().saveSegment(cut, "   ", sourceSessionId = 7)
        advanceUntilIdle()

        assertTrue(dao.stored.isEmpty())
    }

    @Test
    fun `renaming to nothing leaves the name alone`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.saveSegment(cut, "Cemetery Hill", sourceSessionId = 7)
        advanceUntilIdle()

        vm.rename(dao.stored.single(), "   ")
        advanceUntilIdle()

        assertEquals("Cemetery Hill", dao.stored.single().name)
    }

    @Test
    fun `renaming writes the new name, trimmed`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.saveSegment(cut, "Cemetery Hill", sourceSessionId = 7)
        advanceUntilIdle()

        vm.rename(dao.stored.single(), " The Wall ")
        advanceUntilIdle()

        assertEquals("The Wall", dao.stored.single().name)
    }

    @Test
    fun `deleting forgets the segment`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.saveSegment(cut, "Cemetery Hill", sourceSessionId = 7)
        advanceUntilIdle()

        vm.delete(dao.stored.single())
        advanceUntilIdle()

        assertTrue(dao.stored.isEmpty())
    }
}
