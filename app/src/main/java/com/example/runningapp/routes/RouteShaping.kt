package com.example.runningapp.routes

import android.util.Log
import com.example.runningapp.data.RouteShapeRow
import com.example.runningapp.data.routeShapeRowOf

/**
 * Everything the shaping below reads and writes — the database as this pass needs it.
 *
 * An interface for [com.example.runningapp.segments.RunShapeStore]'s reason: these three operations
 * are one thing, and they are the whole of what a test has to stand up to check the ordering and the
 * one-line-at-a-time rule on a laptop.
 */
interface RouteShapeStore {
    /** The courses nobody has taken the shape of — every debt, oldest first. */
    suspend fun coursesMissingShapes(): List<Long>

    /** One course's line as it is stored, or null where the course has gone. */
    suspend fun line(routeId: Long): String?

    suspend fun putShape(row: RouteShapeRow)
}

/**
 * Taking the shape of saved courses, so Runs can be recognised on them (#74).
 *
 * One door, two occasions: a course just kept, and the whole library at the first launch after this
 * shipped. Both are the same measurement, so they are the same code and cannot drift into disagreeing
 * about what a course is.
 *
 * Nothing here links anything. The link is worked out on read, off the shapes this leaves behind
 * ([courseRecognising]) — which is what makes a course saved today claim the Runs that already fit it,
 * and a course deleted stop claiming them, with nothing to mend either time.
 *
 * **Simpler than a Run's shaping, and the reason is a rule rather than an oversight.** A Run's shape
 * is abandoned where the Run moved under the measurement, because a runner can mark a Run a Walk
 * while its track is being read and that flips the answer. A Route's line is written once when the
 * row is inserted and never rewritten ([com.example.runningapp.data.Route.polyline]), so nothing can
 * move under this one: the line read is the line the row holds, then and afterwards. A rename or a
 * re-measure moves no point of it, and a course deleted takes its shape with it through the row's own
 * cascade.
 *
 * **A shape is written even where there is none to take**, as a row saying so
 * ([com.example.runningapp.data.RouteShapeRow]) — that empty write is why a course too short to hold
 * a route is not read out of the library again at every launch for the rest of its life.
 *
 * One line is held at a time and let go before the next is fetched, which is
 * [com.example.runningapp.data.Route.polyline]'s first rule. It is why this pass exists at all: the
 * five places it leaves behind are what everything else asks the library, so no reader after it has
 * to touch a line.
 */
class RouteShaping(private val store: RouteShapeStore) {

    /**
     * Takes one course's shape and writes it down — what a course gets the moment it is kept.
     *
     * A course that has gone is written as nothing at all: there is no row left to hang a shape on,
     * and the foreign key would refuse it.
     */
    suspend fun shapeCourse(routeId: Long) {
        val line = store.line(routeId) ?: return
        val shape = routeShapeOf(RoutePolyline.decode(line))
        store.putShape(routeShapeRowOf(routeId, shape))
        Log.d(TAG, "Course $routeId " + (shape?.let { "covers %.0f m".format(it.distanceMeters) } ?: "holds no shape"))
    }

    /**
     * Takes the shape of every course that has never had one — the launch pass, and the backfill over
     * the whole library (#74).
     *
     * On the launch this shipped that is every course the runner has ever kept, which is the point: a
     * route page that opens empty is exactly what this ticket exists to fill, and a library shaped
     * only from now on would leave every course already in it opening empty for ever.
     *
     * Each course's row is written as it is measured, so a pass cut short by a process being
     * reclaimed keeps everything it has already done and the next launch takes up the rest.
     */
    suspend fun payWhatIsOwed() {
        val owed = store.coursesMissingShapes()
        owed.forEach { shapeCourse(it) }
        if (owed.isNotEmpty()) Log.d(TAG, "Took the shape of ${owed.size} course(s)")
    }

    private companion object {
        const val TAG = "RouteShapes"
    }
}
