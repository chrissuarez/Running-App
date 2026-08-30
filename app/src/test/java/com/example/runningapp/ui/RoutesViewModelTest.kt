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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        return RoutesViewModel(
            dao,
            RouteImporter(resolver, dao, now = { 1_700_000_000_000L }),
            io = dispatcher,
            // The shapes too, so a test can see a pass finish rather than wait on a real thread.
            courseDispatcher = dispatcher,
        )
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
    fun `keeps the file and says so`() = runTest {
        val viewModel = viewModelReading(aRealGpx)

        viewModel.fileChosen(uri)
        advanceUntilIdle()

        assertEquals("Park loop", dao.stored.single().name)
        assertEquals("Saved “Park loop” to your routes.", viewModel.message.value)
        assertEquals(false, viewModel.importing.value)
    }

    /**
     * The case Android creates on its own: an "Open with" intent left in the task is handed back
     * when the app is reopened from the recents list, and the runner must not find a second copy.
     */
    @Test
    fun `says the route is already kept rather than keeping it twice`() = runTest {
        val viewModel = viewModelReading(aRealGpx)

        viewModel.fileChosen(uri)
        advanceUntilIdle()
        viewModel.fileChosen(uri)
        advanceUntilIdle()

        assertEquals(1, dao.stored.size)
        // Through the function rather than the words: RouteModelsTest owns what they say.
        assertEquals(routeAlreadySavedMessage("Park loop"), viewModel.message.value)
    }

    @Test
    fun `keeps nothing and says why`() = runTest {
        val viewModel = viewModelReading("<kml/>")

        viewModel.fileChosen(uri)
        advanceUntilIdle()

        assertTrue(dao.stored.isEmpty())
        assertTrue(viewModel.message.value!!.startsWith("That file isn't a GPX route."))
    }

    /** Backing out of the picker is not a failure and must say nothing at all. */
    @Test
    fun `says nothing when the runner backs out of the picker`() = runTest {
        val viewModel = viewModelReading(aRealGpx)

        viewModel.fileChosen(null)
        advanceUntilIdle()

        assertTrue(dao.stored.isEmpty())
        assertNull(viewModel.message.value)
    }

    /** A double tap on Import must not race two copies of one file into the library. */
    @Test
    fun `reads one file at a time`() = runTest {
        val viewModel = viewModelReading(aRealGpx)

        viewModel.fileChosen(uri)
        viewModel.fileChosen(uri)
        advanceUntilIdle()

        assertEquals(1, dao.stored.size)
    }

    @Test
    fun `renames a route`() = runTest {
        dao.insertRoute(aStoredRoute())
        val viewModel = viewModelReading(aRealGpx)

        advanceUntilIdle()

        viewModel.rename(viewModel.rows.value.single().route, "  Canal towpath  ")
        advanceUntilIdle()

        // Trimmed: the surrounding spaces are a slip of the keyboard, not part of the name.
        assertEquals("Canal towpath", dao.stored.single().name)
    }

    /** An emptied box is a change of mind, not a request for a Route with no name. */
    @Test
    fun `leaves a route named what it was when the box is emptied`() = runTest {
        dao.insertRoute(aStoredRoute())
        val viewModel = viewModelReading(aRealGpx)

        advanceUntilIdle()

        viewModel.rename(viewModel.rows.value.single().route, "   ")
        advanceUntilIdle()

        assertEquals("Park loop", dao.stored.single().name)
    }

    @Test
    fun `forgets a route`() = runTest {
        dao.insertRoute(aStoredRoute())
        val viewModel = viewModelReading(aRealGpx)

        advanceUntilIdle()

        viewModel.delete(viewModel.rows.value.single().route)
        advanceUntilIdle()

        assertTrue(dao.stored.isEmpty())
    }

    @Test
    fun `stops saying it once it has been read`() = runTest {
        val viewModel = viewModelReading(aRealGpx)
        viewModel.fileChosen(uri)
        advanceUntilIdle()

        viewModel.messageShown()

        assertNull(viewModel.message.value)
    }

    // ---- The shape drawn beside each row (#59) ----------------------------------------------

    /**
     * A course long enough to have a shape: about a hundred and forty metres east, which clears the
     * sixty metres a drawing needs before it is a shape rather than a scatter.
     */
    private fun aCourseWithAShape(id: Long = 1, polyline: String = EAST) = Route(
        id = id,
        name = "Park loop",
        distanceMeters = 139.0,
        elevationGainMeters = null,
        polyline = polyline,
        createdAtMillis = 0,
        source = RouteSource.IMPORTED,
    )

    /**
     * Watches the rows for as long as the test runs.
     *
     * The rows are only worked out while something is looking, exactly as they are on the phone, so
     * a test that read [RoutesViewModel.rows] without collecting it would be reading the empty list
     * the flow starts at and would pass whatever the code did.
     */
    private fun TestScope.rowsOf(viewModel: RoutesViewModel): () -> List<RouteRowUi> {
        backgroundScope.launch { viewModel.rows.collect { } }
        return { viewModel.rows.value }
    }

    @Test
    fun `draws the shape of each kept course`() = runTest {
        dao.insertRoute(aCourseWithAShape())
        val viewModel = viewModelReading(aRealGpx)
        val rows = rowsOf(viewModel)

        viewModel.drawCoursesWhileLibraryIsOpen()
        advanceUntilIdle()

        assertNotNull("the row should have a shape to draw", rows().single().thumbnail)
    }

    /**
     * A row is still a row without a drawing. A course that covers no ground — a damaged line, or
     * one point left after a lenient read — has no shape, and the library must still list it under
     * its name rather than dropping it.
     */
    @Test
    fun `lists a course that has no shape, with nothing drawn`() = runTest {
        dao.insertRoute(aCourseWithAShape(polyline = "51.5000000,-0.1000000"))
        val viewModel = viewModelReading(aRealGpx)
        val rows = rowsOf(viewModel)

        viewModel.drawCoursesWhileLibraryIsOpen()
        advanceUntilIdle()

        assertEquals("Park loop", rows().single().route.name)
        assertNull(rows().single().thumbnail)
    }

    /**
     * The lines are read one at a time and never listed, which is the rule #403 states.
     *
     * The library arrives without its lines, and the drawing pass asks for each course's line as it
     * reaches it. So a library of many high-detail courses costs one course's text at a time rather
     * than all of it for as long as the Routes screen is open. Each line is asked for once, because
     * what the pass keeps is the drawing.
     */
    @Test
    fun `asks for each course's line once and never lists them`() = runTest {
        dao.insertRoute(aCourseWithAShape(polyline = EAST))
        dao.insertRoute(aCourseWithAShape(polyline = NORTH))
        val viewModel = viewModelReading(aRealGpx)
        val rows = rowsOf(viewModel)

        viewModel.drawCoursesWhileLibraryIsOpen()
        advanceUntilIdle()

        assertEquals(2, rows().count { it.thumbnail != null })
        assertEquals(listOf(2L, 1L), dao.lineAsks)
    }

    /**
     * A course deleted between the list arriving and its line being asked for is not a crash.
     *
     * The two are separate reads now, so the row can go between them — the runner can delete a
     * course while the library is on screen. The row simply has no shape, and the next emission
     * drops it from the list altogether.
     */
    @Test
    fun `a course deleted before its line is read leaves nothing drawn`() = runTest {
        dao.insertRoute(aCourseWithAShape())
        val viewModel = viewModelReading(aRealGpx)
        val rows = rowsOf(viewModel)
        advanceUntilIdle()

        dao.deleteRoute(1)
        viewModel.drawCoursesWhileLibraryIsOpen()
        advanceUntilIdle()

        assertTrue(rows().isEmpty())
    }

    /**
     * The screen says "No routes yet" when this list is empty, so the list must not be empty merely
     * because nobody has looked at it yet — a runner with a library would be told they have none for
     * the first frames of every visit.
     */
    @Test
    fun `has the library in hand before the screen asks for it`() = runTest {
        dao.insertRoute(aCourseWithAShape())
        val viewModel = viewModelReading(aRealGpx)

        // Nothing collects, and the shapes are never asked for: only the rows themselves.
        advanceUntilIdle()

        assertEquals(1, viewModel.rows.value.size)
        assertNull("nothing should be drawn until it is asked for", viewModel.rows.value.single().thumbnail)
    }

    /** Opening the library twice must not set two passes going over the same courses. */
    @Test
    fun `works the shapes out once however often the library is opened`() = runTest {
        dao.insertRoute(aCourseWithAShape())
        val viewModel = viewModelReading(aRealGpx)
        val rows = rowsOf(viewModel)

        viewModel.drawCoursesWhileLibraryIsOpen()
        viewModel.drawCoursesWhileLibraryIsOpen()
        advanceUntilIdle()

        assertEquals(1, rows().size)
        assertNotNull(rows().single().thumbnail)
    }

    private companion object {
        /** About 140 m east of Regent's Park. */
        const val EAST = "51.5000000,-0.1000000 51.5000000,-0.0980000"

        /** The same length of course, turned a quarter — a different shape at the same size. */
        const val NORTH = "51.5000000,-0.1000000 51.5012500,-0.1000000"
    }
}
