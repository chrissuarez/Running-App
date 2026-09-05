package com.example.runningapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.example.runningapp.routes.CourseShape
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.routes.courseRecognising
import com.example.runningapp.routes.routeShapeOf
import kotlinx.coroutines.flow.Flow

/** Where a Route came from. Stored as text on the row, the way a Run stores its own mode. */
object RouteSource {
    /** Read out of a GPX file the runner picked or opened with this app (#54). */
    const val IMPORTED = "imported"

    /**
     * Traced off a Run the runner had already been for (#55).
     *
     * A Route all the same, and not a link back to that Run: the line was copied onto this row and
     * the Run is free to be deleted, which is the same bargain a Segment makes with the Run it was
     * cut from. Recorded because where a course came from is the one thing about it that can never
     * be worked out afterwards.
     */
    const val FROM_RUN = "from_run"
}

/**
 * A course the runner keeps: a line to follow, and what following it costs (#54).
 *
 * Not a Run and never becomes one. A Run is a recording of something that happened, stamped with
 * when it happened and what the runner's heart did; a Route is a plan, which may be run any number
 * of times or never. That is why deleting one touches nothing else — there is no key from here into
 * `sessions` and none from `sessions` into here, so the library can be emptied without a single Run
 * being disturbed.
 *
 * [distanceMeters] and [elevationGainMeters] are worked out once, at import, and banked — unlike a
 * Run's numbers, which are re-measured off the stored track every time its page is opened, so a
 * change to how the app measures reaches history. A Run can be re-measured because a Run keeps its
 * evidence; an imported Route's heights are bare numbers from a file that is already gone, and no
 * better rule could ever be applied to them
 * ([ADR 0014](../../../../../../../docs/adr/0014-a-route-is-a-plan-not-a-recording.md)).
 */
@Entity(tableName = "routes")
data class Route(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The runner's name for it: the GPX's own to begin with, and theirs after a rename. */
    val name: String,
    val distanceMeters: Double,
    /**
     * How much climbing the course holds, or null when the file carried no heights.
     *
     * Null is not zero. A flat route climbs nothing; a file with no `<ele>` in it says nothing about
     * a route that may well go over a hill, and the screen says as much rather than printing a nought.
     */
    val elevationGainMeters: Double? = null,
    /**
     * The course itself — see [com.example.runningapp.routes.RoutePolyline] for the writing of it.
     *
     * **This is the one column on the row that can be big, and the two rules about it are stated
     * here** (#403). Everything that reads a Route cites them from here rather than arguing them
     * again: [RouteDao.getLibraryFlow], [RouteDao.getRoutePolyline],
     * [com.example.runningapp.routes.libraryRedrawn] and `RoutesViewModel`.
     *
     * How big: a line kept before #354 holds every point its file held, and a file may hold two
     * hundred thousand of them — some four megabytes of text in one row. A line written since is
     * thinned, but thinned to twenty thousand places is still four hundred thousand characters. So a
     * library of many high-detail courses is tens of megabytes, all of it in this column.
     *
     * **One: no reader may hold two lines at once.** A line is fetched when it is about to be used
     * and let go before the next one is, and what is kept from it is bounded — a thumbnail, a
     * digest, a handful of numbers, never the text. That is why nothing that *lists* Routes selects
     * this column at all. Broken, it is broken in the upgrade first, and an upgrade that runs out of
     * memory rolls back and is tried again at every launch: an app that never opens.
     *
     * **Two: it is written once, when the row is inserted, and never again while the app is
     * running.** Nothing in [RouteDao] updates it — [RouteDao.remeasureRoute] writes the two numbers
     * and [RouteDao.renameRoute] the name, and a re-import that finds a Route already kept found it
     * *by this very text* ([RouteDao.findRouteByPolyline]), so what it would write back is what is
     * already there. The one thing that ever rewrites a line is the upgrade at `MIGRATION_41_42`,
     * which runs before the database is opened for reading. `RouteLineIsWrittenOnceTest` keeps this
     * rule rather than leaving it to be remembered.
     *
     * The two together are what lets a reader list rows without their lines and fetch one by id
     * later, knowing the row it gets is the row it listed; and lets anything worked out from a line
     * be kept against the Route's id alone.
     */
    val polyline: String,
    val createdAtMillis: Long,
    /** One of [RouteSource]. Text rather than a number so a row still reads plainly in a backup. */
    val source: String,
    /**
     * The family this course belongs to, or null for a course that belongs to none (#421).
     *
     * **A family is a name the runner typed, and nothing cleverer.** Two Routes are siblings because
     * they carry the very same text here — never because their names look alike. Guessing a group
     * from lookalike names would split what the runner meant and join what they did not, and a wrong
     * guess is worse than none, so no such guess is made anywhere.
     *
     * Each sibling is a whole course in its own right: its own line, its own honest distance and
     * climb, its own remembered Runs. Nothing here is a parent, a child, or a truncation of another
     * sibling — the 8 km version is a real line drawn over the 8 km of ground, which is the only way
     * its climb and its map can be true of it.
     *
     * Stored on the row rather than in a table of its own for the same reason [source] is: it is one
     * short piece of text about this course, and a second table would buy nothing but a join and a
     * second place for the truth to live.
     *
     * Null and blank are the same thing — no family — and the writer is the one that settles it
     * ([RouteDao.setRouteFamily]), so nothing that reads this column has to know that twice.
     */
    val family: String? = null,
)

/**
 * One Route as a list of them shows it: the whole row except the line (#403).
 *
 * The library screen and the pre-run picker both draw a name and two numbers, and neither has any
 * use for the course itself — so neither is handed it. See [RouteDao.getLibraryFlow] for why that
 * matters and [RouteDao.getRoutePolyline] for how the line is fetched when one is wanted.
 */
data class RouteHeader(
    val id: Long,
    val name: String,
    val distanceMeters: Double,
    val elevationGainMeters: Double?,
    val createdAtMillis: Long,
    val source: String,
    /** The family the course was put in, or null — see [Route.family] (#421). */
    val family: String? = null,
)

/** What keeping a course came to: the four things that can become of one line offered to the table. */
enum class RouteKeeping {
    /** The library had nothing drawn along this line, so a new Route now holds it. */
    KEPT,

    /** The library already held this line, and nothing was written. */
    ALREADY_KEPT,

    /**
     * The library already held this line, and the caller's numbers were written onto it.
     *
     * Only ever the answer to a caller that asked to re-measure — see [RouteDao.keepRoute].
     */
    REMEASURED,

    /**
     * The library already held this line, and the caller's distance was written onto it while the
     * climb already banked was left standing, because the caller carried no heights (#355).
     *
     * A file with no `<ele>` in it says nothing about climb. It does not say the course is flat, and
     * it does not say that what an earlier file said about the same course was wrong — so it is not
     * allowed to take that answer away. Told apart from [REMEASURED] because the screen has to say
     * which numbers moved, and here only one of them did.
     */
    REMEASURED_KEEPING_CLIMB,
}

/**
 * The Route the library holds for one line, and what holding it cost.
 *
 * The answer [RouteDao.keepRoute] gives, and the reason it is one answer rather than two: the caller
 * has to be able to tell "kept" from "you already have this" without asking a second question, since
 * asking again is exactly the gap this closes.
 *
 * [name] is the kept row's name, which for a course already held is the runner's name for it and not
 * whatever the caller was about to call it.
 */
data class KeptRoute(
    val id: Long,
    val name: String,
    val keeping: RouteKeeping,
    /**
     * Another course the library already holds over this very ground, or null where there is none
     * (#402).
     *
     * **Only ever set where a row was just written**, because that is the only way the library ends
     * up holding one piece of ground twice. A file whose line the library already had is that same
     * row, not a second one, and nothing about it is worth reporting.
     *
     * It is a *report*, not a decision. The line stays the one identity a Route has
     * ([findRouteByPolyline], ADR 0014) and nothing is merged, refused or hidden — the runner is
     * told what they now have, and settles it themselves. That is the whole of the remedy #402
     * settled on, and the reading
     * [com.example.runningapp.routes.courseRecognising] already assumed: two rows over one piece of
     * ground is a library the app may describe and must not tidy.
     *
     * Recognised by the shapes ([RouteShapeRow]), by the same rule a course's page recognises its
     * Runs with — so a lookalike named here is a course the two rows' pages would both claim.
     * Where more than one fits, the closest in length is named
     * ([com.example.runningapp.routes.courseRecognising]): the runner is owed one name rather than
     * a list.
     */
    val sameGroundAs: String? = null,
)

@Dao
interface RouteDao {

    /**
     * The library, newest first — the order a runner who has just imported one expects.
     *
     * Every row **except its line**, because no reader may hold two lines at once and a list is
     * every line at once — the rule and its size are at [Route.polyline] (#403). `SELECT *` here
     * would hold the whole library's text for as long as the screen is open.
     *
     * Nothing that lists Routes needs a line anyway. The library screen draws a name, two numbers
     * and a thumbnail; the pre-run picker draws a name and two numbers. The line is read one course
     * at a time, by whatever actually needs it ([getRoutePolyline], [getRoute], [getRouteFlow]).
     */
    @Query(
        "SELECT id, name, distanceMeters, elevationGainMeters, createdAtMillis, source, family " +
            "FROM routes ORDER BY createdAtMillis DESC, id DESC"
    )
    fun getLibraryFlow(): Flow<List<RouteHeader>>

    /**
     * One course's line, on its own (#403).
     *
     * The other half of [getLibraryFlow]: a reader lists the library without its lines and then asks
     * for one line at a time, which is how it keeps [Route.polyline]'s first rule. Null is the row
     * having gone since it was listed, which is an ordinary thing — the runner can delete a course
     * while the list is on screen.
     */
    @Query("SELECT polyline FROM routes WHERE id = :routeId")
    suspend fun getRoutePolyline(routeId: Long): String?

    /**
     * One course as its own page shows it: the row without its line, watched (#420).
     *
     * The other half of [getRoutePolyline], and the pair is how the detail page keeps
     * [Route.polyline]'s first rule while still drawing the course. The name and the two numbers are
     * watched, because a rename made on that very page has to reach its title; the line is fetched
     * once and separately, because [Route.polyline]'s second rule says it never changes, so
     * re-delivering it on every rename would be carrying the biggest thing on the row about for the
     * sake of the smallest.
     *
     * Null is the row having gone — deleted from the library on another screen while this one is
     * open — which the page draws as nothing rather than as a course.
     */
    @Query(
        "SELECT id, name, distanceMeters, elevationGainMeters, createdAtMillis, source, family " +
            "FROM routes WHERE id = :routeId"
    )
    fun getRouteHeaderFlow(routeId: Long): Flow<RouteHeader?>

    @Insert
    suspend fun insertRoute(route: Route): Long

    /**
     * The Route the library already holds for this identical line, if it holds one.
     *
     * The line itself is the identity: a file drawing the very same points is the Route already
     * kept, whatever it or the runner calls it. Identical, not merely alike — the same course
     * re-exported by another app, simplified differently, is a different line and becomes its own
     * Route. This is what stops one file becoming two rows — see
     * [com.example.runningapp.routes.RouteImporter].
     */
    @Query("SELECT * FROM routes WHERE polyline = :polyline ORDER BY id LIMIT 1")
    suspend fun findRouteByPolyline(polyline: String): Route?

    /**
     * Keeps [route], unless the library already holds a Route drawn along this very line, and says
     * which of the three things happened.
     *
     * The one way into this table, and the reason it is one way rather than two. The looking and the
     * writing are one operation because they are one decision. Two taps on "Save as route" are two
     * coroutines, and asked separately they can both look before either writes: both find nothing,
     * both write, and the library ends up holding the same course twice with nothing in the table to
     * tell the copies apart. In one transaction the second tap cannot look until the first has
     * finished writing, so it sees the row and is sent back to it.
     *
     * The same is true across the two writers, and that is why the GPX importer comes through here
     * as well rather than keeping a lookup-then-insert of its own. A promise about the table that
     * only one of its writers keeps is not a promise about the table: an import that had looked and
     * found nothing could still be deciding while a tap on "Save as route" wrote the same line, and
     * then write it again.
     *
     * [remeasuring] is the whole of the difference between the two callers, so it is a parameter
     * rather than a second method. A GPX arriving for a course already kept may measure it better
     * than the file before it did — that is the remedy ADR 0014 names for a banked distance or climb
     * — so the importer asks for those numbers to be written on ([RouteKeeping.REMEASURED]). A Run
     * has nothing to offer: measured twice by the same rules off the same fixes it can only ever say
     * what it said the first time, so the saver asks for the row to be left exactly as it is.
     *
     * The column itself is left without a unique constraint on purpose. That would be the same
     * promise made in a second place, and it would refuse the importer's re-measure outright rather
     * than let it write ([com.example.runningapp.routes.RouteImporter]).
     */
    @Transaction
    suspend fun keepRoute(route: Route, remeasuring: Boolean): KeptRoute {
        findRouteByPolyline(route.polyline)?.let { alreadyHeld ->
            // A caller carrying no heights is silent about climb, not authoritative about it
            // (#355), so the banked answer stands rather than being written over with null.
            // Everything below reads this rather than the caller's own field: what the row would end
            // up holding is what decides both whether anything moved and what the runner is told
            // moved.
            val climb = route.elevationGainMeters ?: alreadyHeld.elevationGainMeters
            val measuresTheSame = route.distanceMeters == alreadyHeld.distanceMeters &&
                climb == alreadyHeld.elevationGainMeters
            if (!remeasuring || measuresTheSame) {
                rememberTheShapeOf(alreadyHeld.id, route.polyline)
                return KeptRoute(alreadyHeld.id, alreadyHeld.name, RouteKeeping.ALREADY_KEPT)
            }
            remeasureRoute(alreadyHeld.id, route.distanceMeters, climb)
            // Both halves, because the screen is told that a climb was *kept*: a caller with no
            // heights arriving at a row that never had a climb keeps nothing, and saying "its climb
            // is unchanged" about a climb that does not exist would be a sentence about nothing.
            val climbWasKept =
                route.elevationGainMeters == null && alreadyHeld.elevationGainMeters != null
            val keeping = if (climbWasKept) {
                RouteKeeping.REMEASURED_KEEPING_CLIMB
            } else {
                RouteKeeping.REMEASURED
            }
            rememberTheShapeOf(alreadyHeld.id, route.polyline)
            return KeptRoute(alreadyHeld.id, alreadyHeld.name, keeping)
        }
        val routeId = insertRoute(route)
        rememberTheShapeOf(routeId, route.polyline)
        return KeptRoute(
            routeId,
            route.name,
            RouteKeeping.KEPT,
            sameGroundAs = courseAlreadyOverThisGround(routeId)?.name,
        )
    }

    /**
     * Measures the line a course is being kept along, in the same transaction as the keeping (#74).
     *
     * **On every way out of [keepRoute], not only on the one that inserts a row.** A course the
     * library already held may have been kept before shapes existed at all, and a re-import is one
     * of the few things that reaches it; leaving that path unshaped would make the backfill pass the
     * only thing that could ever fill it in, which is a rule kept in one place depending on a rule
     * kept in another. Writing it here costs a decode of a line already in hand, and the row is
     * replaced rather than added to, so a course already measured is measured to the same answer:
     * a Route's line is written once and never rewritten ([Route.polyline]).
     *
     * In the transaction rather than after it, so no course ever exists without a shape. A gap
     * between the two would be a window in which a Run's page could ask the library which course it
     * was on and be told "none" about the very course just saved — precisely the case #74 closes.
     *
     * On this DAO rather than in [RouteShapeDao] because Room will only join two writes into one
     * transaction where they are on one DAO. Everything that *reads* shapes reads them there;
     * nothing else writes them but the pass that pays the backfill
     * ([com.example.runningapp.routes.RouteShaping]).
     */
    suspend fun rememberTheShapeOf(routeId: Long, polyline: String) =
        insertRouteShape(routeShapeRowOf(routeId, routeShapeOf(RoutePolyline.decode(polyline))))

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRouteShape(shape: RouteShapeRow)

    /**
     * A course the library already held over the ground [routeId] was just written for, or null
     * (#402).
     *
     * The residue #354 and #399 could not reach. A row saved from a Run before #354 was thinned from
     * places that had not been snapped first, and thinning only removes — so that row's line can no
     * longer be drawn from its own Run's GPX, [findRouteByPolyline] misses it, and handing that GPX
     * back writes a second row over the same ground under the same name.
     *
     * **This does not close that door; it puts a light above it.** The alternatives were both worse:
     * matching the old encoding as well would be a second permanent way of saying "the same course",
     * which ADR 0014 argues against, and redrawing the row from its Run's track needs a link from a
     * Route back to a Run that the table has never held. So the runner is told, in the words of the
     * screen they are looking at, and deletes whichever row they do not want. Nothing is merged
     * behind them: which of two courses over one piece of ground is the real one is the runner's
     * call, and it is the very call [com.example.runningapp.routes.courseRecognising] declines to
     * make on their behalf.
     *
     * Read inside [keepRoute]'s own transaction, off the shape written a line above — so what it
     * compares against is the library as it stood when the row landed, not as it stands after
     * whoever wrote next.
     *
     * Null for a course with no shape at all: a line too short to hold one
     * ([com.example.runningapp.routes.routeShapeOf]) has no ground to be recognised on.
     */
    suspend fun courseAlreadyOverThisGround(routeId: Long): CourseShape? {
        val courses = shapedCourses()
        val kept = courses.firstOrNull { it.routeId == routeId }?.decoded() ?: return null
        return courseRecognising(
            kept,
            courses.filter { it.routeId != routeId }.mapNotNull { it.asCourseShape() },
        )
    }

    /**
     * Every course the library holds a shape of — what a course just written is compared against
     * (#402).
     *
     * [SHAPED_COURSES_SQL] itself rather than a second query saying the same thing, so what counts
     * as a shaped course is one answer whoever is asking. On this DAO rather than in
     * [RouteShapeDao] for [rememberTheShapeOf]'s reason: Room will only put two statements in one
     * transaction where they sit on one DAO, and this one has to run inside [keepRoute]'s.
     *
     * Every course rather than the matching one, because the matching is a geometry rule kept in
     * one place ([com.example.runningapp.routes.runIsOnCourse]) and SQL cannot ask it. The rows are
     * five places and a number each, never a line.
     */
    @Query(SHAPED_COURSES_SQL)
    suspend fun shapedCourses(): List<RouteShapeCandidate>

    /**
     * Writes a re-read of the same line's distance and climb onto the Route already kept.
     *
     * The name is left alone: it is the runner's, not the file's. See ADR 0014 — a Route's numbers
     * are banked at import and re-importing is the only thing that revisits them.
     *
     * Reached through [keepRoute] rather than called on its own, because deciding that a line is
     * already held and writing better numbers onto it are the same decision, and anything that comes
     * between the two is a second row waiting to happen.
     */
    @Query(
        "UPDATE routes SET distanceMeters = :distanceMeters, " +
            "elevationGainMeters = :elevationGainMeters WHERE id = :routeId"
    )
    suspend fun remeasureRoute(routeId: Long, distanceMeters: Double, elevationGainMeters: Double?)

    @Query("SELECT * FROM routes WHERE id = :routeId")
    suspend fun getRoute(routeId: Long): Route?

    /**
     * One Route as it stands, and as it stands again every time the table moves under it.
     *
     * Watched rather than read for one reader: the map of a Run that is still going
     * ([com.example.runningapp.data.SessionRepository.routeLineForRunFlow]). The library stays the
     * runner's to edit while they are out on a course, so the row can be deleted mid-Run — and the
     * promise made where deleting is offered is that it costs the Run nothing. A reading taken once
     * cannot keep that promise: the Run's own row never changes, so nothing would ask again, and the
     * map would go on drawing a course the library no longer holds.
     *
     * Null is the row not being there, which is the answer that matters here.
     */
    @Query("SELECT * FROM routes WHERE id = :routeId")
    fun getRouteFlow(routeId: Long): Flow<Route?>

    @Query("UPDATE routes SET name = :name WHERE id = :routeId")
    suspend fun renameRoute(routeId: Long, name: String)

    /**
     * Puts a course into a family, or takes it out of one (#421).
     *
     * The only writer of [Route.family], and it is where blank is settled into null so that every
     * reader below can ask one question — is this column null? — instead of two. A runner who clears
     * the box has taken the course out of its family, and a row holding an empty string would be a
     * family whose name is nothing: it would collect every other course cleared the same way.
     *
     * Not folded into [renameRoute]. The two are different edits made at different moments — a
     * course is renamed once and re-familied as the runner's plans grow — and one method writing
     * both would make each of them carry the other's value about.
     */
    @Transaction
    suspend fun setRouteFamily(routeId: Long, family: String?) {
        writeRouteFamily(routeId, family?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** The write [setRouteFamily] makes, once it has settled blank into null. */
    @Query("UPDATE routes SET family = :family WHERE id = :routeId")
    suspend fun writeRouteFamily(routeId: Long, family: String?)

    @Query("DELETE FROM routes WHERE id = :routeId")
    suspend fun deleteRoute(routeId: Long)
}
