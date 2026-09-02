package com.example.runningapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
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
data class KeptRoute(val id: Long, val name: String, val keeping: RouteKeeping)

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
        "SELECT id, name, distanceMeters, elevationGainMeters, createdAtMillis, source FROM routes " +
            "ORDER BY createdAtMillis DESC, id DESC"
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
        "SELECT id, name, distanceMeters, elevationGainMeters, createdAtMillis, source FROM routes " +
            "WHERE id = :routeId"
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
            return KeptRoute(alreadyHeld.id, alreadyHeld.name, keeping)
        }
        return KeptRoute(insertRoute(route), route.name, RouteKeeping.KEPT)
    }

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

    @Query("DELETE FROM routes WHERE id = :routeId")
    suspend fun deleteRoute(routeId: Long)
}
