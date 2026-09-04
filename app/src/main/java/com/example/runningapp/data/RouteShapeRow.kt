package com.example.runningapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.runningapp.analysis.MapFix
import com.example.runningapp.routes.CourseShape
import com.example.runningapp.routes.RoutePoint
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.segments.RUN_SHAPE_WAYPOINTS
import com.example.runningapp.segments.RunShape
import kotlinx.coroutines.flow.Flow

/**
 * The few places that say where one saved course goes (#74), banked.
 *
 * [RunShapeRow]'s bargain, on the other side of the same match. A course's line is the one big column
 * in the app — a library of high-detail courses is tens of megabytes of it, and no reader may hold two
 * lines at once ([Route.polyline]) — while the shape a match is decided on is five places and a
 * number. Banking the shape is what lets anything ask "which course is this Run on?" of the whole
 * library at once, which reading the lines could never be allowed to do.
 *
 * **The row's absence is the debt**, exactly as `run_shapes`: every course kept before this shipped
 * is owed a shape at the next launch ([RouteShapeDao.getRouteIdsMissingShapes]), and a course kept
 * afterwards is measured as it is kept.
 *
 * Nothing ever invalidates one. A Route's line is written once when the row is inserted and never
 * rewritten ([Route.polyline]), so a shape taken from it is that course's shape for as long as the
 * row exists — unlike a Run's, which is thrown away and taken again whenever the runner's word about
 * the Run changes. A re-measure ([RouteDao.remeasureRoute]) writes the two banked numbers off that
 * very same line and moves no point of it.
 *
 * [shape] is null for a course there is no shape to take of: fewer than two points, or a line under
 * the matching's own floor ([com.example.runningapp.segments.RUN_SHAPE_MINIMUM_METERS]). Null is not
 * an absent row — the row existing is what says this course has been measured — so such a course is
 * measured once and passed over at every launch afterwards.
 */
@Entity(
    tableName = "route_shapes",
    foreignKeys = [
        ForeignKey(
            entity = Route::class,
            parentColumns = ["id"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RouteShapeRow(
    /**
     * The course this is the shape of, and the key itself: a course has one shape, and a second
     * reading of the same line replaces the first rather than standing beside it.
     */
    @PrimaryKey val routeId: Long,
    /** The waypoints, written the way a Route's line and a Run's shape are ([RoutePolyline]). */
    val shape: String?,
    /** How far the course goes, as its own legs counted it. Zero where there is no shape. */
    val distanceMeters: Double,
)

/**
 * One saved course a Run could be recognised on: its shape, and the name the runner would be shown
 * (#74).
 *
 * The name travels with the shape because naming the course is the whole of what the card does with
 * it, and fetching the name afterwards would be a second read of a library that may have changed in
 * between — a Run's page would then name a course by an id nothing answers to.
 */
data class RouteShapeCandidate(
    val routeId: Long,
    val name: String,
    val shape: String,
    val distanceMeters: Double,
)

@Dao
interface RouteShapeDao {

    /**
     * Every saved course that holds a shape — the whole library a Run is recognised against (#74).
     *
     * Watched, because a Run's page names the course it is on and that name moves under an open page:
     * a course imported, renamed, or deleted while the runner is reading a Run changes what that Run
     * should be saying about itself.
     *
     * Every shaped course rather than only the matching one, for [RunShapeDao.getShapedRunsFlow]'s
     * reason: the matching is a geometry rule kept in one place
     * ([com.example.runningapp.routes.runIsOnCourse]) and SQL cannot ask it. The rows are five places
     * and a number each, so this is a few kilobytes for the largest library a runner would keep — and
     * it is the reason this table exists rather than the lines being read ([Route.polyline]).
     */
    @Query(SHAPED_COURSES_SQL)
    fun getShapedCoursesFlow(): Flow<List<RouteShapeCandidate>>

    /** One course's shape, watched — what its own page recognises its Runs with. */
    @Query(ONE_COURSE_SHAPE_SQL)
    fun getCourseShapeFlow(routeId: Long): Flow<RouteShapeCandidate?>

    /**
     * The courses nobody has taken the shape of — every debt, oldest first.
     *
     * The absence of a row is the debt ([RouteShapeRow]). Oldest first so a library being swept for
     * the first time fills in the order the runner kept them.
     */
    @Query("SELECT id FROM routes WHERE id NOT IN (SELECT routeId FROM route_shapes) ORDER BY id ASC")
    suspend fun getRouteIdsMissingShapes(): List<Long>

    /** Writes what one course's shape is, over whatever an earlier reading of the same line said. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putShape(shape: RouteShapeRow)
}

/**
 * The read behind [RouteShapeDao.getShapedCoursesFlow], named so a test can put it to a real SQLite
 * database rather than to a hand-written stand-in that agrees with it by luck (#74).
 *
 * A `const` for [SHAPED_RUNS_SQL]'s reason, and the delete case is the sharper half here too: a
 * course leaving the library takes its Runs' name off them, and that is a promise the *schema* keeps
 * ([RouteShapeRow]'s cascade), which no fake DAO can be asked about at all.
 *
 * The name is read from `routes` rather than copied here, so a rename reaches every Run that names
 * this course without a single shape being taken again.
 */
const val SHAPED_COURSES_SQL: String =
    """
        SELECT r.id AS routeId,
               r.name AS name,
               c.shape AS shape,
               c.distanceMeters AS distanceMeters
        FROM route_shapes c
        JOIN routes r ON r.id = c.routeId
        WHERE c.shape IS NOT NULL
    """

/**
 * The same read, narrowed to one course — what that course's own page recognises its Runs with.
 *
 * Concatenated onto [SHAPED_COURSES_SQL] rather than written out, so the page and the library cannot
 * come to hold two different ideas of what a shaped course is.
 */
const val ONE_COURSE_SHAPE_SQL: String = SHAPED_COURSES_SQL + " AND r.id = :routeId"

/** The shape a candidate row holds, or null where what it holds is not a whole shape. */
fun RouteShapeCandidate.decoded(): RunShape? = decodeCourseShape(shape, distanceMeters)

/** The course as the recognising asks about it, or null where its row holds no whole shape. */
fun RouteShapeCandidate.asCourseShape(): CourseShape? =
    decoded()?.let { CourseShape(routeId = routeId, name = name, shape = it) }

/** One course's shape as a row keeps it. */
fun routeShapeRowOf(routeId: Long, shape: RunShape?): RouteShapeRow = RouteShapeRow(
    routeId = routeId,
    shape = shape?.let { taken ->
        RoutePolyline.encode(
            taken.waypoints.map { RoutePoint(it.latitude, it.longitude, elevationMeters = null) }
        )
    },
    distanceMeters = shape?.distanceMeters ?: 0.0,
)

/**
 * Read back strictly, [RunShapeRow]'s rule and for its reason: a shape with a waypoint missing is not
 * a shorter course, it is a shape whose waypoints no longer stand for the fractions of a line they
 * are compared at. A row that cannot be read whole holds nothing, and the course claims no Runs until
 * it is measured again.
 */
private fun decodeCourseShape(polyline: String?, distanceMeters: Double): RunShape? {
    val waypoints = RoutePolyline.decode(polyline ?: return null)
    if (waypoints.size != RUN_SHAPE_WAYPOINTS) return null
    return RunShape(
        waypoints = waypoints.map { MapFix(it.latitude, it.longitude) },
        distanceMeters = distanceMeters,
    )
}
