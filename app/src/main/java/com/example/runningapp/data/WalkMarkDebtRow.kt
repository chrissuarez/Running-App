package com.example.runningapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A Run whose row does not yet say what the runner said it was, written down so somebody comes back
 * for it (#371).
 *
 * The Walk mark reaches the app twice: as the runner's own word, handed from the finish sheet
 * straight to the settlement, and as a write onto the Run's row. The word is what the Stage is
 * judged on, because a graduation cannot be taken back and a write can fail (#297, #317). The write
 * is what everything *else* reads — history, the fitness figures, the record book, the Segments —
 * and the two attempts that make it are both deliberately guarded
 * ([SessionRepository.finishSheetAnswered] and [SessionRepository.putTheWordBackOnTheRow]): a mend
 * that throws must not cost the Run the judgement, which is the irreversible half.
 *
 * That guard is what leaves this row's reason to exist. The judgement then goes ahead and is right,
 * `stageSettled` is written, and the Run is beyond every launch pass there is — while the column
 * still says `isWalk = false`. Medals a Walk may not hold stay standing, and the runner's only
 * remedy is to open the Run and tick Walk again by hand, which they have no reason to know they
 * need to do.
 *
 * **A row here is that owed mark, and nothing else.** [isWalk] is the word itself and not a flag,
 * because the debt runs both ways: a Run wrongly marked a Walk owes an unmarking exactly as a Walk
 * left unmarked owes a marking, and the pass that pays it must know which it is holding rather than
 * guess from the column it is about to overwrite.
 *
 * **Written in the same transaction as the settlement that raises it**
 * ([SessionRepository.settleAndOweAnyWalkMark]), for the reason every debt in this database is: the debt
 * and the state change that makes it undischargeable have to commit together. Raised first and
 * committed separately, a process dying in between leaves the Run settled and owing nothing, which
 * is the exact state this exists to end; raised after, it is a smaller window of the same thing.
 *
 * **Paid at launch** ([SessionRepository.payWalkMarkDebts]), the way the other launch passes pay
 * theirs. In the database rather than in memory because the process that failed to write the mark is
 * often the process that is about to die — and because the debt and the row it is about are then one
 * file, backed up together and restored together, never able to disagree about which of them is out
 * of date.
 *
 * **Discharged by any mark that lands, not only by the pass** — see [WalkMarkDebtDao.forgetDebtFor]. The
 * runner can reach the same switch on the Run's own page, and a debt still standing after they have
 * had their say would put their own tick back at the next launch.
 *
 * Deleted with its Run, like every other recording of one: a debt about a Run that is gone is owed
 * to nobody, and Room can hand a deleted Run's id to the next Run written.
 */
@Entity(
    tableName = "walk_mark_debts",
    foreignKeys = [
        ForeignKey(
            entity = RunnerSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class WalkMarkDebtRow(
    @PrimaryKey val sessionId: Long,
    /** What the runner said the Run was, exactly as the settlement was given it. */
    val isWalk: Boolean,
)

@Dao
interface WalkMarkDebtDao {

    /**
     * Writes down that [row]'s Run owes its mark, over whatever was there before.
     *
     * One row per Run because one Run has one answer, and the primary key is what says so: a second
     * settlement of the same Run cannot happen ([RunnerSession.stageSettled]), and the runner's own
     * later tick discharges the debt rather than adding to it.
     *
     * **Replacing rather than refusing**, even though that key can only be hit by something that
     * should be impossible. This runs inside the settlement's own transaction, where a constraint
     * failure would roll the settlement back and cost the Run its judgement — so where the two
     * choices are "write the newer word" and "throw inside a transaction that must commit", the
     * first is the only one worth having. A restored archive is the way it could happen: it brings
     * debts of its own, against ids this install's own Runs are then written at.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun owe(row: WalkMarkDebtRow)

    /**
     * Drops a Run's debt, because its row now says what somebody meant it to say.
     *
     * Called from inside the transaction that writes the mark ([SessionRepository.markAsWalk]) and
     * not after it, so a mark that lands and a debt that stands can never be the same instant. Every
     * writer of the mark discharges it that way, not only the pass: the switch on the Run's own page
     * is the runner correcting the row themselves, and a debt outliving that would undo them at the
     * next launch.
     *
     * The pass calls it a second time itself ([SessionRepository.payWalkMarkDebts]), for the one
     * case that transaction cannot cover: a row that already agrees, which [markAsWalk] refuses as a
     * change of nothing and so never opens a transaction for. Only the pass does that, so a Save
     * that leaves the Walk switch as it found it still costs no write at all.
     */
    @Query("DELETE FROM walk_mark_debts WHERE sessionId = :sessionId")
    suspend fun forgetDebtFor(sessionId: Long)

    /**
     * The debt standing against one Run, or null where it owes nothing.
     *
     * Read inside the transaction that writes a mark the launch pass is paying
     * ([SessionRepository.markAsWalk]), and read nowhere else. The pass takes its work list in one
     * go ([owed]) and then pays it a Run at a time, so between the list and any one payment the
     * runner has had every chance to reach the switch on that Run's own page and say the opposite —
     * which discharges the debt. Without this the pass would still write the word it was holding,
     * over the top of the runner's newer one, and a tick they made during startup would come undone
     * in front of them.
     *
     * The whole row and not the id, because the debt the pass is holding and the debt on the disk
     * agreeing that a mark is owed is not the same thing as their agreeing *which* mark: the word is
     * [isWalk], and a payment may only land while the row it came from is unchanged.
     *
     * A read and not a claim — the pass may not delete the debt first and mark afterwards, because a
     * mark that then fails leaves the Run wrong for ever with nothing left to say so. The debt is
     * still discharged by the write it belongs to ([forgetDebtFor]), in the transaction this read
     * happens in, so "the debt stood when the mark landed" is one indivisible statement rather than
     * a check with a window after it.
     */
    @Query("SELECT * FROM walk_mark_debts WHERE sessionId = :sessionId")
    suspend fun debtFor(sessionId: Long): WalkMarkDebtRow?

    /**
     * Drops a Run's debt only while it still says exactly what the caller was holding.
     *
     * The launch pass's own tidy-up, and nobody else's. That pass may finish a payment without
     * having written anything — a row that already agrees, which [SessionRepository.markAsWalk]
     * refuses as a change of nothing, and a payment its own guard abandoned because the debt had
     * moved under it. Told only the id, the delete would take away whatever debt now stands there,
     * including a newer one nobody has paid: the row left as it was, and the mark owed against it
     * forgotten for good. Naming the word as well as the Run makes the delete describe the debt the
     * pass actually dealt with, so a debt it did not deal with survives it.
     */
    @Query("DELETE FROM walk_mark_debts WHERE sessionId = :sessionId AND isWalk = :isWalk")
    suspend fun forgetDebtIfUnchanged(sessionId: Long, isWalk: Boolean)

    /** Every mark still owed, oldest Run first — the launch pass's whole work list. */
    @Query("SELECT * FROM walk_mark_debts ORDER BY sessionId")
    suspend fun owed(): List<WalkMarkDebtRow>
}

/**
 * The v39 to v40 migration's statement, named so a test can put the real thing to a real SQLite
 * database ([RECORD_FILL_TABLE_SQL]'s reason exactly).
 *
 * The table is created with the shape Room builds from [WalkMarkDebtRow] on a fresh install, because
 * Room checks the two against each other at every open and refuses the database if they differ.
 */
const val WALK_MARK_DEBTS_TABLE_SQL: String =
    """
        CREATE TABLE IF NOT EXISTS `walk_mark_debts` (
            `sessionId` INTEGER NOT NULL,
            `isWalk` INTEGER NOT NULL,
            PRIMARY KEY(`sessionId`),
            FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
    """
