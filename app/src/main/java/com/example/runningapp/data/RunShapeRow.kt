package com.example.runningapp.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.runningapp.analysis.MapFix
import com.example.runningapp.routes.RoutePoint
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.segments.RUN_SHAPE_WAYPOINTS
import com.example.runningapp.segments.RunShape
import kotlinx.coroutines.flow.Flow

/**
 * The few places that say which ground one Run covered (#73), banked.
 *
 * Taking a shape means reading a Run's whole track and measuring every leg of it, which is thousands
 * of rows and seconds of arithmetic; deciding whether two shapes are the same route is ten numbers
 * and no rows at all ([com.example.runningapp.segments.runsMatch]). So the measurement is banked and
 * the *grouping is not*: a Run's page works out which Runs match it every time it is opened, from
 * these rows.
 *
 * That is what makes the groups need no mending. There is nothing stored to go stale — deleting a
 * Run takes its row with it and every group it was in is one Run smaller at the next read, and a
 * second pass over the same Run writes the same row rather than a second group.
 *
 * [shape] is null for a Run that has no shape to take: a treadmill Run, one recorded before there
 * were tracks, one too short to hold a route, and a Walk — the runner's own word, which can arrive
 * long after the Run. Null is not an absent row. **The row existing is what says this Run has been
 * measured**, and a Run with no row at all is the debt the launch pass pays
 * ([SessionDao.getSessionIdsMissingRunShapes]) — so a Walk is measured once and passed over
 * afterwards, rather than having its whole track re-read at every launch for ever.
 */
@Entity(
    tableName = "run_shapes",
    foreignKeys = [
        ForeignKey(
            entity = RunnerSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RunShapeRow(
    /**
     * The Run this is the shape of, and the key itself: a Run has one shape, and a second reading of
     * the same Run replaces the first rather than standing beside it.
     */
    @PrimaryKey val sessionId: Long,
    /**
     * The waypoints, written the way a Route's line and a Segment's are
     * ([RoutePolyline]) — one encoding in the app rather than three.
     */
    val shape: String?,
    /** How far the Run went, as its own legs counted it. Zero where there is no shape. */
    val distanceMeters: Double,
)

/**
 * One Run a group could hold: its shape, and enough of the Run itself to draw a row and a point on
 * the trend (#73).
 *
 * The Run's own offset travels with the row because a date is the runner's date
 * ([com.example.runningapp.ranOn], #304).
 */
data class RunShapeCandidate(
    val sessionId: Long,
    val shape: String,
    val distanceMeters: Double,
    val startTime: Long,
    val ranAtUtcOffsetSeconds: Int?,
    val durationSeconds: Long,
    val movingTimeSeconds: Long?,
    val avgPaceMinPerKm: Double,
)

@Dao
interface RunShapeDao {

    /**
     * Every Run that holds a shape, oldest first — the whole field a Run is matched against (#73).
     *
     * Watched rather than read once, because a Run's page names its place in a group ("your 14th run
     * on this route") and that is a claim about every other Run there is: a Run finishing, being
     * deleted, or being marked a Walk while this page is open moves the number on it.
     *
     * Every shaped Run rather than only the matching ones, because the matching is a geometry rule
     * kept in one place ([com.example.runningapp.segments.runsMatch]) and SQL cannot ask it. The
     * rows are small — five places and two numbers — so this is a handful of kilobytes for a
     * runner's whole life.
     */
    @Query(SHAPED_RUNS_SQL)
    fun getShapedRunsFlow(): Flow<List<RunShapeCandidate>>

    /**
     * Whether one Run has been measured for a shape yet, watched.
     *
     * The row existing is the whole of the answer, as it is for the debt pass: a null [shape] on a
     * present row is a Run that has been measured and holds none — a treadmill Run, a Walk, one too
     * short to hold a route — and is just as measured as one that came back with five waypoints.
     * Asked by the Run Summary (#76), which must not describe a Run whose group has not been worked
     * out yet.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM run_shapes WHERE sessionId = :sessionId)")
    fun isShapedFlow(sessionId: Long): Flow<Boolean>

    /**
     * Every finished Run that holds a shape, newest first, with enough of the Run beside it to print
     * a row on a course's page (#74).
     *
     * The same rows [getShapedRunsFlow] serves, asked for by a different reader and answered with
     * different columns. A Run's own page needs a pace to draw a trend; a course's page needs the
     * clock and the distance its best time is judged on, which are the very columns
     * [SessionDao.getRunsAlongRouteFlow] returns — so those columns are taken whole
     * ([RouteRunRow]) rather than restated, and the two halves of a course's list are printed by one
     * piece of code that cannot tell them apart.
     *
     * `endTime > 0` for [SessionDao.getRunsAlongRouteFlow]'s reason, and it is belt and braces here:
     * a Run still being recorded may not be matched at all
     * ([com.example.runningapp.segments.mayBeMatchedToOtherRuns]), so it holds no shape to be found
     * by. Stated anyway, because this query's promise to its reader is about which Runs may appear on
     * a page, and that promise should not rest on a rule kept in another file.
     */
    @Query(SHAPED_RUNS_ON_COURSES_SQL)
    fun getShapedRunsForCoursesFlow(): Flow<List<ShapedRunRow>>

    /** Writes what one Run's shape is now, over whatever the last reading of it said. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putShape(shape: RunShapeRow)

    /**
     * Takes a Run's shape off it, leaving it owing one.
     *
     * The absence of a row is the debt ([SessionDao.getSessionIdsMissingRunShapes]), so this is how
     * anything that changes what a Run *is* — the runner marking it a Walk, or unmarking it — hands
     * the Run back to the pass. Written before the change rather than after it, so an ending in
     * between leaves the debt standing rather than a shape that describes a Run nobody has now.
     */
    @Query("DELETE FROM run_shapes WHERE sessionId = :sessionId")
    suspend fun forgetShape(sessionId: Long)
}

/**
 * One finished Run as a course's page would print it, with the shape it is recognised by (#74).
 *
 * The printable half is [RouteRunRow] itself rather than a copy of its columns, so a Run recognised
 * on a course and a Run remembered on one reach [com.example.runningapp.ui.routeRunsUi] as the same
 * thing — there is no second row type for the page to treat differently, and no second place for the
 * clock rule to be spelled out.
 */
data class ShapedRunRow(
    @Embedded val run: RouteRunRow,
    val shape: String,
    /** How far the Run went as its own legs counted it — the number the shape was matched at. */
    val shapeDistanceMeters: Double,
)

/** The shape a [ShapedRunRow] holds, or null where what it holds is not a whole shape. */
fun ShapedRunRow.decoded(): RunShape? = decodeShapeWaypoints(shape, shapeDistanceMeters)

/** The shape a candidate row holds, or null where what it holds is not a whole shape. */
fun RunShapeCandidate.decoded(): RunShape? = decodeShapeWaypoints(shape, distanceMeters)

/** One shape as a row keeps it. */
fun runShapeRowOf(sessionId: Long, shape: RunShape?): RunShapeRow = RunShapeRow(
    sessionId = sessionId,
    shape = shape.encodedWaypoints(),
    distanceMeters = shape?.distanceMeters ?: 0.0,
)

/**
 * The waypoints as a row keeps them, or null where there was no shape to take.
 *
 * Written with [RoutePolyline], the app's one encoding for a line — used by a Route, a Segment and a
 * shape alike, and naming it twice would be the actual mistake (CONTEXT.md).
 *
 * Here rather than beside each row type, because a Run's shape and a course's are the *same five
 * places* written the same way and read back by the same rule ([decodeShapeWaypoints]): they are
 * compared with each other (#74), so a second copy of the writing would be free to drift into
 * writing one of them differently, and the comparison would quietly stop meaning anything.
 */
fun RunShape?.encodedWaypoints(): String? = this?.let { taken ->
    RoutePolyline.encode(
        taken.waypoints.map { RoutePoint(it.latitude, it.longitude, elevationMeters = null) }
    )
}

/**
 * The five places back off a row, or null where what the row holds is not a whole shape.
 *
 * Read back strictly, unlike a Route's line: a shape with a waypoint missing is not a shorter shape,
 * it is a shape whose waypoints no longer stand for the fractions of a line they are compared at,
 * and matching on it would put Runs in a group they never ran, or on a course they never covered. A
 * row that cannot be read whole holds nothing, and its Run drops out of every group — or its course
 * claims nothing — until it is measured again.
 */
fun decodeShapeWaypoints(polyline: String?, distanceMeters: Double): RunShape? {
    val waypoints = RoutePolyline.decode(polyline ?: return null)
    if (waypoints.size != RUN_SHAPE_WAYPOINTS) return null
    return RunShape(
        waypoints = waypoints.map { MapFix(it.latitude, it.longitude) },
        distanceMeters = distanceMeters,
    )
}

/**
 * The read behind [RunShapeDao.getShapedRunsFlow], named so a test can put it to a real SQLite
 * database rather than to a hand-written stand-in that agrees with it by luck (#73).
 *
 * A `const` rather than a literal in the annotation for [RUN_SEGMENT_EFFORTS_SQL]'s reason: what a
 * runner is told about their own repetition is decided by which rows this returns, and a test that
 * retyped the SQL would go on passing after this one changed. The delete case is the sharper half —
 * a Run leaving its group is a promise the *schema* keeps ([RunShapeRow]'s cascade), which no fake
 * DAO can be asked about at all.
 */
const val SHAPED_RUNS_SQL: String =
    """
        SELECT r.sessionId AS sessionId,
               r.shape AS shape,
               r.distanceMeters AS distanceMeters,
               s.startTime AS startTime,
               s.ranAtUtcOffsetSeconds AS ranAtUtcOffsetSeconds,
               s.durationSeconds AS durationSeconds,
               s.movingTimeSeconds AS movingTimeSeconds,
               s.avgPaceMinPerKm AS avgPaceMinPerKm
        FROM run_shapes r
        JOIN sessions s ON s.id = r.sessionId
        WHERE r.shape IS NOT NULL
        ORDER BY s.startTime ASC
    """

/**
 * The read behind [RunShapeDao.getShapedRunsForCoursesFlow], named so a test can put it to a real
 * SQLite database (#74) — [SHAPED_RUNS_SQL]'s rule and for its reason.
 */
const val SHAPED_RUNS_ON_COURSES_SQL: String =
    """
        SELECT s.id AS sessionId,
               s.startTime AS startTime,
               s.ranAtUtcOffsetSeconds AS ranAtUtcOffsetSeconds,
               s.durationSeconds AS durationSeconds,
               s.movingTimeSeconds AS movingTimeSeconds,
               s.distanceKm AS distanceKm,
               s.isWalk AS isWalk,
               r.shape AS shape,
               r.distanceMeters AS shapeDistanceMeters
        FROM run_shapes r
        JOIN sessions s ON s.id = r.sessionId
        WHERE r.shape IS NOT NULL AND s.endTime > 0
        ORDER BY s.startTime DESC
    """
