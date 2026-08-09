package com.example.runningapp.routes

import java.util.Locale

/**
 * How a Route's course is written into its database row and read back (#54).
 *
 * Plain text — `lat,lon` pairs separated by spaces — rather than one of the packed encodings a map
 * library would offer. A Route is stored once and read on every screen that draws it, so what
 * matters is that a row can be read by eye when something looks wrong on a phone, and that there is
 * no codec of our own to be subtly wrong about. Seven decimal places is a little over a centimetre,
 * the same precision the app writes a GPX out at.
 *
 * Height is deliberately dropped. What a Route needs elevation for is its climb, and that is worked
 * out once at import and banked on the row ([com.example.runningapp.data.Route]); carrying the
 * profile as well would treble the size of every row to answer a question nothing asks.
 */
object RoutePolyline {

    fun encode(points: List<RoutePoint>): String = points.joinToString(" ") { point ->
        String.format(Locale.US, "%.7f,%.7f", point.latitude, point.longitude)
    }

    /**
     * The course a row holds, with anything unreadable in it passed over.
     *
     * Lenient on purpose. This only ever reads what [encode] wrote, so a pair that does not parse
     * means the row is damaged — and a Route that draws most of itself is worth more to a runner
     * than a screen that will not open.
     */
    fun decode(polyline: String): List<RoutePoint> =
        polyline.split(' ', '\n').mapNotNull { pair ->
            val parts = pair.split(',')
            if (parts.size != 2) return@mapNotNull null
            val latitude = parts[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val longitude = parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@mapNotNull null
            RoutePoint(latitude, longitude, elevationMeters = null)
        }
}
