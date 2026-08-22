package com.example.runningapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The Run Summary one Run has been given, kept (#76).
 *
 * Written the first time the runner opens the Run's page and read from here for ever afterwards, so
 * a page opened a second time costs nothing and reaches nothing. That is the whole reason the row
 * exists: the words are written by a model over the network, which is slow, is money, and is not
 * there at all on a phone with no signal.
 *
 * **An absent row is a Run nobody has opened**, and never a Run whose summary is empty — the writing
 * either lands and is stored or is not stored at all. So there is no backfill and no debt: history
 * arrives holding none of these, and each one appears the first time its Run is looked at. A Run
 * nobody ever opens is never sent anywhere, which is the point of writing them lazily rather than at
 * the finish line.
 *
 * Replaced rather than added to, so asking for the words again is asking for *these* words again.
 * Deleted with its Run like every other recording of one.
 */
@Entity(
    tableName = "run_summaries",
    foreignKeys = [
        ForeignKey(
            entity = RunnerSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RunSummaryRow(
    /** The Run these words are about, and the key: a Run has one summary. */
    @PrimaryKey val sessionId: Long,
    /** What the model wrote, as it wrote it. */
    val text: String,
    /**
     * When it was written.
     *
     * Nothing on screen reads it. It is kept because these words are the one thing on a Run's page
     * that was not measured from the Run — when a future model writes differently, or a bug puts bad
     * words in, "which of these were written before the change" is a question only the row can
     * answer, and it cannot be worked out afterwards.
     */
    val writtenAtMillis: Long,
)

@Dao
interface RunSummaryDao {

    /**
     * The words this Run holds, watched.
     *
     * Watched rather than read once because the page is open while they are being written: the card
     * is empty, the model answers, the row lands, and the card fills in without the runner doing
     * anything.
     */
    @Query("SELECT * FROM run_summaries WHERE sessionId = :sessionId")
    fun summaryFlow(sessionId: Long): Flow<RunSummaryRow?>

    /** Writes the words for a Run, over whatever was written for it before. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(summary: RunSummaryRow)
}

/**
 * How an ask for a summary ended (#76).
 *
 * Three answers rather than a boolean, because the runner is shown a different thing for each.
 * [WRITTEN] fills the card. [FAILED] is the one that earns a retry — the phone had no signal, or the
 * model said nothing — and offering one is the whole of what "a missing summary never blocks
 * reviewing a run" asks for. [REFUSED] is the app declining to ask at all, and a retry button would
 * be a button that can only fail: the Run is not one that may be sent, or there is nothing wired to
 * send it with.
 */
enum class RunSummaryOutcome { WRITTEN, FAILED, REFUSED }
