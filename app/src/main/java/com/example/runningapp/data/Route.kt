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
    /** The course itself — see [com.example.runningapp.routes.RoutePolyline] for the writing of it. */
    val polyline: String,
    val createdAtMillis: Long,
    /** One of [RouteSource]. Text rather than a number so a row still reads plainly in a backup. */
    val source: String,
)

/** What keeping a course came to: the three things that can become of one line offered to the table. */
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

    /** The library, newest first — the order a runner who has just imported one expects. */
    @Query("SELECT * FROM routes ORDER BY createdAtMillis DESC, id DESC")
    fun getAllRoutesFlow(): Flow<List<Route>>

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
            val measuresTheSame = route.distanceMeters == alreadyHeld.distanceMeters &&
                route.elevationGainMeters == alreadyHeld.elevationGainMeters
            if (!remeasuring || measuresTheSame) {
                return KeptRoute(alreadyHeld.id, alreadyHeld.name, RouteKeeping.ALREADY_KEPT)
            }
            remeasureRoute(alreadyHeld.id, route.distanceMeters, route.elevationGainMeters)
            return KeptRoute(alreadyHeld.id, alreadyHeld.name, RouteKeeping.REMEASURED)
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
