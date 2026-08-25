package com.example.runningapp.routes

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteDao
import com.example.runningapp.data.RouteKeeping
import com.example.runningapp.data.RouteSource
import java.io.IOException

/** What became of a file the runner handed to the library. */
sealed interface RouteImportOutcome {
    data class Imported(val routeId: Long, val name: String) : RouteImportOutcome

    /**
     * The library already held this course, measured exactly as this file measures it.
     *
     * [name] is what the existing Route is called, which may not be what the file is called: a
     * runner who renamed it needs to be told which row is the one they already have.
     */
    data class AlreadySaved(val name: String) : RouteImportOutcome

    /**
     * The library already held this course, and this file measures it differently, so the kept
     * Route now carries the file's numbers.
     *
     * This is the remedy ADR 0014 names for a Route's banked distance and climb: re-importing the
     * file is how a runner reaches them, since nothing re-measures a Route behind their back. The
     * common case is a first export with no `<ele>` in it and a second with heights.
     */
    data class Remeasured(val name: String) : RouteImportOutcome

    /** Nothing was written. See [com.example.runningapp.ui.gpxRefusalMessage] for the words. */
    data class Refused(val reason: GpxRefusal) : RouteImportOutcome
}

/**
 * Turns a GPX file the runner picked — or opened this app with — into a stored Route (#54).
 *
 * One door for both ways in, which is the point of it being here rather than in the screen: the
 * in-app picker and Android's "Open with" hand over the same thing, a `content://` Uri this app has
 * been granted a read of, and a route imported one way must be identical to the same file imported
 * the other.
 *
 * Measuring happens before anything is written, so a file that turns out to be unreadable leaves the
 * library exactly as it was. There is no half-saved Route to find afterwards: a Route is one row.
 *
 * Importing is repeatable: the same course handed over twice is one Route, not two. That is a rule
 * about what a Route is — a course, not an act of importing — and it is also what makes the library
 * safe from Android handing this app the same file a second time without the runner asking. An
 * "Open with" leaves its intent sitting in the task; reopening the app from the recents list days
 * later replays it, and nothing in this app can reach into the system and take it back. So the
 * import is written to be harmless when repeated rather than guarded against being repeated.
 *
 * Repeatable is not inert. A file that draws a course already kept but measures it differently
 * writes its numbers onto that Route, which is what makes re-importing the remedy ADR 0014 says it
 * is for a distance or a climb banked under an older rule. The runner's name for it is never
 * touched: they named the course, not the file.
 */
class RouteImporter(
    private val contentResolver: ContentResolver,
    private val routeDao: RouteDao,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun import(uri: Uri): RouteImportOutcome {
        val outcome = try {
            contentResolver.openInputStream(uri).use { stream ->
                if (stream == null) return RouteImportOutcome.Refused(GpxRefusal.UNREADABLE)
                GpxRouteReader.read(stream)
            }
        } catch (unreadable: IOException) {
            Log.w("RouteImporter", "Could not read the picked file", unreadable)
            return RouteImportOutcome.Refused(GpxRefusal.UNREADABLE)
        } catch (refused: SecurityException) {
            // The read grant has lapsed — an "Open with" Uri the app came back to after being
            // killed, most often. Nothing to do but ask for the file again.
            Log.w("RouteImporter", "No longer allowed to read the picked file", refused)
            return RouteImportOutcome.Refused(GpxRefusal.UNREADABLE)
        }

        val read = when (outcome) {
            is GpxReadOutcome.Refused -> return RouteImportOutcome.Refused(outcome.reason)
            is GpxReadOutcome.Read -> outcome
        }

        // The name is worked out here, before the library is asked anything, even though a file that
        // turns out to be a course already kept will not use it. Asking the provider what the file
        // is called is talk to another app, and it cannot happen with the table's decision held open
        // — so the choice is to ask for a name that is sometimes thrown away, or to ask and write in
        // two goes and let a tap on "Save as route" slip between them. The first costs a cheap local
        // query on the rare occasion someone imports a file they already have; the second costs the
        // runner a second row of the same course, and nothing in the table would tell the two apart.
        val name = routeName(fileSuggested = read.name, fileNamed = displayNameOf(uri))

        // The line is the course's identity, and the library decides in one go what to do with it:
        // keep it, leave the row already holding it alone, or write this file's better numbers onto
        // that row. Whatever comes back names the row the runner has, under whatever they call it.
        val kept = routeDao.keepRoute(
            Route(
                name = name,
                distanceMeters = routeDistanceMeters(read.points),
                elevationGainMeters = routeElevationGainMeters(read.points),
                polyline = RoutePolyline.encode(read.points),
                createdAtMillis = now(),
                source = RouteSource.IMPORTED,
            ),
            remeasuring = true,
        )
        return when (kept.keeping) {
            RouteKeeping.KEPT -> RouteImportOutcome.Imported(routeId = kept.id, name = kept.name)
            RouteKeeping.ALREADY_KEPT -> RouteImportOutcome.AlreadySaved(name = kept.name)
            RouteKeeping.REMEASURED -> RouteImportOutcome.Remeasured(name = kept.name)
        }
    }

    /**
     * What the file is called where the runner keeps it — the picker's display name, which is the
     * only handle they have on it.
     *
     * Null whenever the provider will not say, which several will: a Uri's own last path segment is
     * an opaque document id as often as it is a filename, so guessing from it would produce names
     * like "msf:1000000042" and be worse than falling back.
     */
    private fun displayNameOf(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
    } catch (unavailable: Exception) {
        // A provider that has gone away, or one that refuses this column. A name is a nicety and
        // the import must not fail for want of one.
        Log.w("RouteImporter", "The provider would not name the picked file", unavailable)
        null
    }
}

/** What a newly imported Route is called before the runner renames it. */
private const val UNNAMED_ROUTE = "Imported route"

/**
 * The name a freshly imported Route takes: the file's own, then the file's name on disk, then a
 * plain stand-in (#54).
 *
 * The GPX's `<name>` first because it is the only one the person who made the route chose;
 * `regents-park-loop.gpx` is what a download happened to be called. Neither is trusted to be
 * sensible — a blank name, or a name of nothing but spaces, falls through to the next — and the
 * extension comes off, since a Route is not a file once it is in the library.
 *
 * Internal rather than private: these three rules are what a runner sees at the top of every row,
 * and they are pinned by [com.example.runningapp.routes.RouteNameTest] rather than by importing a
 * file on a phone.
 */
internal fun routeName(fileSuggested: String?, fileNamed: String?): String =
    fileSuggested?.trim()?.takeIf { it.isNotEmpty() }
        ?: fileNamed?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }
        ?: UNNAMED_ROUTE
