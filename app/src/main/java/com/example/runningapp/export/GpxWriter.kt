package com.example.runningapp.export

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One point of a run as GPX sees it: where, when, how high, and the heart rate at that moment.
 * Elevation and heart rate are optional because a run can record a position without either.
 */
data class GpxTrackPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val timeMillis: Long,
    val heartRateBpm: Int?
)

/**
 * An unbroken stretch of a run: points a reader may join up, one to the next.
 *
 * A run is a list of these rather than one list of points because a pause tears the route in two.
 * Nothing was recorded while the runner stood still, and a reader given a single stretch would draw
 * a straight line across the break and count it as distance run — which the app itself refuses to
 * do (`SessionRecorder.discardLastFix`).
 */
data class GpxTrackSegment(val points: List<GpxTrackPoint>)

/** A whole run, ready to serialise. [startTimeMillis] becomes the file's metadata time. */
data class GpxTrack(
    val name: String,
    val startTimeMillis: Long,
    val segments: List<GpxTrackSegment>
) {
    /** Every point of the run in order, whichever stretch it belongs to. */
    val points: List<GpxTrackPoint> get() = segments.flatMap { it.points }
}

/**
 * Serialises a run as GPX 1.1 with per-point heart rate in the Garmin TrackPointExtension namespace
 * — the shape Strava, Garmin Connect and Runalyze all read (#84).
 *
 * Deliberately a pure function with no Android dependencies: the export's correctness is pinned by
 * golden-file tests on the JVM, and the Android side only has to put the string in a file.
 */
object GpxWriter {

    const val FILE_EXTENSION = "gpx"
    const val MIME_TYPE = "application/gpx+xml"

    private const val CREATOR = "Running App"
    private const val GPX_NS = "http://www.topografix.com/GPX/1/1"
    private const val XSI_NS = "http://www.w3.org/2001/XMLSchema-instance"
    private const val TPX_NS = "http://www.garmin.com/xmlschemas/TrackPointExtension/v1"
    private const val SCHEMA_LOCATION =
        "$GPX_NS http://www.topografix.com/GPX/1/1/gpx.xsd " +
            "$TPX_NS http://www.garmin.com/xmlschemas/TrackPointExtensionv1.xsd"

    // Whole seconds, UTC. GPS fixes arrive about a second apart, so sub-second precision would only
    // add noise, and every reader accepts this form.
    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun write(track: GpxTrack): String {
        val name = escape(track.name)
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("<gpx version=\"1.1\" creator=\"$CREATOR\"")
            append(" xmlns=\"$GPX_NS\"")
            append(" xmlns:xsi=\"$XSI_NS\"")
            append(" xmlns:gpxtpx=\"$TPX_NS\"")
            append(" xsi:schemaLocation=\"$SCHEMA_LOCATION\">").append('\n')
            append("  <metadata>").append('\n')
            append("    <name>$name</name>").append('\n')
            append("    <time>${formatTime(track.startTimeMillis)}</time>").append('\n')
            append("  </metadata>").append('\n')
            append("  <trk>").append('\n')
            append("    <name>$name</name>").append('\n')
            append("    <type>running</type>").append('\n')
            // One <trkseg> per unbroken stretch: the break between two of them is what tells a
            // reader the runner was not moving between the last fix of one and the first of the next.
            track.segments.filter { it.points.isNotEmpty() }.forEach { segment ->
                append("    <trkseg>").append('\n')
                segment.points.forEach { appendPoint(it) }
                append("    </trkseg>").append('\n')
            }
            append("  </trk>").append('\n')
            append("</gpx>").append('\n')
        }
    }

    private fun StringBuilder.appendPoint(point: GpxTrackPoint) {
        // Element order inside trkpt is fixed by the schema: ele, time, then extensions.
        append("      <trkpt lat=\"${formatCoordinate(point.latitude)}\" lon=\"${formatLongitude(point.longitude)}\">").append('\n')
        point.elevationMeters?.let {
            append("        <ele>${formatElevation(it)}</ele>").append('\n')
        }
        append("        <time>${formatTime(point.timeMillis)}</time>").append('\n')
        point.heartRateBpm?.let { bpm ->
            append("        <extensions>").append('\n')
            append("          <gpxtpx:TrackPointExtension>").append('\n')
            append("            <gpxtpx:hr>$bpm</gpxtpx:hr>").append('\n')
            append("          </gpxtpx:TrackPointExtension>").append('\n')
            append("        </extensions>").append('\n')
        }
        append("      </trkpt>").append('\n')
    }

    private fun formatTime(epochMillis: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis))

    // Locale.US throughout: a device set to German would otherwise write "51,5074" and produce a
    // file no reader can parse.
    private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.7f", value)

    /**
     * GPX bounds longitude at 180 exclusive, so a fix on the antimeridian must be written as the
     * same meridian's other name, -180. Rounding to seven places is what makes this reachable at
     * all: a fix at 179.99999999 formats as 180.0000000, which no strict reader will accept.
     */
    private fun formatLongitude(value: Double): String {
        val formatted = formatCoordinate(value)
        return if (formatted == "180.0000000") "-180.0000000" else formatted
    }

    private fun formatElevation(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
