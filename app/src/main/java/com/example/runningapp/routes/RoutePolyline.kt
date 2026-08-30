package com.example.runningapp.routes

import java.security.MessageDigest
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

    /**
     * A fixed-length stand-in for one line, equal exactly when the lines are (#403).
     *
     * Here, beside the encoding itself, because it is a fact about the written line and nothing
     * else knows how a line is written. What it buys is a reader that has to tell many lines apart
     * without holding them: `libraryRedrawn` keeps one of these per surviving course — sixty-four
     * characters — where it used to keep the course's whole redrawn line.
     *
     * SHA-256, and the choice matters. This is what decides that two rows are the same course and
     * that one of them may be deleted, so a digest two different courses could share would lose a
     * runner a Route. Two different lines sharing a SHA-256 is not something that happens to a
     * library; it is something no one has ever produced for any pair of inputs at all.
     *
     * The alternative — keeping the digest as a hint and then reading the candidate row's line back
     * out of the table to confirm — was declined. The candidate was decided and written some rows
     * ago, so confirming would mean reading a line back out of a half-written table, which makes a
     * pure decision depend on the order the writer happened to get to. A cheaper digest defended by
     * a read is a worse trade than a digest that needs no defending.
     */
    fun digestOf(polyline: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(polyline.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun encode(points: List<RoutePoint>): String =
        points.joinToString(" ") { "${format(it.latitude)},${formatLongitude(it.longitude)}" }

    /**
     * The same places, each moved to the position the row would write it at (#354).
     *
     * A course is thinned to its shape before it is stored ([com.example.runningapp.routes.courseOf]),
     * and thinning asks how far a place sits from a line — a question whose answer changes in the
     * last centimetre. So the places are moved to where they will be *kept* before that is asked,
     * and not afterwards: a Run's fixes carry a dozen more decimal places than the row has room for,
     * a file's points arrive already rounded to seven by whatever wrote them, and the same course
     * coming in by those two doors has to thin to the very same line or the library keeps it twice.
     *
     * The heights are left alone. They are not written here — a Route's row keeps no profile — and
     * the climb is banked off them before ever reaching the line ([com.example.runningapp.routes.Course]).
     */
    fun snapped(points: List<RoutePoint>): List<RoutePoint> = points.map { point ->
        RoutePoint(
            latitude = format(point.latitude).toDouble(),
            longitude = formatLongitude(point.longitude).toDouble(),
            elevationMeters = point.elevationMeters,
        )
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.7f", value)

    /**
     * Longitude written the one way this app writes it.
     *
     * 180 and -180 are the same meridian under two names, and a course whose identity is the text of
     * its line cannot hold both: a place a hair west of the antimeridian rounds up to "180.0000000",
     * which is also the name the place a hair east of it goes by. The eastern name is the one kept,
     * the same choice [com.example.runningapp.export.GpxWriter] makes and for the same reason — GPX
     * bounds longitude at 180 exclusive, so a file has no other name available.
     */
    private fun formatLongitude(value: Double): String {
        val formatted = format(value)
        return if (formatted == "180.0000000") "-180.0000000" else formatted
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
