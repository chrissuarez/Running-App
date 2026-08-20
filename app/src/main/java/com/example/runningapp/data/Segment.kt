package com.example.runningapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
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
    /**
     * Whether the whole of history has been walked against this Segment (#70).
     *
     * False is a debt, the same kind [com.example.runningapp.data.RunnerSession.recordsScored]'s
     * false is: it says nobody has put the runner's Runs to this ground yet, never that none of them
     * crossed it. A Segment walks history the moment it is cut, and that walk is minutes long and
     * runs outside any screen — so it can be lost to a process being reclaimed half way through, and
     * a Segment cut before this shipped never had one at all. The launch pass
     * ([SessionRepository.payWhatSegmentTimingOwes]) finds every Segment still carrying a false here
     * and walks it.
     *
     * Written only once the walk has *returned*, so an ending in between leaves the debt standing.
     * A repeated walk costs arithmetic and nothing else: it replaces a pair's efforts rather than
     * adding to them ([SegmentEffortDao.replaceEffortsOf]).
     */
    val historyTimed: Boolean = false,
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

    /** The whole collection, read once — what a Run that has just finished is put to (#70). */
    @Query("SELECT * FROM segments")
    suspend fun getAllSegments(): List<Segment>

    /** One Segment, read once — what a newly cut Segment's history scan is measured against (#70). */
    @Query("SELECT * FROM segments WHERE id = :segmentId")
    suspend fun getSegment(segmentId: Long): Segment?

    /**
     * The Segments history has never been walked against (#70) — the launch pass's work list.
     *
     * Oldest first, so a runner who cut three of them in one sitting sees them fill in the order
     * they cut them.
     */
    @Query("SELECT * FROM segments WHERE historyTimed = 0 ORDER BY createdAtMillis ASC, id ASC")
    suspend fun getSegmentsMissingHistory(): List<Segment>

    /** Marks one Segment as walked against history — written only after that walk has landed. */
    @Query("UPDATE segments SET historyTimed = 1 WHERE id = :segmentId")
    suspend fun setHistoryTimed(segmentId: Long)

    @Insert
    suspend fun insertSegment(segment: Segment): Long

    @Query("UPDATE segments SET name = :name WHERE id = :segmentId")
    suspend fun renameSegment(segmentId: Long, name: String)

    @Query("DELETE FROM segments WHERE id = :segmentId")
    suspend fun deleteSegment(segmentId: Long)
}

/**
 * One time a Run went over a Segment (#70).
 *
 * The measurement, banked. It is not read off the Run on demand like a split or a climb is, because
 * it is not a fact about the Run: it is a fact about the Run *and* a Segment, found by walking one
 * against the other ([com.example.runningapp.segments.segmentTraversalsIn]), and a Segment's page
 * would otherwise re-walk every track in history to draw one list.
 *
 * Deleted with either parent, and for different reasons. With the Run, like every other recording of
 * one: the effort is a stretch of that Run's track, and history the runner has thrown away must not
 * leave a time on a leaderboard. With the Segment, because the effort is a time *at* that Segment
 * and means nothing without the ground it was run over — which is what makes deleting a Segment take
 * its efforts with it rather than orphaning them.
 *
 * There is no elapsed column. The two crossings are the measurement and the elapsed time is their
 * difference, so a column would be a second copy of one number, free to disagree with the first
 * after a migration or a rescan; the queries subtract instead.
 */
@Entity(
    tableName = "segment_efforts",
    foreignKeys = [
        ForeignKey(
            entity = Segment::class,
            parentColumns = ["id"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RunnerSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("segmentId"),
        Index("sessionId"),
        // One Run cannot have crossed one Segment's start gate twice at the same instant, so this
        // says what "already scanned" means in the schema itself rather than only in the pass that
        // writes it (see [SegmentEffortDao.replaceEffortsOf]).
        Index(value = ["segmentId", "sessionId", "startedAtMillis"], unique = true)
    ]
)
data class SegmentEffort(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val segmentId: Long,
    val sessionId: Long,
    /** When the runner crossed the start gate — interpolated, not the nearest fix. */
    val startedAtMillis: Long,
    /** When they crossed the end gate, the same way. */
    val finishedAtMillis: Long,
)

/**
 * One effort as a Segment's page needs it: the time, and enough of its Run to date it (#70).
 *
 * The Run's own offset travels with the row because a date is the runner's date
 * ([com.example.runningapp.ranOn], #304) — an effort run at midnight in Spain is not the previous
 * day because the phone has come home since.
 */
data class SegmentEffortRow(
    val effortId: Long,
    val sessionId: Long,
    val startedAtMillis: Long,
    val elapsedMillis: Long,
    val ranAtUtcOffsetSeconds: Int?,
)

/**
 * One effort at a Segment, carrying the Segment with it — what a *Run's* page reads (#71).
 *
 * A Segment's own page knows which ground it is looking at; a Run's page does not, so the name and
 * the length travel on the row. The length is here because the card prints a pace, and a pace is
 * the elapsed time against the ground it covered.
 *
 * These rows are every effort at the Segments the Run crossed, not only the Run's own. A medal is a
 * claim about all of them — third place means three efforts were quicker — so the rivals have to
 * arrive in the same read as the efforts they are ranked against, or the card could place a Run
 * against a leaderboard that had already moved.
 */
data class RunSegmentEffortRow(
    val effortId: Long,
    val segmentId: Long,
    val segmentName: String,
    val distanceMeters: Double,
    val sessionId: Long,
    val startedAtMillis: Long,
    val elapsedMillis: Long,
)

@Dao
interface SegmentEffortDao {

    /**
     * Every effort ever run at one Segment, newest first — the page's whole list, and the PR is the
     * quickest row in it.
     *
     * One read rather than a list query and a separate "fastest" query, so the record at the top of
     * the page and the list under it cannot be two different answers taken a moment apart.
     */
    @Query(
        """
        SELECT e.id AS effortId,
               e.sessionId AS sessionId,
               e.startedAtMillis AS startedAtMillis,
               (e.finishedAtMillis - e.startedAtMillis) AS elapsedMillis,
               s.ranAtUtcOffsetSeconds AS ranAtUtcOffsetSeconds
        FROM segment_efforts e
        JOIN sessions s ON s.id = e.sessionId
        WHERE e.segmentId = :segmentId
        ORDER BY e.startedAtMillis DESC
        """
    )
    fun getEffortsFlow(segmentId: Long): Flow<List<SegmentEffortRow>>

    /**
     * Everything a Run's Segments card needs, in one read (#71): every effort at every Segment this
     * Run went over — the Run's own, and every rival they are placed against.
     *
     * Watched rather than read once, because the card is a leaderboard and a leaderboard moves
     * under a page that is already open: a Segment cut minutes ago is still walking history on a
     * scope of its own, and each Run it finds can push this one down a place.
     *
     * The rivals are fetched rather than counted in SQL on purpose. A place is decided by a rule
     * about ties ([com.example.runningapp.ui.runSegmentEffortsUi]) that a Segment's own page already
     * keeps; written a second time here in SQL it would be free to drift, and the two pages would
     * then disagree about who holds the record.
     */
    @Query(RUN_SEGMENT_EFFORTS_SQL)
    fun getEffortsForRunFlow(sessionId: Long): Flow<List<RunSegmentEffortRow>>

    @Query("DELETE FROM segment_efforts WHERE segmentId = :segmentId AND sessionId = :sessionId")
    suspend fun deleteEffortsOf(segmentId: Long, sessionId: Long)

    @Insert
    suspend fun insertEfforts(efforts: List<SegmentEffort>)

    /**
     * What one Run is worth at one Segment, written down — replacing whatever the last reading of
     * the same pair said.
     *
     * This is what makes a rescan idempotent, and it is a replacement rather than an insert that
     * skips what is already there because the two are not the same promise. Skipping would leave a
     * Run holding efforts measured under an older rulebook forever; replacing means the answer in
     * the database is always the answer the code gives today, and a Run whose efforts have gone
     * (marked a Walk, its track corrected) loses them rather than keeping a time nothing would
     * measure again.
     *
     * In one transaction, so a Segment's page can never be caught with a Run's efforts half deleted
     * and a PR that belongs to nobody.
     */
    @Transaction
    suspend fun replaceEffortsOf(segmentId: Long, sessionId: Long, efforts: List<SegmentEffort>) {
        deleteEffortsOf(segmentId, sessionId)
        if (efforts.isNotEmpty()) insertEfforts(efforts)
    }
}


/**
 * The read behind [SegmentEffortDao.getEffortsForRunFlow], named so a test can put it to a real
 * SQLite database rather than to a hand-written stand-in that agrees with it by luck.
 *
 * A `const` rather than a literal in the annotation for exactly that reason: the medal on a Run's
 * page is decided by which rows this returns, and a test that retyped the SQL would go on passing
 * after this one changed.
 */
const val RUN_SEGMENT_EFFORTS_SQL: String =
    """
        SELECT e.id AS effortId,
               e.segmentId AS segmentId,
               g.name AS segmentName,
               g.distanceMeters AS distanceMeters,
               e.sessionId AS sessionId,
               e.startedAtMillis AS startedAtMillis,
               (e.finishedAtMillis - e.startedAtMillis) AS elapsedMillis
        FROM segment_efforts e
        JOIN segments g ON g.id = e.segmentId
        WHERE e.segmentId IN (SELECT segmentId FROM segment_efforts WHERE sessionId = :sessionId)
    """
