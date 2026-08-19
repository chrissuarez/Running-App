package com.example.runningapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A stretch of ground the runner has named, cut out of a Run they actually ran (#69).
 *
 * Not a Route and not a Run. A Route is a plan somebody else drew and a Run is a recording of one
 * outing; a Segment is a *place* — "Cemetery Hill" — that the runner expects to cross again and
 * again, and its whole point is to outlive the Run it came from.
 *
 * That is why the geometry is copied onto this row rather than pointed at. Reading it back out of
 * `track_points` would tie the place to the recording: deleting that one Run would take the hill
 * with it, and a Segment the runner has been measuring themselves against for a year would vanish
 * because they tidied up an outing from last March.
 *
 * [sourceSessionId] is provenance and nothing more — which Run this was traced from, so the page
 * can say where it came from. It is nulled rather than cascaded when that Run is deleted, for the
 * reason above: losing the Run loses the answer to "where did this come from", not the place.
 */
@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(
            entity = RunnerSession::class,
            parentColumns = ["id"],
            childColumns = ["sourceSessionId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("sourceSessionId")]
)
data class Segment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The runner's name for the place. Theirs from the start — nothing else ever names one. */
    val name: String,
    /**
     * The ground itself, written the way a Route's is
     * ([com.example.runningapp.routes.RoutePolyline]) — the same format for the same reason, so a
     * row can be read by eye on a phone and there is one encoding in the app rather than two.
     */
    val polyline: String,
    /**
     * How far it goes, as the Run that it was cut from counted it
     * ([com.example.runningapp.segments.SegmentCut.Cut.distanceMeters]).
     *
     * Banked at creation rather than re-measured on read, the bargain a Route makes
     * ([ADR 0014](../../../../../../../docs/adr/0014-a-route-is-a-plan-not-a-recording.md)): the
     * evidence it was measured off is one particular Run's track, which this row deliberately does
     * not depend on staying in the database.
     */
    val distanceMeters: Double,
    /** The Run it was traced from, or null once that Run has been deleted. */
    val sourceSessionId: Long?,
    val createdAtMillis: Long,
)

@Dao
interface SegmentDao {

    /** The whole collection, newest first — where a runner who has just cut one expects to find it. */
    @Query("SELECT * FROM segments ORDER BY createdAtMillis DESC, id DESC")
    fun getAllSegmentsFlow(): Flow<List<Segment>>

    /**
     * One Segment's own page, watched rather than read once, so a rename made on the list behind it
     * arrives here too.
     *
     * Null once it is deleted, which is what closes the page rather than leaving the runner looking
     * at a place that no longer exists.
     */
    @Query("SELECT * FROM segments WHERE id = :segmentId")
    fun getSegmentFlow(segmentId: Long): Flow<Segment?>

    @Insert
    suspend fun insertSegment(segment: Segment): Long

    @Query("UPDATE segments SET name = :name WHERE id = :segmentId")
    suspend fun renameSegment(segmentId: Long, name: String)

    @Query("DELETE FROM segments WHERE id = :segmentId")
    suspend fun deleteSegment(segmentId: Long)
}
