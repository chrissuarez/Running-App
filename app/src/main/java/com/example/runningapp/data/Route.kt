package com.example.runningapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Where a Route came from. Stored as text on the row, the way a Run stores its own mode. */
object RouteSource {
    /** Read out of a GPX file the runner picked or opened with this app (#54). */
    const val IMPORTED = "imported"
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
