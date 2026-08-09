package com.example.runningapp.ui

import android.content.ContentResolver
import android.net.Uri
import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteSource
import com.example.runningapp.routes.FakeRouteDao
import com.example.runningapp.routes.RouteImporter
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
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class RoutesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val uri: Uri = mock()
    private val dao = FakeRouteDao()

    private fun viewModelReading(gpx: String?): RoutesViewModel {
        val resolver: ContentResolver = mock()
        whenever(resolver.openInputStream(eq(uri))).doAnswer { gpx?.byteInputStream() as InputStream? }
        whenever(resolver.query(eq(uri), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .doReturn(null)
        return RoutesViewModel(dao, RouteImporter(resolver, dao, now = { 1_700_000_000_000L }), io = dispatcher)
    }

    private val aRealGpx = """
        <gpx version="1.1">
          <metadata><name>Park loop</name></metadata>
          <trk><trkseg>
            <trkpt lat="51.5" lon="-0.1"/><trkpt lat="51.501" lon="-0.1"/>
          </trkseg></trk>
        </gpx>
    """.trimIndent()

    private fun aStoredRoute(name: String = "Park loop") = Route(
        id = 1,
        name = name,
        distanceMeters = 111.0,
        elevationGainMeters = null,
        polyline = "51.5000000,-0.1000000",
        createdAtMillis = 0,
        source = RouteSource.IMPORTED,
    )

    @Test
    fun keepsTheFileAndSaysSo() = runTest {
        val viewModel = viewModelReading(aRealGpx)

        viewModel.fileChosen(uri)
        advanceUntilIdle()

        assertEquals("Park loop", dao.stored.single().name)
        assertEquals("Saved “Park loop” to your routes.", viewModel.message.value)
        assertEquals(false, viewModel.importing.value)
    }

    @Test
    fun keepsNothingAndSaysWhy() = runTest {
        val viewModel = viewModelReading("<kml/>")

        viewModel.fileChosen(uri)
        advanceUntilIdle()

        assertTrue(dao.stored.isEmpty())
        assertTrue(viewModel.message.value!!.startsWith("That file isn't a GPX route."))
    }

    /** Backing out of the picker is not a failure and must say nothing at all. */
    @Test
    fun saysNothingWhenTheRunnerBacksOutOfThePicker() = runTest {
        val viewModel = viewModelReading(aRealGpx)

        viewModel.fileChosen(null)
        advanceUntilIdle()

        assertTrue(dao.stored.isEmpty())
        assertNull(viewModel.message.value)
    }

    /** A double tap on Import must not race two copies of one file into the library. */
    @Test
    fun readsOneFileAtATime() = runTest {
        val viewModel = viewModelReading(aRealGpx)

        viewModel.fileChosen(uri)
        viewModel.fileChosen(uri)
        advanceUntilIdle()

        assertEquals(1, dao.stored.size)
    }

    @Test
    fun renamesARoute() = runTest {
        dao.insertRoute(aStoredRoute())
        val viewModel = viewModelReading(aRealGpx)

        viewModel.rename(dao.stored.single(), "  Canal towpath  ")
        advanceUntilIdle()

        // Trimmed: the surrounding spaces are a slip of the keyboard, not part of the name.
        assertEquals("Canal towpath", dao.stored.single().name)
    }

    /** An emptied box is a change of mind, not a request for a Route with no name. */
    @Test
    fun leavesARouteNamedWhatItWasWhenTheBoxIsEmptied() = runTest {
        dao.insertRoute(aStoredRoute())
        val viewModel = viewModelReading(aRealGpx)

        viewModel.rename(dao.stored.single(), "   ")
        advanceUntilIdle()

        assertEquals("Park loop", dao.stored.single().name)
    }

    @Test
    fun forgetsARoute() = runTest {
        dao.insertRoute(aStoredRoute())
        val viewModel = viewModelReading(aRealGpx)

        viewModel.delete(dao.stored.single())
        advanceUntilIdle()

        assertTrue(dao.stored.isEmpty())
    }

    @Test
    fun stopsSayingItOnceItHasBeenRead() = runTest {
        val viewModel = viewModelReading(aRealGpx)
        viewModel.fileChosen(uri)
        advanceUntilIdle()

        viewModel.messageShown()

        assertNull(viewModel.message.value)
    }
}
