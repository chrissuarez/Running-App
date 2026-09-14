package com.example.runningapp.routes

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.runningapp.data.RouteDao
import com.example.runningapp.data.RouteSource
import java.io.IOException

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
 * Activity is handed its launch intent again on every recreation, which a change of text size is
 * enough to cause. So the import is written to be harmless when repeated rather than guarded
 * against being repeated.
 *
 * It used to have to be harmless across a reopen from the recents list too. It is not any more:
 * [com.example.runningapp.routes.RouteFileLaunch] says why. Nothing here changed for it — repeatable
 * is still the rule — but the repeat that reached furthest no longer happens (#277).
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

    suspend fun import(uri: Uri): RouteOutcome {
        val outcome = try {
            contentResolver.openInputStream(uri).use { stream ->
                if (stream == null) return RouteOutcome.FileRefused(GpxRefusal.UNREADABLE)
                GpxRouteReader.read(stream)
            }
        } catch (unreadable: IOException) {
            Log.w("RouteImporter", "Could not read the picked file", unreadable)
            return RouteOutcome.FileRefused(GpxRefusal.UNREADABLE)
        } catch (refused: SecurityException) {
            // The read grant has lapsed — an "Open with" Uri the app came back to after being
            // killed, most often. Nothing to do but ask for the file again.
            Log.w("RouteImporter", "No longer allowed to read the picked file", refused)
            return RouteOutcome.FileRefused(GpxRefusal.UNREADABLE)
        }

        val read = when (outcome) {
            is GpxReadOutcome.Refused -> return RouteOutcome.FileRefused(outcome.reason)
            is GpxReadOutcome.Read -> outcome
        }

        // The file's places become a course by the one rule every Route is stored under, so a file
        // shared from a Run of this app's own draws the very line that Run's page would save (#354)
        // — see [courseOf], where what that costs the file's own points is argued.
        val course = courseOf(read.points)

        // A file can be readable, real, and still not a course: one place, the same place over and
        // over, or a scatter inside the width of a fix's own error. The Run door has always turned
        // that away and the file door did not, which left the library holding rows no Run could be
        // started on (#397). One test for both doors, so what counts as a course cannot drift apart
        // the way the line itself once did — see [holdsACourse].
        //
        // Only what arrives from here on. A row already written this way is left standing rather
        // than swept up: deleting a runner's row behind their back is a thing this app does not do,
        // and the row is harmless — [CourseLine.of] refuses it, so no Run can be started on it, and
        // it draws as a course with no shape. Handing the same file over again now refuses it, and
        // the runner can delete the row themselves.
        if (!course.holdsACourse()) return RouteOutcome.NoGround

        // The name is worked out here, after the file has been found to hold a course and before the
        // library is asked anything, even though a file that turns out to be a course already kept
        // will not use it. The argument for asking early is unchanged and is below; asking after the
        // gate rather than before it only spares a refused file a question about a name nothing will
        // ever use. Asking the provider what the file is called is talk to another app, and it
        // cannot happen with the table's decision held open — so the choice is to ask for a name that
        // is sometimes thrown away, or to ask and write in two goes and let a tap on "Save as route"
        // slip between them. The first costs a cheap local query on the rare occasion someone imports
        // a file they already have; the second costs the runner a second row of the same course, and
        // nothing in the table would tell the two apart.
        val name = routeName(fileSuggested = read.name, fileNamed = displayNameOf(uri))

        // The line is the course's identity, and the library decides in one go what to do with it:
        // keep it, leave the row already holding it alone, or write this file's better numbers onto
        // that row. Whatever comes back names the row the runner has, under whatever they call it,
        // and is handed on as it is rather than translated (#480).
        val kept = routeDao.keepRoute(
            course.asRoute(name, createdAtMillis = now(), source = RouteSource.IMPORTED),
            remeasuring = true,
        )
        return RouteOutcome.Kept(kept)
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
