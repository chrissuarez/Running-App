package com.example.runningapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A launch pass that still owes the whole of history a re-measuring, written down so anything
 * reading a Run's numbers can tell that they are about to change (#349).
 *
 * **The one way a history-wide pass says it owes work**, and the reason this table is keyed by the
 * pass rather than shaped for any one of them. Five such passes existed before it and each said so
 * differently — a dedicated `record_fill` row, a per-Run column, a per-Segment column, a row's
 * existence — which is four spellings too many: the sixth pass had nothing to copy, so the two that
 * arrived without a debt at all ([SessionRepository.backfillMovingTime] and
 * [SessionRepository.backfillEffortScores]) simply went without one. Everything they rewrite —
 * moving time, average pace, Effort Score — is read by other features while it is still moving.
 *
 * The case that surfaced it: a Run Summary (#76) is written once and kept for ever, so it must not
 * be written out of a half-measured history. It waits on [SessionRepository.historyBeingMeasuredFlow],
 * which folds together every history-wide debt that can be observed — and could not fold in these
 * two, because there was nothing to fold.
 *
 * **A row's existence is the whole fact.** There is nothing to store beside the name: "this pass
 * owes history work" is a yes or a no, and a count could not answer it anyway — one Run owing a
 * measurement is an ordinary Tuesday, and one Run owing a measurement is also the whole of an
 * upgrade's backfill on a history with one Run in it. What differs is not how much is owed but what
 * raised it, which is what naming the pass records.
 *
 * **Raised by a migration, in SQL, before a line of Kotlin runs; lowered by the pass when it has
 * been through its list.** That is [RecordFillRow]'s rule and it is here for its reasons. An upgrade
 * is the one moment that can honestly say history is half-measured, and raising the debt from inside
 * the pass instead would leave a window before the raise where a reader saw a clean history, and
 * would re-raise on every launch for ever on the installs whose work list never empties — a Run that
 * recorded no beats has no Effort Score to compute and stays on that list for good.
 *
 * **In the database rather than in settings.** A Room migration cannot write DataStore, and a pass
 * interrupted must come back owed at the next launch — the same durability the rows it describes
 * have, so the debt and the history are one file, backed up together and restored together, never
 * able to disagree about which of them is out of date.
 *
 * An empty table is what a fresh install holds, and it reads as nothing outstanding — which is the
 * truth about a history measured as it is run.
 */
@Entity(tableName = "history_debts")
data class HistoryDebtRow(
    /** One of [HistoryPass]. Text rather than a number so a row still reads plainly in a backup. */
    @PrimaryKey val pass: String,
)

/**
 * The passes that can owe history work — the names stored in [HistoryDebtRow.pass].
 *
 * Constants rather than an enum for the reason [RouteSource]'s are: the value is written to a
 * database by a migration's raw SQL, and an enum would put the spelling in two places that a
 * rename could part.
 */
object HistoryPass {
    /** Moving time and the average pace that follows from it, for Runs recorded before #163. */
    const val MOVING_TIME: String = "moving_time"

    /** The Effort Score, for Runs recorded before #62. */
    const val EFFORT_SCORES: String = "effort_scores"
}

@Dao
interface HistoryDebtDao {

    /**
     * Drops a pass's debt, because it has been through the history it owed.
     *
     * The only write this DAO offers. A debt is *raised* in SQL by the migration that makes history
     * half-measured ([HistoryDebtRow]), so there is nothing here to raise one with — a pass that
     * learns of work at a moment no migration can speak for is a design that does not exist yet, and
     * the way to add it is to add the writer then rather than to keep an unused one now.
     */
    @Query("DELETE FROM history_debts WHERE pass = :pass")
    suspend fun settle(pass: String)

    /**
     * Whether this pass owes anything right now, asked once — what a pass checks before lowering.
     *
     * A pass that was never owed anything has nothing to lower, and deleting anyway would wake every
     * reader of the flow below at every launch for the life of the app.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM history_debts WHERE pass = :pass)")
    suspend fun owes(pass: String): Boolean

    /**
     * Whether any pass at all owes history work, watched — the arm
     * [SessionRepository.historyBeingMeasuredFlow] folds in.
     *
     * Any rather than each, because every debt behind that flow has the same consequence: while one
     * stands, what a Run is worth can still change. A reader does not care which pass is still
     * walking.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM history_debts)")
    fun anyHistoryDebtOwedFlow(): Flow<Boolean>
}

/**
 * The v40 to v41 migration's statement, named so a test can put the real thing to a real SQLite
 * database ([RECORD_FILL_TABLE_SQL]'s reason exactly).
 *
 * The table is created with the shape Room builds from [HistoryDebtRow] on a fresh install, because
 * Room checks the two against each other at every open and refuses the database if they differ.
 */
const val HISTORY_DEBTS_TABLE_SQL: String =
    """
        CREATE TABLE IF NOT EXISTS `history_debts` (
            `pass` TEXT NOT NULL,
            PRIMARY KEY(`pass`)
        )
    """

/**
 * Writes down that history is owed the moving-time backfill (#163, #349).
 *
 * **Only where there is something to measure**, which is [RAISE_WHOLESALE_FILL_SQL]'s rule: an
 * install whose every Run already carries a moving time owes nothing, and saying otherwise would
 * cover up a Run Summary over work nobody is doing. The test is the pass's own work-list query
 * ([SessionDao.getSessionIdsMissingMovingTime]) written as an existence, so the two agree by
 * construction.
 */
const val RAISE_MOVING_TIME_DEBT_SQL: String =
    """
        INSERT OR REPLACE INTO `history_debts` (`pass`)
        SELECT 'moving_time' WHERE EXISTS (
            SELECT 1 FROM `sessions`
            WHERE `movingTimeSeconds` IS NULL AND `endTime` > 0 AND `runMode` = 'outdoor'
        )
    """

/**
 * Writes down that history is owed the Effort Score backfill (#62, #349) — the sibling of
 * [RAISE_MOVING_TIME_DEBT_SQL], and its work-list query ([SessionDao.getSessionIdsMissingEffort])
 * written the same way.
 */
const val RAISE_EFFORT_SCORE_DEBT_SQL: String =
    """
        INSERT OR REPLACE INTO `history_debts` (`pass`)
        SELECT 'effort_scores' WHERE EXISTS (
            SELECT 1 FROM `sessions` WHERE `endTime` > 0 AND `effortScore` IS NULL
        )
    """
