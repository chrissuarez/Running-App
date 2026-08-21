package com.example.runningapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
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

/**
 * Whether a **wholesale fill** of [RunEffortRow] is still outstanding — the one fact the Records
 * section needs before it may call anything an all-time best (#75).
 *
 * `run_efforts` is filled a Run at a time by the launch pass
 * ([SessionRepository.scoreMissedRecords]), and over a long history that is minutes of measuring.
 * While it is going on the table holds a *slice* of history, and a top ten read off a slice is a
 * top ten with the wrong Runs in it: whichever Run happened to be measured first takes gold, and
 * Runs that never placed stand fourth. So the section has to know when a fill is under way.
 *
 * **Why this is a stored fact and not a count.** The obvious stand-in is "how many finished Runs
 * still owe a scoring", and it cannot answer the question. One debt is an ordinary Run finishing on
 * a Tuesday — whose records must *not* be blanked for the seconds its own scoring takes — and it is
 * also the whole of the migration backfill on a history with one Run in it, where everything the
 * section could draw is missing. Counting cannot tell those apart at any threshold, because they
 * are the same count. What differs is not how much is owed but *what raised it*, so that is what is
 * written down.
 *
 * **In the database rather than in settings**, for two reasons that both matter. A Room migration
 * cannot write DataStore, and the migration that created `run_efforts` is the main thing that raises
 * this. And a fill that is interrupted — the process reclaimed, the phone off mid-pass — must come
 * back raised at the next launch, which is the same durability the table itself has: the fact and
 * the rows it describes are then one file, restored together, backed up together, and never able to
 * disagree about which of them is out of date.
 *
 * One row, at [SINGLE_ROW_ID]. No row at all is the answer a fresh install gives — Room builds this
 * table empty and nothing has ever needed filling — and it reads as "nothing outstanding", which is
 * the truth about a history that is measured as it is run.
 */
@Entity(tableName = "record_fill")
data class RecordFillRow(
    @PrimaryKey val id: Int = SINGLE_ROW_ID,
    val wholesaleFillOwed: Boolean,
) {
    companion object {
        /** The only id this table ever holds: the fact is about the table, not about any Run. */
        const val SINGLE_ROW_ID: Int = 0
    }
}

@Dao
interface RecordFillDao {

    /**
     * Raises or lowers the wholesale-fill debt, over whatever was there before.
     *
     * Raised where a fill *starts* — the v36 to v37 migration, in SQL, before a line of Kotlin runs
     * — and lowered where one finishes, which is the only pair of moments that can honestly speak
     * for it. Never written from the screen that reads it.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: RecordFillRow)

    /**
     * Whether a wholesale fill is outstanding right now, watched — what the Records section covers
     * itself up with, and uncovers itself by when the pass finishes.
     *
     * `EXISTS` rather than a plain column read so the answer is a plain false on a database that has
     * never held the row, instead of a null every caller would have to remember to fold down.
     */
    @Query(WHOLESALE_FILL_OWED_SQL)
    fun wholesaleFillOwedFlow(): Flow<Boolean>

    /**
     * The same answer once, for the passes that pay the debt off: a pass that was never owed a fill
     * has nothing to lower, and writing the row anyway would wake every reader of the flow above at
     * every launch for the life of the app.
     */
    @Query(WHOLESALE_FILL_OWED_SQL)
    suspend fun wholesaleFillOwed(): Boolean
}

/**
 * The v36 to v37 migration's half of the wholesale-fill fact, named so a test can put the real
 * statements to a real SQLite database (#75) — [RECORD_EFFORTS_SQL]'s reason exactly. What the
 * migration raises here is what the Records section covers itself up with for the whole of the
 * first launch after the upgrade, and a test that retyped the SQL would go on passing after this
 * changed.
 *
 * The table is created with the shape Room builds from [RecordFillRow] on a fresh install, because
 * Room checks the two against each other at every open and refuses the database if they differ.
 */
/**
 * The read both halves of [RecordFillDao] answer with, named so the migration's own statements and
 * the question the Records section asks of them can be put to a real SQLite database in one test
 * (#75) — [RECORD_EFFORTS_SQL]'s reason.
 */
const val WHOLESALE_FILL_OWED_SQL: String =
    "SELECT EXISTS(SELECT 1 FROM record_fill WHERE id = 0 AND wholesaleFillOwed = 1)"

const val RECORD_FILL_TABLE_SQL: String =
    """
        CREATE TABLE IF NOT EXISTS `record_fill` (
            `id` INTEGER NOT NULL,
            `wholesaleFillOwed` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
    """

/**
 * Writes down that the whole of history is owed a re-measuring against the record book — the debt
 * the v36 to v37 migration raises in the same breath as it clears every Run's scoring mark (#75).
 *
 * **Only where there is something to measure.** A history with no finished Run in it owes no fill:
 * the migration un-scores nothing, the launch pass finds nothing, and saying "still measuring your
 * runs" over an empty Records section would be a sentence about work nobody is doing. `endTime > 0`
 * is the same finished test the pass's own work list is read with, so the two agree by construction.
 */
const val RAISE_WHOLESALE_FILL_SQL: String =
    """
        INSERT OR REPLACE INTO `record_fill` (`id`, `wholesaleFillOwed`)
        SELECT 0, 1 WHERE EXISTS (SELECT 1 FROM `sessions` WHERE `endTime` > 0)
    """
