package com.example.runningapp.routes

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import com.example.runningapp.data.RouteSource
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.FileNotFoundException
import java.io.InputStream
import java.time.ZoneId

class RouteImporterTest {

    private val uri: Uri = mock()
    private val dao = FakeRouteDao()

    private fun importerFor(
        contents: String?,
        fileNamedOnDisk: String? = null,
        openThrows: Exception? = null,
    ): RouteImporter {
        val resolver: ContentResolver = mock()
        when {
            openThrows != null -> whenever(resolver.openInputStream(eq(uri))).doThrow(openThrows)
            // A stream per call, so an importer that reads twice cannot pass by accident.
            else -> whenever(resolver.openInputStream(eq(uri))).doAnswer {
                contents?.byteInputStream() as InputStream?
            }
        }
        val cursor: Cursor? = fileNamedOnDisk?.let { name ->
            mock<Cursor>().also {
                whenever(it.moveToFirst()).doReturn(true)
                whenever(it.isNull(0)).doReturn(false)
                whenever(it.getString(0)).doReturn(name)
            }
        }
        // anyOrNull throughout: the importer asks for one column and passes null for the
        // selection, and a plain any() would not match those.
        whenever(resolver.query(eq(uri), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .doReturn(cursor)
        return RouteImporter(resolver, dao, now = { 1_700_000_000_000L })
    }

    private val aRealGpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
          <metadata><name>Regent's Park loop</name></metadata>
          <trk><trkseg>
            <trkpt lat="51.5000000" lon="-0.1000000"><ele>10.0</ele></trkpt>
            <trkpt lat="51.5010000" lon="-0.1000000"><ele>10.0</ele></trkpt>
            <trkpt lat="51.5020000" lon="-0.1000000"><ele>40.0</ele></trkpt>
          </trkseg></trk>
        </gpx>
    """.trimIndent()

    @Test
    fun `keeps the picked file as a route`() = runTest {
        val outcome = importerFor(aRealGpx, fileNamedOnDisk = "download-3.gpx").import(uri)

        val route = dao.stored.single()
        assertEquals(RouteImportOutcome.Imported(route.id, "Regent's Park loop"), outcome)
        assertEquals("Regent's Park loop", route.name)
        assertEquals(222.4, route.distanceMeters, 1.0)
        // That the heights reached the row at all. What they add up to is the shape module's
        // question, and RouteShapeTest is where it is asked.
        assertNotNull(route.elevationGainMeters)
        assertEquals(
            "51.5000000,-0.1000000 51.5010000,-0.1000000 51.5020000,-0.1000000",
            route.polyline,
        )
        assertEquals(1_700_000_000_000L, route.createdAtMillis)
        assertEquals(RouteSource.IMPORTED, route.source)
    }

    @Test
    fun `the same course handed over twice is one route`() = runTest {
        val first = importerFor(aRealGpx).import(uri)
        val again = importerFor(aRealGpx).import(uri)

        val route = dao.stored.single()
        assertEquals(RouteImportOutcome.Imported(route.id, "Regent's Park loop"), first)
        assertEquals(RouteImportOutcome.AlreadySaved("Regent's Park loop"), again)
    }

    @Test
    fun `a course already kept is answered under the name the runner gave it`() = runTest {
        importerFor(aRealGpx).import(uri)
        val kept = dao.stored.single()
        dao.renameRoute(kept.id, "Tuesday hills")

        val again = importerFor(aRealGpx).import(uri)

        // The file still calls it "Regent's Park loop"; the runner does not. Naming the file back
        // at them would point at a row that is not in their library under that name.
        assertEquals(RouteImportOutcome.AlreadySaved("Tuesday hills"), again)
        assertEquals(1, dao.stored.size)
    }

    /**
     * The remedy ADR 0014 names for a banked number: a Route's distance and climb are worked out
     * once, and re-importing the file is the only thing that revisits them.
     */
    @Test
    fun `a fuller export of a course already kept re-measures it`() = runTest {
        val withoutHeights = """
            <gpx version="1.1"><metadata><name>Regent's Park loop</name></metadata><trk><trkseg>
              <trkpt lat="51.5000000" lon="-0.1000000"/>
              <trkpt lat="51.5010000" lon="-0.1000000"/>
              <trkpt lat="51.5020000" lon="-0.1000000"/>
            </trkseg></trk></gpx>
        """.trimIndent()

        importerFor(withoutHeights).import(uri)
        dao.renameRoute(dao.stored.single().id, "Tuesday hills")
        assertNull(dao.stored.single().elevationGainMeters)

        // The same three points, now with heights on them: the same line, measured better.
        val outcome = importerFor(aRealGpx).import(uri)

        val route = dao.stored.single()
        assertEquals(RouteImportOutcome.Remeasured("Tuesday hills"), outcome)
        // Absent became stated, which is the whole difference between the two files: what the
        // climb adds up to is RouteShapeTest's question, not this one's.
        assertNotNull(route.elevationGainMeters)
        // Their name for the course survives the file's own name arriving a second time.
        assertEquals("Tuesday hills", route.name)
    }

    @Test
    fun `a different course is kept alongside the first`() = runTest {
        val elsewhere = """
            <gpx version="1.1"><metadata><name>Hampstead</name></metadata><trk><trkseg>
              <trkpt lat="51.56" lon="-0.17"/><trkpt lat="51.561" lon="-0.17"/>
            </trkseg></trk></gpx>
        """.trimIndent()

        importerFor(aRealGpx).import(uri)
        importerFor(elsewhere).import(uri)

        assertEquals(2, dao.stored.size)
    }

    @Test
    fun `names it after the file on disk when the gpx names nothing`() = runTest {
        val nameless = """
            <gpx version="1.1"><trk><trkseg>
              <trkpt lat="51.5" lon="-0.1"/><trkpt lat="51.501" lon="-0.1"/>
            </trkseg></trk></gpx>
        """.trimIndent()

        importerFor(nameless, fileNamedOnDisk = "regents-park-loop.gpx").import(uri)

        assertEquals("regents-park-loop", dao.stored.single().name)
    }

    @Test
    fun `keeps a route whose file carried no heights`() = runTest {
        val flat = """
            <gpx version="1.1"><trk><trkseg>
              <trkpt lat="51.5" lon="-0.1"/><trkpt lat="51.501" lon="-0.1"/>
            </trkseg></trk></gpx>
        """.trimIndent()

        importerFor(flat).import(uri)

        // Absent, not zero — the file said nothing about the ground rather than that it is flat.
        assertNull(dao.stored.single().elevationGainMeters)
    }

    @Test
    fun `writes nothing when the file is not a gpx`() = runTest {
        val outcome = importerFor("<kml><Placemark/></kml>").import(uri)

        assertEquals(RouteImportOutcome.Refused(GpxRefusal.NOT_GPX), outcome)
        assertTrue(dao.stored.isEmpty())
    }

    @Test
    fun `writes nothing when the gpx holds no route`() = runTest {
        val outcome = importerFor("""<gpx version="1.1"><trk><trkseg/></trk></gpx>""").import(uri)

        assertEquals(RouteImportOutcome.Refused(GpxRefusal.NO_POINTS), outcome)
        assertTrue(dao.stored.isEmpty())
    }

    /** A half-downloaded file, truncated mid-track. Nothing of it is kept. */
    @Test
    fun `writes nothing when the file is cut short`() = runTest {
        val outcome = importerFor(
            """<gpx version="1.1"><trk><trkseg><trkpt lat="51.5" lon="-0.1"/>"""
        ).import(uri)

        assertEquals(RouteImportOutcome.Refused(GpxRefusal.UNREADABLE), outcome)
        assertTrue(dao.stored.isEmpty())
    }

    @Test
    fun `refuses a file the provider will not open`() = runTest {
        assertEquals(
            RouteImportOutcome.Refused(GpxRefusal.UNREADABLE),
            importerFor(contents = null).import(uri),
        )
        assertEquals(
            RouteImportOutcome.Refused(GpxRefusal.UNREADABLE),
            importerFor(contents = null, openThrows = FileNotFoundException("gone")).import(uri),
        )
        assertTrue(dao.stored.isEmpty())
    }

    /** An "Open with" the app came back to after being killed: the read grant has lapsed. */
    @Test
    fun `refuses a file it is no longer allowed to read`() = runTest {
        val outcome = importerFor(
            contents = null,
            openThrows = SecurityException("permission denial"),
        ).import(uri)

        assertEquals(RouteImportOutcome.Refused(GpxRefusal.UNREADABLE), outcome)
        assertTrue(dao.stored.isEmpty())
    }

    /** A provider that will not say what the file is called must not cost the runner the import. */
    @Test
    fun `imports even when nothing will name the file`() = runTest {
        val nameless = """
            <gpx version="1.1"><trk><trkseg>
              <trkpt lat="51.5" lon="-0.1"/><trkpt lat="51.501" lon="-0.1"/>
            </trkseg></trk></gpx>
        """.trimIndent()

        importerFor(nameless, fileNamedOnDisk = null).import(uri)

        assertEquals("Imported route", dao.stored.single().name)
    }

    /**
     * A file arriving while the runner keeps the very same course off a Run — and the library holds
     * one Route afterwards, not two.
     *
     * Two hands reach this table now: a picked GPX, and the "Save as route" button on a Run's page
     * (#55). They are not the same tap and nothing on either screen stops them overlapping, because
     * an import carries on after the runner has left the Routes screen — long enough to open the
     * Run they were looking for and keep its ground while the file is still being decided about.
     *
     * Asked and then told, the import loses that race in the gap it leaves: it looks, finds nothing,
     * goes off to ask the provider what the file is called, and by the time it writes, the button
     * has already kept the line. Both rows draw the same course and nothing in the table tells them
     * apart, so the runner is left to work out which of two identical Routes to delete. Only one way
     * in can promise otherwise, which is why both come through [com.example.runningapp.data.RouteDao.keepRoute].
     */
    @Test
    fun `a file and a run keeping the same course at once keep one route`() = runTest {
        val london = ZoneId.of("Europe/London")
        val run = RunnerSession(
            id = 7,
            startTime = 1_700_000_000_000L,
            endTime = 1_700_000_600_000L,
            durationSeconds = 600,
            runMode = "outdoor",
        )
        val lap = listOf(0.0 to 0.0, 500.0 to 0.0, 500.0 to 500.0, 0.0 to 500.0)
            .mapIndexed { i, (north, east) ->
                TrackPoint(
                    sessionId = 7,
                    latitude = 51.5 + north / 111_320.0,
                    longitude = -0.1 + east / (111_320.0 * 0.6225),
                    timestampMillis = 1_700_000_000_000L + i * 60_000L,
                    source = TrackPointSource.GPS,
                )
            }

        // What line the Run comes down to is the saver's business and it thins the track to get
        // there, so the file is written out of the line itself rather than out of the fixes: these
        // two must be handing the library the same course, or the test is about nothing.
        val rehearsal = FakeRouteDao()
        RunRouteSaver(rehearsal, now = { 1_700_000_500_000L }).save(run, lap, london)
        val theSameCourse = rehearsal.stored.single().polyline
        val fileOfTheSameCourse = theSameCourse.split(' ').joinToString(
            separator = "\n",
            prefix = """<gpx version="1.1"><trk><trkseg>""",
            postfix = "</trkseg></trk></gpx>",
        ) { pair ->
            val (latitude, longitude) = pair.split(',')
            """<trkpt lat="$latitude" lon="$longitude"/>"""
        }

        // Long enough that each is still deciding when the other arrives.
        dao.findDelayMillis = 50
        val importer = importerFor(fileOfTheSameCourse)
        val saver = RunRouteSaver(dao, now = { 1_700_000_500_000L })

        val outcomes = listOf(
            async { importer.import(uri) },
            async { saver.save(run, lap, london) },
        ).awaitAll()

        // One row, and only one of the two was told it had kept it — the other was sent to the
        // row that keeping it made. Which of them got there first is not this test's business.
        val route = dao.stored.single()
        assertEquals(theSameCourse, route.polyline)
        assertEquals(
            "one of them kept the course and the other was sent to it, but got $outcomes",
            1,
            outcomes.count { it is RouteImportOutcome.Imported || it is RunRouteOutcome.Saved },
        )
    }
}
