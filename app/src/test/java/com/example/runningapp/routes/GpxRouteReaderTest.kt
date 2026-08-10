package com.example.runningapp.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpxRouteReaderTest {

    private fun read(xml: String): GpxReadOutcome = GpxRouteReader.read(xml.byteInputStream())

    private fun readOrFail(xml: String): GpxReadOutcome.Read =
        read(xml) as? GpxReadOutcome.Read ?: error("expected a readable GPX, got ${read(xml)}")

    private fun refusalOf(xml: String): GpxRefusal =
        (read(xml) as? GpxReadOutcome.Refused)?.reason ?: error("expected a refusal, got ${read(xml)}")

    @Test
    fun `reads track points in document order`() {
        val outcome = readOrFail(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <trkseg>
                  <trkpt lat="51.5000000" lon="-0.1000000"><ele>10.0</ele></trkpt>
                  <trkpt lat="51.5010000" lon="-0.1010000"><ele>12.5</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()
        )

        assertEquals(
            listOf(
                RoutePoint(51.5, -0.1, 10.0),
                RoutePoint(51.501, -0.101, 12.5),
            ),
            outcome.points,
        )
    }

    /**
     * A track's segments are one course. The breaks between them are facts about how the file was
     * recorded — a pause on the run it came from — and a route is the line the runner intends to
     * follow, so they are joined.
     */
    @Test
    fun `joins the segments of one track into one course`() {
        val outcome = readOrFail(
            """
            <gpx version="1.1">
              <trk>
                <trkseg><trkpt lat="1.0" lon="2.0"/></trkseg>
                <trkseg><trkpt lat="1.1" lon="2.1"/></trkseg>
              </trk>
              <trk>
                <trkseg><trkpt lat="1.2" lon="2.2"/></trkseg>
              </trk>
            </gpx>
            """.trimIndent()
        )

        assertEquals(3, outcome.points.size)
        assertEquals(RoutePoint(1.2, 2.2, null), outcome.points.last())
    }

    @Test
    fun `reads a route when the file has no track`() {
        val outcome = readOrFail(
            """
            <gpx version="1.1">
              <rte>
                <rtept lat="51.5" lon="-0.1"><ele>10</ele></rtept>
                <rtept lat="51.6" lon="-0.2"/>
              </rte>
            </gpx>
            """.trimIndent()
        )

        assertEquals(listOf(RoutePoint(51.5, -0.1, 10.0), RoutePoint(51.6, -0.2, null)), outcome.points)
    }

    /**
     * A file carrying both is a recorded track alongside a coarse plan of it. The track is the one
     * with the detail, so it wins outright rather than the two being run together.
     */
    @Test
    fun `prefers the track when the file carries both`() {
        val outcome = readOrFail(
            """
            <gpx version="1.1">
              <rte><rtept lat="9.0" lon="9.0"/></rte>
              <trk><trkseg><trkpt lat="1.0" lon="2.0"/></trkseg></trk>
            </gpx>
            """.trimIndent()
        )

        assertEquals(listOf(RoutePoint(1.0, 2.0, null)), outcome.points)
    }

    @Test
    fun `takes the name from the files metadata`() {
        val outcome = readOrFail(
            """
            <gpx version="1.1">
              <metadata><name>Regent's Park loop</name></metadata>
              <trk><name>Afternoon Run</name><trkseg><trkpt lat="1.0" lon="2.0"/></trkseg></trk>
            </gpx>
            """.trimIndent()
        )

        assertEquals("Regent's Park loop", outcome.name)
    }

    @Test
    fun `falls back to the tracks own name`() {
        val outcome = readOrFail(
            """
            <gpx version="1.1">
              <trk><name>Afternoon Run</name><trkseg><trkpt lat="1.0" lon="2.0"/></trkseg></trk>
            </gpx>
            """.trimIndent()
        )

        assertEquals("Afternoon Run", outcome.name)
    }

    @Test
    fun `falls back to the routes own name`() {
        val outcome = readOrFail(
            """
            <gpx version="1.1">
              <rte><name>Planned loop</name><rtept lat="1.0" lon="2.0"/></rte>
            </gpx>
            """.trimIndent()
        )

        assertEquals("Planned loop", outcome.name)
    }

    /** A name is only ever the file's suggestion; the importer has the filename to fall back on. */
    @Test
    fun `has no name when the file names nothing`() {
        val outcome = readOrFail(
            """
            <gpx version="1.1"><trk><trkseg><trkpt lat="1.0" lon="2.0"/></trkseg></trk></gpx>
            """.trimIndent()
        )

        assertNull(outcome.name)
    }

    /** A `<name>` full of nothing is no name at all, and must not become a blank route title. */
    @Test
    fun `treats a blank name as no name`() {
        val outcome = readOrFail(
            """
            <gpx version="1.1">
              <metadata><name>   </name></metadata>
              <trk><trkseg><trkpt lat="1.0" lon="2.0"/></trkseg></trk>
            </gpx>
            """.trimIndent()
        )

        assertNull(outcome.name)
    }

    /** Waypoints are places, not a course — a file holding only those has no route in it. */
    @Test
    fun `refuses a file holding only waypoints`() {
        assertEquals(
            GpxRefusal.NO_POINTS,
            refusalOf(
                """
                <gpx version="1.1"><wpt lat="1.0" lon="2.0"><name>Home</name></wpt></gpx>
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `refuses a gpx with no points at all`() {
        assertEquals(GpxRefusal.NO_POINTS, refusalOf("""<gpx version="1.1"><trk><trkseg/></trk></gpx>"""))
    }

    @Test
    fun `refuses an empty file`() {
        assertEquals(GpxRefusal.UNREADABLE, refusalOf(""))
    }

    @Test
    fun `refuses xml that is not gpx`() {
        assertEquals(GpxRefusal.NOT_GPX, refusalOf("""<kml><Placemark/></kml>"""))
    }

    @Test
    fun `refuses xml that does not close`() {
        assertEquals(
            GpxRefusal.UNREADABLE,
            refusalOf("""<gpx version="1.1"><trk><trkseg><trkpt lat="1.0" lon="2.0"/>"""),
        )
    }

    @Test
    fun `refuses a point with no position`() {
        assertEquals(
            GpxRefusal.UNREADABLE,
            refusalOf("""<gpx version="1.1"><trk><trkseg><trkpt lon="2.0"/></trkseg></trk></gpx>"""),
        )
    }

    @Test
    fun `refuses a position off the earth`() {
        assertEquals(
            GpxRefusal.UNREADABLE,
            refusalOf("""<gpx version="1.1"><trk><trkseg><trkpt lat="91.0" lon="2.0"/></trkseg></trk></gpx>"""),
        )
    }

    @Test
    fun `refuses a position that is not a number`()  {
        assertEquals(
            GpxRefusal.UNREADABLE,
            refusalOf("""<gpx version="1.1"><trk><trkseg><trkpt lat="north" lon="2.0"/></trkseg></trk></gpx>"""),
        )
    }

    /** An unreadable height is a missing height, not a broken file: the line is still followable. */
    @Test
    fun `reads a point whose height makes no sense`() {
        val outcome = readOrFail(
            """
            <gpx version="1.1"><trk><trkseg>
              <trkpt lat="1.0" lon="2.0"><ele>high up</ele></trkpt>
              <trkpt lat="1.1" lon="2.1"><ele/></trkpt>
            </trkseg></trk></gpx>
            """.trimIndent()
        )

        assertEquals(listOf(RoutePoint(1.0, 2.0, null), RoutePoint(1.1, 2.1, null)), outcome.points)
    }

    /**
     * A GPX exported by another app carries heart rate, cadence and the rest in `<extensions>`. A
     * route wants none of it, and an element named `name` in there must not become the route's.
     */
    @Test
    fun `ignores what other apps hang off a point`() {
        val outcome = readOrFail(
            """
            <gpx version="1.1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
              <trk><name>Real name</name><trkseg>
                <trkpt lat="1.0" lon="2.0">
                  <ele>10</ele>
                  <time>2026-08-09T09:00:00Z</time>
                  <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>150</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>
                </trkpt>
              </trkseg></trk>
            </gpx>
            """.trimIndent()
        )

        assertEquals("Real name", outcome.name)
        assertEquals(listOf(RoutePoint(1.0, 2.0, 10.0)), outcome.points)
    }

    /**
     * An `<extensions>` block may hold an element of any name at all, `ele` among them. Taking that
     * one would not merely add nothing — it would take the height back off the point, and a file
     * stating every height would import as one stating none.
     */
    @Test
    fun `keeps the point's own height when an extension carries one too`() {
        val outcome = readOrFail(
            """
            <gpx version="1.1" xmlns:vendor="http://example.com/vendor/v1">
              <trk><trkseg>
                <trkpt lat="1.0" lon="2.0">
                  <ele>10</ele>
                  <extensions><vendor:ele>999</vendor:ele></extensions>
                </trkpt>
              </trkseg></trk>
            </gpx>
            """.trimIndent()
        )

        assertEquals(listOf(RoutePoint(1.0, 2.0, 10.0)), outcome.points)
    }

    /**
     * The same rule as the height above, for the elements that carry a position: a vendor's own
     * `trkpt` inside an `<extensions>` block would otherwise add a point to a course nobody drew.
     */
    @Test
    fun `ignores a point-shaped element an extension carries`() {
        val outcome = readOrFail(
            """
            <gpx version="1.1" xmlns:vendor="http://example.com/vendor/v1">
              <trk><trkseg>
                <trkpt lat="1.0" lon="2.0">
                  <extensions><vendor:trkpt lat="80.0" lon="80.0"/></extensions>
                </trkpt>
              </trkseg></trk>
            </gpx>
            """.trimIndent()
        )

        assertEquals(listOf(RoutePoint(1.0, 2.0, null)), outcome.points)
    }

    /** And one with no position at all must not make an otherwise readable file unreadable. */
    @Test
    fun `reads a file whose extension carries a positionless point`() {
        val outcome = readOrFail(
            """
            <gpx version="1.1" xmlns:vendor="http://example.com/vendor/v1">
              <trk><trkseg>
                <trkpt lat="1.0" lon="2.0">
                  <extensions><vendor:trkpt>something</vendor:trkpt></extensions>
                </trkpt>
                <trkpt lat="1.001" lon="2.0"/>
              </trkseg></trk>
            </gpx>
            """.trimIndent()
        )

        assertEquals(
            listOf(RoutePoint(1.0, 2.0, null), RoutePoint(1.001, 2.0, null)),
            outcome.points,
        )
    }

    /** The name has to come from the part the course came from, not the part left behind. */
    @Test
    fun `takes the route's name when the named track is empty`() {
        val outcome = readOrFail(
            """
            <gpx version="1.1">
              <trk><name>Afternoon Run</name><trkseg/></trk>
              <rte><name>Planned loop</name><rtept lat="1.0" lon="2.0"/></rte>
            </gpx>
            """.trimIndent()
        )

        assertEquals("Planned loop", outcome.name)
        assertEquals(listOf(RoutePoint(1.0, 2.0, null)), outcome.points)
    }

    /** An entity pointed at the phone's own files must never be resolved (#54). */
    @Test
    fun `refuses a file that declares a doctype`() {
        assertEquals(
            GpxRefusal.UNREADABLE,
            refusalOf(
                """
                <?xml version="1.0"?>
                <!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/hosts">]>
                <gpx version="1.1"><trk><trkseg><trkpt lat="1.0" lon="2.0"/></trkseg></trk></gpx>
                """.trimIndent()
            ),
        )
    }

    /**
     * Text is the one part of a GPX that grows without adding a point (#54).
     *
     * The point cap counts points, so a file with one enormous name passes it while the reader
     * gathers the name into memory. This is the bound that catches that, and it is reached with the
     * point count still at zero.
     */
    @Test
    fun `refuses a file whose name runs on without end`() {
        assertEquals(
            GpxRefusal.TOO_LARGE,
            refusalOf(
                """
                <gpx version="1.1"><trk><name>${"pretending to be a name ".repeat(1_000)}</name>
                <trkseg><trkpt lat="1.0" lon="2.0"/></trkseg></trk></gpx>
                """.trimIndent()
            ),
        )
    }

    /** A name of an ordinary length is not caught by that bound (#54). */
    @Test
    fun `keeps a name of the length a person would write`() {
        val outcome = readOrFail(
            """
            <gpx version="1.1"><trk><name>${"Eastbourne seafront loop ".repeat(20)}</name>
            <trkseg><trkpt lat="1.0" lon="2.0"/></trkseg></trk></gpx>
            """.trimIndent()
        )

        assertEquals("Eastbourne seafront loop ".repeat(20).trim(), outcome.name)
    }

    /**
     * An entity that names no file at all is refused just the same (#54).
     *
     * The declaration is what is refused, not the fetching: an internal entity asks for a short
     * string to be expanded into a longer one, and nested declarations expand a few hundred bytes
     * into gigabytes — which the point cap cannot bound, the growth being inside one name rather
     * than in the number of points. This is the case a phone found: Android expands internal
     * entities and ignores the parser feature that was meant to have stopped the doctype.
     */
    @Test
    fun `refuses a file that declares an entity of its own`() {
        assertEquals(
            GpxRefusal.UNREADABLE,
            refusalOf(
                """
                <?xml version="1.0"?>
                <!DOCTYPE gpx [<!ENTITY expand "grown">]>
                <gpx version="1.1"><trk><name>&expand;</name>
                <trkseg><trkpt lat="1.0" lon="2.0"/></trkseg></trk></gpx>
                """.trimIndent()
            ),
        )
    }
}
