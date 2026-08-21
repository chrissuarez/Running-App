package com.example.runningapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.runningapp.analysis.RecordType
import kotlinx.coroutines.flow.Flow

/**
 * What one Run was worth at one Record, banked — every claim, not only the ones that placed (#75).
 *
 * The record book ([Achievement]) keeps the top three at each Record and nothing else, which is all
 * a Run's own page ever needed: a medal is the only thing about a Run that is a fact relative to
 * every other Run. The Records section of the Progress screen asks two questions that book cannot
 * answer — the all-time top *ten* at a Record, and how the runner's times at it have moved across
 * the calendar. Both are questions about efforts that never won anything, and beyond bronze the book
 * remembers none.
 *
 * So this is the same measurement stored deeper rather than a second measurement. Every row here is
 * a [com.example.runningapp.analysis.BestEffort] exactly as
 * [com.example.runningapp.analysis.bestEffortsOf] handed it over, written in the same transaction
 * that writes the medals it was ranked for — there is no second rule here about who may compete, and
 * nothing derives an effort of its own. A Walk holds no rows because it contests nothing; a
 * treadmill Run holds the two totals and whichever of the five distances it has been told it holds.
 *
 * [value] is seconds or metres according to [RecordType.unit], as the book's own value is. Deleted
 * with its Run, like every other recording of one — which is what keeps the top ten honest without
 * anything having to mend it: a deleted Run's rows go, and the tenth place is somebody else's at the
 * next read.
 */
@Entity(
    tableName = "run_efforts",
    primaryKeys = ["sessionId", "type"],
    foreignKeys = [
        ForeignKey(
            entity = RunnerSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("type")]
)
data class RunEffortRow(
    val sessionId: Long,
    val type: RecordType,
    val value: Double,
)

/**
 * One effort as the Records section reads it: the claim, and enough of the Run behind it to put a
 * date on it and open it.
 *
 * The Run's own offset travels with the row because a date is the runner's date
 * ([com.example.runningapp.ranOn], #304) — a 5 km run at 00:30 in Sydney is not the day before it.
 */
data class RecordEffortRow(
    val sessionId: Long,
    val type: RecordType,
    val value: Double,
    val startTime: Long,
    val ranAtUtcOffsetSeconds: Int?,
)

@Dao
interface RunEffortDao {

    /** Writes what a Run is worth now, over whatever the last measuring of it said. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEfforts(efforts: List<RunEffortRow>)

    /**
     * Clears everything one Run was worth, whatever it was worth it at.
     *
     * Whole and not by Record, because it answers the one question a per-Record clear cannot: which
     * Records has this Run *stopped* contesting? A Walk contests none, and a Run whose stated time
     * has been withdrawn contests one fewer — and neither is named by the Records anything is
     * re-measuring ([SessionRepository]).
     */
    @Query("DELETE FROM run_efforts WHERE sessionId = :sessionId")
    suspend fun deleteEffortsForSession(sessionId: Long)

    /**
     * What one Run is worth right now, at every Record it holds a claim at.
     *
     * Read so a re-measuring can say whether it actually moved anything (#75). Re-banking is a wipe
     * and a rewrite whatever it finds, so the rows alone cannot tell a caller apart from no change
     * at all — and the caller that has to know is the one deciding whether the history backup on
     * disk has gone stale.
     */
    @Query("SELECT * FROM run_efforts WHERE sessionId = :sessionId")
    suspend fun getEffortsForSession(sessionId: Long): List<RunEffortRow>

    /** Clears every Run's claim at [types] — the rebuild's own wipe, before it writes them all back. */
    @Query("DELETE FROM run_efforts WHERE type IN (:types)")
    suspend fun deleteEffortsOfTypes(types: List<RecordType>)

    /**
     * What is banked at [types] right now, for the rebuild to carry over the Runs it did not
     * measure — the same carry-in the record book makes, for the same reason and against the same
     * window ([com.example.runningapp.data.SessionRepository]).
     */
    @Query("SELECT * FROM run_efforts WHERE type IN (:types)")
    suspend fun getEffortsOfTypes(types: List<RecordType>): List<RunEffortRow>

    /**
     * Every claim ever banked, oldest Run first — the whole of what the Records section is drawn
     * from (#75).
     *
     * One read for all seven Records rather than a query per card. The rows are three numbers each
     * and a Run holds at most seven, so a runner's whole life is a few tens of kilobytes; splitting
     * it would buy nothing and cost the grid and the top ten the chance to disagree, which is the
     * thing they must never do.
     *
     * Watched rather than read once: a Run finishing, a treadmill time stated, a Run deleted or
     * marked a Walk all move what stands here, and the screen has to move with them.
     */
    @Query(RECORD_EFFORTS_SQL)
    fun getRecordEffortsFlow(): Flow<List<RecordEffortRow>>
}

/**
 * The read behind [RunEffortDao.getRecordEffortsFlow], named so a test can put it to a real SQLite
 * database rather than to a hand-written stand-in that agrees with it by luck (#75).
 *
 * A `const` for [SHAPED_RUNS_SQL]'s reason: which rows come back is what the runner is told their
 * records are, and a test that retyped the SQL would go on passing after this one changed. The
 * delete case is the sharper half — a deleted Run leaving the top ten is a promise the *schema*
 * keeps ([RunEffortRow]'s cascade), which no fake DAO can be asked about at all.
 */
const val RECORD_EFFORTS_SQL: String =
    """
        SELECT e.sessionId AS sessionId,
               e.type AS type,
               e.value AS value,
               s.startTime AS startTime,
               s.ranAtUtcOffsetSeconds AS ranAtUtcOffsetSeconds
        FROM run_efforts e
        JOIN sessions s ON s.id = e.sessionId
        ORDER BY s.startTime ASC
    """
