package com.example.runningapp.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-file tests (#84): the exported bytes are the contract with every other tool that reads a
 * GPX file, so the whole document is pinned rather than spot-checked. Regenerate a golden only when
 * the format change is deliberate.
 */
class GpxWriterTest {

    @Test
    fun `writes a scripted run with elevation and heart rate`() {
        val track = GpxTrack(
            name = "Morning Run",
            startTimeMillis = 1_753_500_000_000, // 2025-07-26T03:20:00Z
            points = listOf(
                GpxTrackPoint(51.5074, -0.1278, 12.3, 1_753_500_000_000, 101),
                GpxTrackPoint(51.50745, -0.12775, 12.8, 1_753_500_001_000, 104),
                GpxTrackPoint(51.5075, -0.1277, 13.0, 1_753_500_002_000, 110)
            )
        )

        assertEquals(golden("scripted-run.gpx"), GpxWriter.write(track))
    }

    @Test
    fun `omits elevation and heart rate where the run did not record them`() {
        val track = GpxTrack(
            name = "Run & Walk <test>",
            startTimeMillis = 1_753_500_000_000,
            points = listOf(
                GpxTrackPoint(51.5074, -0.1278, null, 1_753_500_000_000, null),
                GpxTrackPoint(51.50745, -0.12775, 12.8, 1_753_500_001_000, null),
                GpxTrackPoint(51.5075, -0.1277, null, 1_753_500_002_000, 110)
            )
        )

        assertEquals(golden("sparse-run.gpx"), GpxWriter.write(track))
    }

    @Test
    fun `escapes XML markup in the run name`() {
        val gpx = GpxWriter.write(
            GpxTrack(
                name = "Run & Walk <test>",
                startTimeMillis = 1_753_500_000_000,
                points = listOf(GpxTrackPoint(1.0, 2.0, null, 1_753_500_000_000, null))
            )
        )

        assertTrue(gpx.contains("<name>Run &amp; Walk &lt;test&gt;</name>"))
    }

    @Test
    fun `formats coordinates independently of the device locale`() {
        val previous = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        try {
            val gpx = GpxWriter.write(
                GpxTrack(
                    name = "Run",
                    startTimeMillis = 1_753_500_000_000,
                    points = listOf(GpxTrackPoint(51.5074, -0.1278, 12.3, 1_753_500_000_000, 101))
                )
            )

            assertTrue(gpx.contains("""lat="51.5074000" lon="-0.1278000""""))
            assertTrue(gpx.contains("<ele>12.3</ele>"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `declares the GPX 1_1 and Garmin TrackPointExtension namespaces`() {
        val gpx = GpxWriter.write(
            GpxTrack(
                name = "Run",
                startTimeMillis = 1_753_500_000_000,
                points = listOf(GpxTrackPoint(1.0, 2.0, null, 1_753_500_000_000, 120))
            )
        )

        assertTrue(gpx.startsWith("""<?xml version="1.0" encoding="UTF-8"?>"""))
        assertTrue(gpx.contains("""version="1.1""""))
        assertTrue(gpx.contains("http://www.topografix.com/GPX/1/1"))
        assertTrue(gpx.contains("http://www.garmin.com/xmlschemas/TrackPointExtension/v1"))
        assertTrue(gpx.contains("<gpxtpx:hr>120</gpxtpx:hr>"))
    }

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/gpx/$name")) { "Missing golden file gpx/$name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
}
