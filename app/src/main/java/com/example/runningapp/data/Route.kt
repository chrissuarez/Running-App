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

/**
 * The Route the library holds for one line, and whether it was already holding it.
 *
 * The answer [RouteDao.keepRoute] gives, and the reason it is one answer rather than two: the caller
 * has to be able to tell "kept" from "you already have this" without asking a second question, since
 * asking again is exactly the gap this closes.
 *
 * [name] is the kept row's name, which for a course already held is the runner's name for it and not
 * whatever the caller was about to call it.
 */
data class KeptRoute(val id: Long, val name: String, val alreadyKept: Boolean)

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
     * which of the two happened.
     *
     * The looking and the writing are one operation because they are one decision. Two taps on
     * "Save as route" are two coroutines, and asked separately they can both look before either
     * writes: both find nothing, both write, and the library ends up holding the same course twice
     * with nothing in the table to tell the copies apart. In one transaction the second tap cannot
     * look until the first has finished writing, so it sees the row and is sent back to it.
     *
     * The column itself is left without a unique constraint on purpose. That would be the same
     * promise made in a second place, and it would make the promise to the GPX importer too — which
     * re-measures a line it already holds rather than refusing it, and would then be refused by the
     * database instead ([com.example.runningapp.routes.RouteImporter]).
     */
    @Transaction
    suspend fun keepRoute(route: Route): KeptRoute {
        findRouteByPolyline(route.polyline)?.let { alreadyHeld ->
            return KeptRoute(id = alreadyHeld.id, name = alreadyHeld.name, alreadyKept = true)
        }
        return KeptRoute(id = insertRoute(route), name = route.name, alreadyKept = false)
    }

    /**
     * Writes a re-read of the same line's distance and climb onto the Route already kept.
     *
     * The name is left alone: it is the runner's, not the file's. See ADR 0014 — a Route's numbers
     * are banked at import and re-importing is the only thing that revisits them.
     */
    @Query(
        "UPDATE routes SET distanceMeters = :distanceMeters, " +
            "elevationGainMeters = :elevationGainMeters WHERE id = :routeId"
    )
    suspend fun remeasureRoute(routeId: Long, distanceMeters: Double, elevationGainMeters: Double?)

    @Query("SELECT * FROM routes WHERE id = :routeId")
    suspend fun getRoute(routeId: Long): Route?

    @Query("UPDATE routes SET name = :name WHERE id = :routeId")
    suspend fun renameRoute(routeId: Long, name: String)

    @Query("DELETE FROM routes WHERE id = :routeId")
    suspend fun deleteRoute(routeId: Long)
}
