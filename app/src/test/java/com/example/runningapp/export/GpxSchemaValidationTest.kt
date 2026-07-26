package com.example.runningapp.export

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import org.junit.Test

/**
 * Validates the export against the real GPX 1.1 schema, kept offline in test resources (#84). The
 * schema declares `extensions` content as `processContents="lax"`, so the Garmin heart-rate elements
 * pass through — exactly as they do in the readers this file is written for.
 */
class GpxSchemaValidationTest {

    @Test
    fun `a full run validates against the GPX 1_1 schema`() {
        validate(
            GpxWriter.write(
                GpxTrack(
                    name = "Morning Run",
                    startTimeMillis = 1_753_500_000_000,
                    segments = oneSegment(
                        GpxTrackPoint(51.5074, -0.1278, 12.3, 1_753_500_000_000, 101),
                        GpxTrackPoint(51.50745, -0.12775, 12.8, 1_753_500_001_000, 104)
                    )
                )
            )
        )
    }

    @Test
    fun `a run missing elevation and heart rate validates too`() {
        validate(
            GpxWriter.write(
                GpxTrack(
                    name = "Run & Walk <test>",
                    startTimeMillis = 1_753_500_000_000,
                    segments = oneSegment(GpxTrackPoint(-33.8688, 151.2093, null, 1_753_500_000_000, null))
                )
            )
        )
    }

    @Test
    fun `an empty track still validates`() {
        validate(
            GpxWriter.write(
                GpxTrack(name = "Run", startTimeMillis = 1_753_500_000_000, segments = emptyList())
            )
        )
    }

    /** Proves the validator above is actually validating rather than waving everything through. */
    @Test(expected = org.xml.sax.SAXException::class)
    fun `the validator rejects a document the schema forbids`() {
        validate(
            """<?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="x" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg/><name>out of order</name></trk>
            </gpx>
            """.trimIndent()
        )
    }

    private fun validate(gpx: String) {
        val schemaStream = checkNotNull(javaClass.getResourceAsStream("/gpx/gpx11.xsd")) {
            "Missing gpx/gpx11.xsd test resource"
        }
        val schema = schemaStream.use {
            SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(StreamSource(it))
        }
        // Throws SAXParseException with the offending line on any violation, which is a better test
        // failure than an assertion would be.
        schema.newValidator().validate(StreamSource(ByteArrayInputStream(gpx.toByteArray())))
    }

    /** A run with no break in it: one unbroken stretch of route. */
    private fun oneSegment(vararg points: GpxTrackPoint) = listOf(GpxTrackSegment(points.toList()))
}
