package com.example.runningapp.records

import android.util.Log
import com.example.runningapp.analysis.BestEffort
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.analysis.RunEfforts
import com.example.runningapp.analysis.bestEffortsOf
import com.example.runningapp.analysis.recordBookOf
import com.example.runningapp.analysis.standingsAfter
import com.example.runningapp.data.Achievement
import com.example.runningapp.data.RecordsReadingRow
import com.example.runningapp.data.RunEffortRow
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.SessionMedalCount
import com.example.runningapp.data.StatedBestEffort
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.byType
import com.example.runningapp.data.isFinished
import com.example.runningapp.training.HistoryBestEffort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything the record book reads and writes, in one place — the database as the book needs it
 * (#478).
 *
 * An interface for [com.example.runningapp.segments.SegmentTimingStore]'s reason: these are one
 * thing, what "Runs, their medals and the claims banked beneath them" looks like from here, and they
 * are the whole of what a test has to stand up to reach one Record rule.
 *
 * Plain reads and writes, and a transaction to put them in. The book's compare-and-writes — a Run
 * read again inside the commit that ranks it, a claim list read again inside the commit that
 * rebuilds from it — are the book's own rules, so they are written in [RecordBook] against
 * [inTransaction] rather than hidden in an implementation where a test could not see them.
 *
 * Every table is always there (#484): the app's store is
 * [com.example.runningapp.data.RoomRecordBookStore], and a test stands up the whole of one in memory
 * rather than wiring some tables and not others.
 */
interface RecordBookStore {
    suspend fun run(sessionId: Long): RunnerSession?
    /** Every Run in history, finished or not. */
    suspend fun runs(): List<RunnerSession>
    /** A Run's accuracy-gated fixes — the same ones the map, the splits and the GPX export use. */
    suspend fun track(sessionId: Long): List<TrackPoint>

    /** The finished Runs the book has never measured (#210). */
    suspend fun runsOwedScoring(): List<Long>
    suspend fun markScored(sessionId: Long)
    suspend fun markScored(sessionIds: List<Long>)

    /** What one Run has been told it holds (#282). */
    suspend fun statedFor(sessionId: Long): List<StatedBestEffort>
    suspend fun allStated(): List<StatedBestEffort>

    suspend fun medals(): List<Achievement>
    suspend fun medalsHeldBy(sessionIds: List<Long>): List<Achievement>
    /** Every medal at [types] taken off, and [medals] put on in their place. */
    suspend fun replaceMedalsOfTypes(types: List<RecordType>, medals: List<Achievement>)

    suspend fun effortsFor(sessionId: Long): List<RunEffortRow>
    /** Everything banked for one Run taken off, and [efforts] put on in its place. */
    suspend fun replaceEffortsFor(sessionId: Long, efforts: List<RunEffortRow>)
    /** Banked over whatever one Run already held at the same Record ([RunEffortRow]'s key). */
    suspend fun putEfforts(efforts: List<RunEffortRow>)
    suspend fun effortsOfTypes(types: List<RecordType>): List<RunEffortRow>
    /** Everything banked at [types] taken off, and [efforts] put on in its place. */
    suspend fun replaceEffortsOfTypes(types: List<RecordType>, efforts: List<RunEffortRow>)

    /** Whether the banked claims are part-way through being filled over all of history (#75). */
    suspend fun wholesaleFillOwed(): Boolean
    suspend fun markWholesaleFillPaid()

    /**
     * Whether the whole of history has been put to the book (#50) — null wherever there are no
     * settings to keep the mark in, which the passes that would pay it read as nothing to do.
     */
    suspend fun historySeeded(): Boolean?
    suspend fun markHistorySeeded()
    suspend fun clearHistorySeeded()

    /** Runs [block] as one database transaction. */
    suspend fun inTransaction(block: suspend () -> Unit)

    // --- For readers that are not the book (#484) ---
    //
    // The screens that show what the book holds, and the two statements a runner can make about a
    // treadmill Run. None of them decides anything the book ranks by, so they are plain reads and
    // writes here and the rules around them stay with their callers.

    /** The quickest effort history holds at [type], watched (#446). */
    fun quickestInHistoryFlow(type: RecordType): Flow<HistoryBestEffort?>
    /** How many medals each Run holds, watched (#51). */
    fun medalCountsFlow(): Flow<List<SessionMedalCount>>
    /** The banked claims and the fill flag, in one statement (#346). */
    fun recordsReadingFlow(): Flow<List<RecordsReadingRow>>
    /** Whether the banked claims are part-way through being filled, watched (#75). */
    fun wholesaleFillOwedFlow(): Flow<Boolean>
    /** What one Run has been told it holds, watched (#282). */
    fun statedForFlow(sessionId: Long): Flow<List<StatedBestEffort>>
    /** One stated Best Effort stored, over whatever the Run held at the same Record (#282). */
    suspend fun state(effort: StatedBestEffort)
    /** One Run's stated Best Effort at [type] taken away (#282). */
    suspend fun withdraw(sessionId: Long, type: RecordType)
}

/**
 * The record book: scoring a Run against it, marking what has been scored, and mending it when a
 * Run changes or leaves (#49, #50, #75, #210, #231, #282).
 *
 * Only the ranking is pure ([recordBookOf], [standingsAfter]). What is here is everything around
 * it — when a Run is measured, what it is checked against before the result is written, which debts
 * a pass may call paid and when — and it is here rather than in the repository so a Record bug has
 * one place to be, and a test can reach it without the repository (#478).
 *
 * [refreshHistoryBackup] re-snapshots history to Downloads after a change: a Clear-storage restore
 * reads that snapshot, so until it is rewritten it still holds what the change took away.
 */
class RecordBook(
    private val store: RecordBookStore,
    private val refreshHistoryBackup: (suspend () -> Unit)? = null,
) {

    /**
     * Scores a finished run against the record book and banks whatever it won (#49).
     *
     * Returns the medals *this run* holds afterwards, which is what its own page shows — an empty
     * list for an ordinary run, and for one that beat nothing — or `null` for a Run that changed
     * underneath the measuring, and was therefore not written to the book at all (#210).
     *
     * Private, and never called except through [scoreAndMark], which also marks the Run scored so
     * the launch pass does not score it again (#495). A door that scored without marking would be a
     * Run the launch pass scores a second time.
     *
     * Safe to call again: [standingsAfter] drops the run's own standing rows before ranking it, so
     * a re-score cannot leave it racing itself. The read of the book, the ranking and the rewrite
     * are one transaction, because a half-written book has a record with two golds in it and no way
     * to tell which one is real.
     *
     * Scoring history recorded before this shipped is a job of its own (#50): it means measuring
     * every stored track, and it has to happen once rather than every time a run finishes.
     *
     * Measuring a Run is minutes of arithmetic on a long history, and the Run is read at the start
     * of it. A stated distance corrected in that window — or the Run deleted — is a change that
     * scores itself and mends the book behind it, so a rewrite landing afterwards out of the effort
     * measured *before* it would put the old number back, over a mend that had already been made.
     * Nothing later would find it: only the top three are stored, so the effort the correction
     * promoted exists nowhere else, and a Run marked scored is never revisited. That window was
     * always here in principle, and the launch pass is what makes it wide — every historical Run
     * queued behind one another after the v22 migration.
     *
     * So the Run is read again inside the transaction that writes the book, and the write is
     * abandoned if the effort it measured is no longer the Run's own. Inside, because the database
     * takes one writer at a time: either the correction has committed by then and this reads it, or
     * it commits afterwards and its own mend has the last word.
     *
     * Cheaper than a lock, and the right shape: nothing is made to wait behind minutes of
     * arithmetic. The cost of abandoning is that the Run keeps owing a scoring, which the next
     * launch pays against a book nobody is moving.
     */
    private suspend fun scoreUnlessOvertaken(sessionId: Long): List<Achievement>? {
        val session = store.run(sessionId) ?: return emptyList()
        // The same accuracy-gated points the map, the splits and the GPX export are built from, so a
        // fix the run itself refused cannot come back as a record nobody ran.
        val stated = statedEffortsOf(sessionId)
        val efforts = bestEffortsOf(session, store.track(sessionId), stated = stated)
        if (efforts.isEmpty()) return emptyList()

        var earned: List<Achievement>? = null
        store.inTransaction {
            val now = store.run(sessionId)
            if (now == null || !now.contestsAs(session) || statedEffortsOf(sessionId) != stated) {
                Log.d(TAG, "Run $sessionId changed while it was being measured; leaving it unscored")
                return@inTransaction
            }
            val rewritten = standingsAfter(store.medals(), sessionId, efforts)
            store.replaceMedalsOfTypes(efforts.map { it.type }, rewritten)
            // The same efforts, banked whole, in the same commit that ranks them (#75). One
            // transaction because they are one measuring: a book written without them would leave
            // the Records section a top ten short of a Run it has already given a medal to, and
            // nothing afterwards goes back for either.
            bankEfforts(sessionId, efforts)
            earned = rewritten.filter { it.sessionId == sessionId }
        }
        return earned
    }

    /**
     * Banks what one Run is worth at the Records it contested, over whatever the last measuring of
     * it said (#75).
     *
     * Called only from inside the transaction that writes the record book, never on its own: these
     * rows and the medals are one measuring, and a caller free to write one without the other is a
     * caller free to make them disagree.
     *
     * A re-scoring replaces this Run's rows rather than joining them, which the row's own key does
     * ([RunEffortRow] is keyed by the Run and the Record) — so scoring a Run twice leaves it holding
     * one claim at each Record rather than racing itself, the same promise [standingsAfter] keeps
     * for the medals.
     *
     * Only the Records the Run still contests are touched. A Record it has stopped contesting
     * altogether — a stated time withdrawn, a Walk marked — is not this path's to clear and never
     * was: those go through the rebuild, which wipes the Record whole and writes back only what
     * still stands.
     */
    private suspend fun bankEfforts(sessionId: Long, efforts: List<BestEffort>) {
        store.putEfforts(efforts.map { RunEffortRow(sessionId, it.type, it.value) })
    }

    /**
     * Whether two readings of the same Run would put the same efforts to the book: everything
     * [bestEffortsOf] measures a Run by, and nothing else (#210).
     *
     * Deliberately not the whole row. A Run's feel, its note and its Effort Score can all be written
     * while history is being measured — the Effort backfill runs at the same launch — and none of
     * them can change a distance or a duration, so none of them is a reason to abandon a scoring.
     *
     * The Walk mark is here for the opposite reason (#275): it changes what the Run is worth at
     * every record at once, from everything it measured to nothing at all. Marked in the window, the
     * Run scores itself and mends the book behind it, and a rewrite landing afterwards out of the
     * efforts measured before the mark would put its medals straight back.
     *
     * What a Run has been *told* it holds is not in the row at all, so the caller checks that
     * separately against the same table (#282) — for the same reason and against the same window: a
     * stated Best Effort corrected while history is being measured scores itself and mends the book
     * behind it, and a rewrite landing afterwards out of the older claim would put the old time
     * back over a mend already made.
     */
    private fun RunnerSession.contestsAs(other: RunnerSession): Boolean =
        endTime == other.endTime &&
            runMode == other.runMode &&
            durationSeconds == other.durationSeconds &&
            distanceKm == other.distanceKm &&
            isWalk == other.isWalk

    /**
     * Scores a Run and, only once that has landed, writes down that it has been scored (#210).
     *
     * The order is the whole of it, which is why the two are one function rather than a rule three
     * callers are asked to remember. [scoreUnlessOvertaken] is the work; the mark is the receipt,
     * and it is written after — never inside the scoring, and never in the same breath as the row
     * being stamped finished. Every way the work can end short of finishing therefore leaves the Run
     * owing a scoring: the process reclaimed, the write thrown. That debt costs one redundant
     * re-score at the next launch, which is safe, where a receipt written early would cost the Run
     * its medals for good.
     *
     * Returns the medals the Run holds afterwards — an empty list for a Run that won nothing — and
     * throws where the scoring throws: an unmarked Run being precisely what the caller wants left
     * behind.
     *
     * A Run that changed while it was being measured is one of those ways of ending short: nothing
     * was written to the book (see [scoreUnlessOvertaken]), so nothing is marked either, and
     * the debt stands for the next launch to pay.
     *
     * Called for a statement too, and not only for a Run finishing (#282). A Stated Distance or a
     * stated Best Effort re-scores a Run the book has already seen — but the mark is what stops the
     * book ever looking again, so a claim stored against a marked Run is a medal nobody goes back
     * for if the scoring behind it ends short. The repository's `writeAndScore` lifts the mark first
     * for exactly that reason, which leaves this to hand it back.
     */
    suspend fun scoreAndMark(sessionId: Long): List<Achievement> {
        val earned = scoreUnlessOvertaken(sessionId) ?: return emptyList()
        store.markScored(sessionId)
        return earned
    }

    /**
     * Scores every finished Run the book never measured, at launch (#210).
     *
     * A Run is scored the moment it finishes, and that is the only moment anything offers it to the
     * book — so a scoring that is missed is missed for good. The process can be killed between the
     * row being stamped finished and the book being written; the write itself can throw and be
     * logged. Nothing revisits it afterwards: the interrupted-run rescue only looks at Runs with no
     * end time, a later Run's scoring ranks only itself, and the seeding pass declines once history
     * carries its mark. This is the pass that goes back for them.
     *
     * **Silent while history is still owed its seeding.** That pass is about to measure all of
     * history at once and its book is the better one — only a rebuild can fill a hole *below* the
     * stored top three — so scoring Runs one at a time in front of it would be work done twice for
     * a worse answer. It marks the Runs it measured itself, which leaves this with nothing to do.
     *
     * One Run at a time, each marked as its scoring lands, so a pass cut short keeps what it paid
     * for and the next launch picks up the rest. A Run that cannot be scored costs the others
     * nothing and stays owed. Failures are logged and never thrown: a book that cannot be written
     * is not a reason to take the app down on the way to the first screen.
     */
    suspend fun scoreMissed() {
        if (store.historySeeded() != true) return
        val sessionIds = store.runsOwedScoring()
        var scored = 0
        sessionIds.forEach { sessionId ->
            try {
                scoreAndMark(sessionId)
                scored++
            } catch (e: Exception) {
                Log.w(TAG, "Could not score run $sessionId; leaving it for next launch", e)
            }
        }
        if (sessionIds.isNotEmpty()) {
            Log.d(TAG, "Scored $scored of ${sessionIds.size} run(s) the book had missed")
        }
        // This pass is the one that pays off a wholesale fill, so it is the one that hands the debt
        // back — and only once it has been through every Run it found, which is why it no longer
        // returns early on an empty list: a launch that finds nothing owing is a launch that has
        // just finished the fill, or a launch after one where the migration un-scored nothing at
        // all. Anything that cuts the loop short — the process reclaimed, the scope cancelled —
        // leaves the fill standing for the next launch to finish, which is the whole reason the
        // fact is in the database. See [handBackTheWholesaleFill] for why a Run that could not be
        // measured does not hold it up.
        handBackTheWholesaleFill()
    }

    /**
     * Hands back the wholesale-fill debt, once the pass that was paying it has been through the
     * whole of the work it found (#75).
     *
     * Asked before it is written, so a launch that was owed nothing does not wake the Records
     * section with a write that changes no answer.
     *
     * **After the sweep, not after a perfect sweep.** A Run the pass could not measure — an
     * unreadable track, a write that threw — keeps its own debt and is tried again at the next
     * launch, and that is the right place for it. But the fill is a statement about the *table*,
     * and once every owed Run has been offered to the book the table is as whole as this history
     * can make it: one Run's claims missing from a top ten is a small, self-mending wrong, while a
     * Records section hidden for ever behind a Run that will never measure is a permanent one. The
     * runner would be left with a screen that says "still measuring your runs" until they delete
     * the Run, with nothing on it to tell them why.
     */
    private suspend fun handBackTheWholesaleFill() {
        if (!store.wholesaleFillOwed()) return
        store.markWholesaleFillPaid()
    }

    /**
     * Puts every Run already in history to the record book, once (#50).
     *
     * #49 scores a Run as it finishes, which leaves the history recorded before it shipped — years
     * of it — holding medals nobody ever awarded. This is the pass that awards them: every stored
     * track measured, the whole book built from all of it at once, so old Runs show the
     * achievements they earned at the time and the book means "all time" rather than "since the
     * update".
     *
     * Once, because it is minutes of GPS arithmetic over a long history: the mark is stored only
     * after the rebuilt book has been committed, so a pass killed part-way through — the process
     * reclaimed, the phone off — simply runs again at the next launch rather than leaving half a
     * book behind. Nothing is written until the rebuild is complete, so there is no half state to
     * resume from and nothing to clean up.
     *
     * Deliberately *not* gated on the book being empty: a fresh install scores its first Run the
     * moment it finishes, and a book with one Run in it would look seeded while the rest of a
     * restored history was still unread. The mark travels with the history it describes — a restored
     * archive clears it (see [com.example.runningapp.SettingsRepository.restoreArchivedSettings]),
     * and a Clear-storage wipe takes it with the settings, which is the safe direction: an
     * unnecessary reseed costs a few minutes of background work and produces the same book.
     */
    suspend fun seedFromHistory() {
        if (store.historySeeded() != false) return

        // Noted before a line of history is read, and asked again before the mark is written. Both
        // under the lock, so a delete cannot slip into the gap between looking and deciding.
        var deletesBefore = 0L
        var deleteRunningBefore = false
        seedingMark.withLock {
            deletesBefore = deletesStarted.get()
            deleteRunningBefore = deletesActive.get() != 0
        }
        // Which Runs this pass is about to settle the debt of (#210), read before it measures
        // anything: everything on this list is finished now, so the rebuild below is certain to
        // measure it. A Run that finishes while the measuring is going on is deliberately not here
        // — it scores itself, and if that scoring is missed the debt is still its own to owe.
        val runsOwedScoring = store.runsOwedScoring()
        try {
            val book = rebuild(RecordType.entries)
            if (book == null) {
                Log.d(TAG, "A stated Best Effort changed while history was being scored; leaving it for next launch")
                return
            }
            // Only now: until this lands, the pass is still owed. And not at all if a delete ran at
            // any point alongside it — one that started after the baseline was taken, one still
            // running now, or one already under way when it was taken, which a count of *starts*
            // cannot see on its own. That delete read history as unseeded, so it lifted no mark and
            // will hand none back, and its own mend can still be cut short: marking here would
            // stand over a book with a hole in it that nothing can find. Left unmarked, the next
            // launch reseeds, which is minutes of background work for the right book.
            // Asked and answered under the lock, so a delete arriving between the question and the
            // write is one that waits rather than one this write talks over.
            seedingMark.withLock {
                if (deletesStarted.get() != deletesBefore ||
                    deleteRunningBefore ||
                    deletesActive.get() != 0
                ) {
                    Log.d(TAG, "A run was deleted while history was being scored; leaving it for next launch")
                    return
                }
                store.markHistorySeeded()
                // In the same breath as the whole-history mark, and on the same terms: this book
                // measured every one of them, so none of them is owed a scoring of its own (#210).
                // Nothing is marked on the path above, where the pass declines the mark — that book
                // may have a hole in it, and a Run marked scored against it would never be revisited.
                store.markScored(runsOwedScoring)
                // And the wholesale fill with them (#75): this pass rewrote the whole of
                // `run_efforts` in the transaction that just committed, so whatever fill was
                // outstanding — the one the v36 to v37 migration raised, or one a restored archive
                // arrived still owing — has been paid in full by a book built over all of history at
                // once. On the declining paths above nothing is written and the fill stands, which is
                // right: the table was left as it was, and as it was is what the debt describes.
                handBackTheWholesaleFill()
            }
            Log.d(TAG, "Seeded the record book from history: ${book.size} medal(s) awarded")
        } catch (e: Exception) {
            // Caught rather than left to the launch scope, which has no handler behind it: a book
            // that cannot be built is a card the runner does not see yet, not a reason to take the
            // app down on the way to the first screen. Unmarked, so the next launch tries again.
            Log.w(TAG, "Could not seed the record book from history; leaving it for next launch", e)
        }
    }

    /**
     * The one way a Run leaves history, and the one way a medal it holds is written down to a
     * smaller number: what it held noted, the change made durable, and only then the book mended
     * (#50, #231).
     *
     * Both are the same shape, which is why they are the same function. A deletion takes a medal off
     * the book; a distance corrected downward demotes one. Either way the Run behind it — the one
     * that should move up — exists nowhere but in history, because only the top three are banked, so
     * neither can be put right by re-scoring one Run. Nothing here is about the deletion in
     * particular except the words: [change] is whatever the sessions are about to be made into, and
     * [mendOnly] narrows the mend where the caller knows which record can have moved. Everything
     * below says "delete" because deletion is where all of it was worked out, and every line of it
     * holds for a correction unchanged — including the counters ([deletesStarted], [deletesActive]),
     * which count anything that can leave the book standing short, not deletions specifically.
     *
     * **The backup is refreshed twice, and the first one is the important one.** The Downloads
     * snapshot is what a Clear-storage restore reads, so until it is rewritten it still holds the
     * deleted Run. Mending the book measures every stored track — minutes on a long history — and a
     * process killed inside that window would leave the deletion committed here and undone there,
     * so the runner could restore a Run they had deleted. The snapshot therefore goes out the
     * moment the rows are gone, before anything slow, and the deletion is durable from that point
     * whatever happens next. What the change itself owes goes out ahead of even that
     * ([onceRowsAreDurable]): the snapshot is a copy of rows that have already gone, while the
     * coaching taken back is the thing the deletion promised the runner.
     *
     * The second refresh carries the mended book — the promotions behind the deleted Run — into the
     * snapshot, and is skipped entirely when the Run held nothing, which is the ordinary case. Its
     * failing costs a restored history a record standing two deep until something contests it; the
     * first one's failing would cost the runner a deletion that did not stick.
     *
     * **The seeding mark is lifted first of all, and handed back last.** From the moment the rows
     * go the medals go with them, so until the book is mended a record the deleted Run held stands
     * short — and short is a hole nothing else can find: only the top three are ever stored, so the
     * fourth-best effort that should move up exists nowhere but in the tracks, and no future Run
     * finishing can promote it. The debt is therefore written down *before* the delete, not after
     * it: everything between here and the mend can be cut short — the process reclaimed, the view
     * model's scope cancelled by the runner leaving the screen mid-backup — and a debt recorded
     * afterwards would be a debt those endings skip. Recorded first, every one of them leaves
     * history owing a reseed, which the next launch pays.
     *
     * Lifted for deletes that turn out to hold nothing too, because what a Run held is not known
     * until it is already gone. That costs a needless reseed only if the process dies inside the
     * delete itself, and a reseed of unmoved history arrives at the same book.
     *
     * Only if it was marked to begin with: an install whose seeding pass has not finished (or has
     * failed) is already owed one, and marking it seeded at the end of a two-record repair would
     * cancel a debt this never paid. And only if this delete was the only one there was: another
     * one mending different records is a debt of its own, and this one's mend landing says nothing
     * about whether that one's will. "The only one" is asked two ways, because one delete can be
     * *behind* another as easily as ahead of it: none begun since this one started, and none still
     * running once this one has stepped out of the count. Deletes that overlapped in either
     * direction leave the reseed owed, which the next launch pays.
     */
    suspend fun changeAndRepair(
        sessionIds: List<Long>,
        mendOnly: Collection<RecordType>? = null,
        /**
         * What else the change owes, run the moment the rows are durable — before the backup and
         * before the mend, which is the slow part (#156). First of everything that follows the
         * commit, because a delete's promise to the runner is that the coaching goes with the Run,
         * and everything after the commit can be cut short.
         *
         * Uncancellable, which closes one of the two ways it can be cut short and not the other. The
         * runner leaving the history screen cancels this scope, and there is no second attempt and
         * no pass at startup to find the work undone — so cancellation must not reach it. A process
         * *reclaimed* still can, and what is left of that is set out on the repository's
         * `deleteRuns`.
         *
         * Nothing here is allowed to stop the mend either: whatever it needs to do, a failure of it
         * is a smaller loss than a record book left standing short.
         */
        onceRowsAreDurable: (suspend () -> Unit)? = null,
        /**
         * Held from before the rows go until [onceRowsAreDurable] has finished, and let go before
         * the mend — so a caller that has to make the change and what it owes look like one act to
         * everybody else can say so (#156). Null where nothing else is watching.
         */
        whileTheRowsGo: Mutex? = null,
        change: suspend () -> Unit,
    ) {
        var mine = 0L
        var wasSeeded = false
        // Only ever true once the mend has landed, so every way out of the block below — thrown,
        // cancelled — leaves the debt standing.
        var repaired = false
        // Joining is one indivisible act: take a number, join the count, and lift the mark. Held
        // across the DataStore read and write because that is the check-and-act another delete has
        // to be kept out of — one entering here between another's read and its write would find the
        // mark already down, lift nothing, and be owed nothing back.
        //
        // Uncancellable, like the leaving below and for the same reason: both suspend, and a
        // cancellation landing after the count was joined but before the block finished would leave
        // a delete counted that is not running. See [deletesActive].
        //
        // Inside the `try` below, because joining is not one write but three — take a number, join
        // the count, then read and lower the mark — and the last two suspend on DataStore, which can
        // throw. A join that got as far as the count and no further would otherwise leave a delete
        // counted that never ran and never leaves, and that phantom stops every later delete and
        // every seeding pass in this process from calling the book whole: a full reseed at every
        // launch, for the life of the install. [joined] is raised the instant the count is joined,
        // so the leaving below answers for exactly the part that happened.
        var joined = false
        try {
            withContext(NonCancellable) {
                seedingMark.withLock {
                    mine = deletesStarted.incrementAndGet()
                    deletesActive.incrementAndGet()
                    joined = true
                    wasSeeded = store.historySeeded() == true
                    if (wasSeeded) store.clearHistorySeeded()
                }
            }
            // Read and removed in one transaction, so nothing can award these Runs a medal in
            // between. The seeding pass commits a whole book at once and a delete arrives from the
            // history screen while it may still be running: read outside, a medal landing in that
            // gap would be cascaded away by the delete and never appear in `losing`, leaving the
            // record it took vacant with no repair coming and the pass marking history complete
            // over the hole.
            var losing = emptyList<RecordType>()
            whileTheRowsGo.holding {
                store.inTransaction {
                    losing = recordsHeldBy(sessionIds).filter { mendOnly == null || it in mendOnly }
                    change()
                }
                // Before the backup, and uncancellable — see [onceRowsAreDurable]. Both narrow the
                // same window, the one where the rows have gone and what stood on them has not been
                // taken back yet: ahead of the backup it is one settings write wide instead of a
                // whole copy of history, and uncancellable it is not walked out of by the runner
                // leaving the screen. Narrowed, not closed — a process reclaimed inside the write
                // itself still lands there, which the repository's `deleteRuns` says plainly.
                withContext(NonCancellable) { onceRowsAreDurable?.invoke() }
            }
            refreshHistoryBackup?.invoke()

            // Every changed Run's banked claims re-taken, whether or not it held a medal (#75).
            //
            // The book above is mended only where a place moved, which is all it can be: beyond
            // bronze it remembers nothing, so there is nothing there to go stale. The banked rows
            // are the opposite — they hold every claim a Run ever made, and a Run that has stopped
            // making one leaves a row nothing else would ever look at again. A Run marked a Walk
            // that placed fourth at 5 km is exactly that: it loses no medal, so `losing` is empty
            // and the rebuild does nothing, and its time would stand in that Record's top ten for
            // ever.
            //
            // Asked of the Run rather than of the Records, because what changed is what the Run is
            // worth: re-measuring it whole is the only reading that can say a Record it used to
            // contest is one it no longer does.
            val rebanked = rebank(sessionIds)
            repaired = repair(losing, remeasured = sessionIds) && rebanked.landed
            // A second snapshot, because the one above was taken before either of these ran and is
            // now behind whatever they wrote. Owed by the re-banking as much as by the mend (#75):
            // a fourth-place Run marked a Walk moves no medal, so `losing` is empty and the book is
            // never rebuilt — and its banked rows still went. Restored from a snapshot taken before
            // that, the Walk would climb back into the Records top ten and its trend, which is the
            // very thing this change took it out of.
            //
            // On what actually moved rather than on having got here at all, because the backup is a
            // whole copy of history and every edit to a Run's feel or its note comes through this
            // path. A re-measuring that came back with the same claims has left the snapshot as good
            // as it was.
            if (losing.isNotEmpty() || rebanked.movedRows) refreshHistoryBackup?.invoke()
        } finally {
            // Leaving is the same act in reverse, and under the same lock: step out of the count,
            // look around, and hand the mark back only if there is nobody left to speak for. Sampled
            // and written together, so a delete arriving in between cannot be one this write ignores.
            //
            // In a finally so a cancelled delete — the runner leaving the history screen — stops
            // holding every other caller's mark down. It leaves the debt behind it either way, since
            // `repaired` is only true once the mend has landed.
            //
            // And uncancellable, because this *is* the cancellation path and taking the lock
            // suspends: run in the cancelled scope it would throw before the count came down, and
            // the phantom delete left behind would stop every later delete and every seeding pass in
            // this process from ever calling the book whole — a full reseed at every launch, for the
            // life of the install.
            //
            // And only if the count was joined, since a join that threw before it landed has
            // nothing standing in it to take out.
            if (joined) withContext(NonCancellable) {
                seedingMark.withLock {
                    deletesActive.decrementAndGet()
                    val onlyDelete = deletesStarted.get() == mine && deletesActive.get() == 0
                    if (wasSeeded && repaired && onlyDelete) store.markHistorySeeded()
                }
            }
        }
    }

    /**
     * The two halves of "was a delete going on while I worked?", which is the question anything
     * about to call history scored has to answer before it does (#50).
     *
     * Asked by the seeding pass and by a delete's own mend, because either can overlap a delete and
     * the result is the same shape: a marked book with a hole in it. A delete that reads history as
     * unseeded lifts no mark, because the seeding pass owes one already — but if that pass then
     * finishes and marks history complete while the delete's mend is cut short, the record the
     * deleted Run held stands short with the mark saying otherwise. Two overlapping deletes reach
     * it from the other side: the first one's mend landing says nothing about whether the second's
     * will, so it must not be the one to call the book whole. Nothing later can find what either
     * leaves behind, since only the top three are ever stored.
     *
     * **Both counters, because neither answers it alone.** [deletesStarted] only ever climbs, so
     * comparing it against a baseline catches a delete that began *after* the baseline was taken
     * and nothing else — a delete already under way at that moment is invisible to it, and one that
     * is still running when the baseline is checked again looks identical to one that finished.
     * [deletesActive] is what says a delete is happening *now*. Asked at both ends, they cover a
     * delete ahead, behind, or alongside.
     *
     * Counters rather than a lock, because the lock would be the wrong shape: seeding is minutes of
     * arithmetic, and a delete made to wait behind it is a history screen that does not respond.
     * These let everything run and refuse only the *mark* when two of them overlapped, which costs
     * a reseed at the next launch and arrives at the same book.
     *
     * In memory only, and that is enough: they exist to catch two things overlapping inside one
     * process, and a process that dies takes any unwritten mark with it. Which is also why there
     * must be one [RecordBook] per process, built once in [com.example.runningapp.AppContainer].
     *
     * **[deletesActive] must come back down whatever happens**, which is why both the joining and
     * the leaving run uncancellable. A delete counted but not running is not a wrong book — it errs
     * the safe way, refusing the mark — but it never stops erring: every later delete and every
     * later seeding pass would decline to call the book whole, so the install pays a full reseed at
     * every launch for as long as it lives.
     */
    private val deletesStarted = AtomicLong(0)
    private val deletesActive = AtomicInteger(0)

    /**
     * Held while the seeding mark is being looked at and moved, and never while anything is
     * measured (#50).
     *
     * The counters say who was working; this is what makes *asking them and acting on the answer*
     * one act. Sampled and then written without it, a delete could enter in the gap — find the mark
     * already down so it lifts nothing and is owed nothing back — while the write it arrived after
     * put the mark up over a mend that had not landed. Every reader of the counters therefore does
     * its looking and its writing inside here.
     *
     * What is *not* inside here is the work: the rebuild, the backup, the delete's own transaction
     * all happen with the lock released. Nothing held across it takes longer than a DataStore edit,
     * so a delete never waits on a seeding pass, which is the whole reason these are counters and
     * not a lock around the work itself.
     */
    private val seedingMark = Mutex()

    private suspend fun recordsHeldBy(sessionIds: List<Long>): List<RecordType> =
        store.medalsHeldBy(sessionIds).map { it.type }.distinct()

    /**
     * Re-takes what [sessionIds] are worth at every Record, replacing whatever was banked for them
     * (#75). Returns whether it landed and whether it moved anything — see [Rebanking].
     *
     * Its own attempt and its own transaction, like [repair]: the Runs have already changed by the
     * time this runs, and a re-banking that cannot be written must not take a Walk mark down with
     * it. Left undone it leaves the debt owed, which the next launch's seeding pass pays against a
     * book and a set of rows nobody is moving.
     *
     * A Run that is *gone* re-banks to nothing and needs no help: its rows cascaded away with it
     * ([RunEffortRow]), and it is not in history to be measured. The delete path therefore reaches
     * here and finds nothing to do, which is the right answer rather than a special case.
     *
     * Whole rather than at named Records, and that is the point of it: only a whole measure can say
     * that a Record the Run used to contest is one it no longer does.
     *
     * A Run that moved while it was being measured is left exactly as it stands, and leaves the debt
     * owed — see the abandonment below.
     */
    private suspend fun rebank(sessionIds: List<Long>): Rebanking {
        // Raised outside the `try` and never lowered, because a re-banking that threw on the third
        // Run has still moved the first two: what is on disk is stale from the first row that
        // differed, whether or not the rest of the work got there.
        var movedRows = false
        // Raised by a Run that moved out from under the measuring, for the same reason [landed] is
        // lowered by a throw: the rows this pass was going to write were never written, so the Run
        // is still owed a re-banking and the debt has to outlive this call. Per pass rather than per
        // Run, because the debt is paid by one reseed of the whole book either way.
        var overtaken = false
        return try {
            sessionIds.forEach { sessionId ->
                val session = store.run(sessionId)
                // Measured outside the transaction below, which is the same split [rebuild] makes:
                // reading and measuring a track is real work, and the database's write lock is not
                // the place to do it.
                val stated = session?.let { statedEffortsOf(sessionId) }.orEmpty()
                val efforts = session
                    ?.let { effortsAt(it, RecordType.entries, stated) }
                    .orEmpty()
                val rows = efforts.map { RunEffortRow(sessionId, it.type, it.value) }
                store.inTransaction {
                    // The Run and what it has been told it holds are asked for again, inside the
                    // transaction that replaces its rows, and the replacement is abandoned if either
                    // has moved (#75). [scoreUnlessOvertaken]'s rule, against the same window and
                    // for a sharper reason: a *second* edit landing after this pass measured the
                    // Run scores itself and banks its own rows, and this one committing afterwards
                    // out of a reading taken before it would delete them and put the older claims
                    // back. Nothing later would find it. A withdrawn fourth-place claim re-stated in
                    // that window is the whole of it: the effort held no medal, so `losing` is empty
                    // and no rebuild ever visits the Record again.
                    //
                    // Inside, because the database takes one writer at a time — either the newer
                    // edit has committed by now and this reads it, or it commits afterwards and its
                    // own banking has the last word. Cheaper than a lock, and nothing is made to
                    // wait behind a walk of a track.
                    //
                    // A Run that was already gone when it was measured is not overtaken by still
                    // being gone: its rows went with it ([RunEffortRow]), and re-banking it to
                    // nothing is the right answer. One reappearing would be a different Run at the
                    // same id, which is nothing this reading can speak for either.
                    val now = store.run(sessionId)
                    val moved =
                        if (session == null) now != null
                        else now == null || !now.contestsAs(session) || statedEffortsOf(sessionId) != stated
                    if (moved) {
                        Log.d(TAG, "Run $sessionId changed while what it is worth was being re-taken; leaving its claims")
                        overtaken = true
                        return@inTransaction
                    }
                    // Read inside the same transaction that replaces them, so what is compared is
                    // what is overwritten. Sets rather than lists: a re-measuring that came back
                    // with the same claims in another order has moved nothing the runner or the
                    // Records section could ever see.
                    if (store.effortsFor(sessionId).toSet() != rows.toSet()) movedRows = true
                    store.replaceEffortsFor(sessionId, rows)
                }
            }
            Rebanking(landed = !overtaken, movedRows = movedRows)
        } catch (e: Exception) {
            Log.w(TAG, "Could not re-bank what ${sessionIds.size} run(s) are worth", e)
            Rebanking(landed = false, movedRows = movedRows)
        }
    }

    /**
     * What one pass of [rebank] did: whether it landed, and whether it changed anything (#75).
     *
     * Two answers rather than one because they are asked by different callers for different reasons.
     * [landed] is the debt — a re-banking that could not be written leaves history owing a full
     * reseed, exactly as a mend that could not be written does. Owed by a re-banking that *declined*
     * to be written too: a Run overtaken mid-measure is one whose rows this pass never replaced, and
     * the newer scoring behind it is the one that owns them now. [movedRows] is the snapshot on disk
     * — the backup is a whole copy of history and taking one is not free, so it is refreshed when
     * the rows behind the Records section actually moved and not on every change that reaches here.
     *
     * A pass can be both: one Run re-banked and the next one thrown on leaves rows moved and the
     * debt owed at the same time.
     */
    private data class Rebanking(val landed: Boolean, val movedRows: Boolean)

    /**
     * Rebuilds the records a deleted Run held, so the places below it move up (#50).
     *
     * Only the records it actually held: deleting a Run that never won anything changes nothing
     * about the book, and must not cost a re-measure of the whole history to prove it — so an empty
     * list is nothing to do rather than a whole history to walk, and counts as mended.
     *
     * Its own attempt, because the Run is already deleted by the time this runs. A book that cannot
     * be rewritten leaves a record two deep until the next Run contests it, which is a wrong number
     * on a card; failing here would instead leave the runner staring at a delete that appeared not
     * to work, with the run gone anyway. Returns whether it landed, so the caller can leave history
     * owing a full reseed when it did not.
     */
    private suspend fun repair(types: List<RecordType>, remeasured: List<Long>): Boolean {
        if (types.isEmpty()) return true
        return try {
            // Null is the rebuild declining to commit, which is not a failure but is not a repair
            // either: the book stands as it was and the debt stays owed.
            rebuild(types, remeasured) != null
        } catch (e: Exception) {
            Log.w(TAG, "Deleted run(s) held ${types.size} record(s) the book could not be rebuilt for", e)
            false
        }
    }

    /**
     * Measures the whole history and writes [types] of the record book from it, returning what it
     * wrote (#50).
     *
     * The measuring is done outside the transaction and the writing inside it, which is the split
     * that matters: reading and measuring every stored track is minutes of work, and holding the
     * database's write lock for it would stall the per-second inserts of a Run being recorded.
     * Everything that *changes* the book is one commit, so the book is never half rewritten.
     *
     * A Run that finished while the measuring was going on has already scored itself, and its rows
     * would be wiped by the rewrite — so the standing rows of any Run this pass cannot have the last
     * word on are carried in as claims of their own. Their stored value is the effort they were
     * awarded for, so they can be ranked beside the freshly measured ones without measuring again.
     *
     * Which Runs those are is decided by what history looked like when it was read, not by what the
     * measuring came back with. A Run still being recorded then *is* in the list and is worth
     * nothing until it finishes — which is exactly the Run most likely to finish and score itself
     * while this pass is still measuring — so its rows are kept. A Run that was already finished is
     * answered for by this pass whatever it measured to, including nothing at all, and its standing
     * rows go. That difference is the whole of it: a Run marked a Walk contests nothing, so it
     * measures to nothing, and judging the carry-in on emptiness instead would conflate it with the
     * Run nobody could measure yet and hand its old rows straight back. Its time would return to the
     * Records section at every reseed and no repeated pass could ever shift it (#75).
     *
     * [remeasured] is the Runs this rebuild was called *for*, whose standing rows are therefore
     * never carried in — the whole point of the pass is to replace them. Without it a Run that now
     * measures to nothing is indistinguishable from one that was never measured, and its old row
     * comes straight back: a Stated Distance withdrawn would keep the medal it held at the number it
     * no longer has (#231). Empty for the seeding pass, which is measuring history rather than
     * mending it, and harmless for a deletion, whose rows have already cascaded away.
     *
     * On [Dispatchers.Default] because the measuring is geodesic arithmetic over every stored
     * track — minutes of it on a long history. The callers are a launch-time pass and a delete from
     * the history screen, and the delete arrives on the main thread.
     */
    private suspend fun rebuild(
        types: List<RecordType>,
        remeasured: List<Long> = emptyList(),
    ): List<Achievement>? {
        // Every statement in history, in one read rather than one per Run: a query inside the loop
        // below is a round trip per Run in the runner's life, to fetch at most five rows. Read
        // before the measuring, and checked again after it — see the abandonment below.
        val statedBefore = claimsAt(types)
        // Regrouped out of the very list the abandonment below compares against, rather than read a
        // second time: the claims a Run is measured against have to be the claims that were
        // compared, or the rebuild could commit having measured one reading and checked another.
        val statedByRun: Map<Long, Map<RecordType, Double>> = statedBefore
            .groupBy({ it.sessionId }) { it.type to it.seconds.toDouble() }
            .mapValues { (_, claims) -> claims.toMap() }
        // One Run at a time, because a track is thousands of points and the whole history's worth
        // of them at once is not something to hold in memory. Unfinished Runs are in this list and
        // measure to nothing, which is what [bestEffortsOf] says they are worth.
        val history = store.runs()
        val measured = withContext(Dispatchers.Default) {
            history.map { session ->
                RunEfforts(session.id, effortsAt(session, types, statedByRun[session.id].orEmpty()))
            }
        }
        // The Runs this pass has the last word on: the ones history showed as finished when it was
        // read, whether or not they turned out to be worth anything, plus the ones the rebuild was
        // called for. Everything else — a Run still being recorded, a Run that appeared after the
        // read — keeps whatever is standing for it. See the carry-in above.
        val measuredIds = history.filter { it.isFinished() }.map { it.id }.toSet() + remeasured

        var written: List<Achievement>? = null
        store.inTransaction {
            // The statements are asked for again, inside the transaction that writes the book, and
            // the whole rebuild is abandoned if they have moved (#282). A Run finishing mid-measure
            // is already answered — it scores itself, and its standing rows are carried in as
            // `unseen` — but a *statement* has no such answer: stating or improving one takes the
            // direct scoring path rather than this one, so a rebuild committing afterwards out of
            // the claims it read minutes ago would overwrite that scoring with the old time, or
            // with no row at all. Nothing later would find it: only the top three are stored.
            //
            // Inside, because the database takes one writer at a time — either the statement has
            // committed by now and this sees it, or it commits afterwards and its own scoring has
            // the last word. Abandoning costs a book left as it was and the seeding debt still
            // owed, which the next launch pays against a history nobody is moving.
            if (claimsAt(types) != statedBefore) {
                Log.d(TAG, "A stated Best Effort changed while the book was being rebuilt; leaving it")
                return@inTransaction
            }
            val unseen = store.medals()
                .filter { it.type in types && it.sessionId !in measuredIds }
                .groupBy { it.sessionId }
                .map { (sessionId, rows) ->
                    RunEfforts(sessionId, rows.map { BestEffort(it.type, it.value) })
                }
            val book = recordBookOf(measured + unseen)
            store.replaceMedalsOfTypes(types, book)
            // The efforts behind the book, rewritten on exactly the terms the book is (#75): the
            // Runs this pass measured, plus what is banked for the Runs it did not — which is the
            // same carry-in, decided by the same [measuredIds], so a Run that finished mid-measure
            // keeps the rows its own scoring wrote rather than being wiped by this one, and a Run
            // that was measured and found to be worth nothing loses the rows it used to hold.
            val carried = store.effortsOfTypes(types).filter { it.sessionId !in measuredIds }
            store.replaceEffortsOfTypes(
                types,
                measured.flatMap { run ->
                    run.efforts.map { RunEffortRow(run.sessionId, it.type, it.value) }
                } + carried,
            )
            written = book
        }
        return written
    }

    /**
     * One statement as the record book's input sees it: which Run made it, at which record, and the
     * time it claims (#282).
     *
     * The claim and not the stored row, which is what lets two readings of an unmoved table compare
     * equal: correcting a statement replaces it, giving the same claim a new id, and an id is not
     * something a record book has ever ranked by.
     */
    private data class Claim(val sessionId: Long, val type: RecordType, val seconds: Int)

    /**
     * Every claim standing at [types], as the thing two readings of it are compared by (#282).
     *
     * Sorted so two reads of an unchanged table compare equal whatever order the database hands
     * them back in.
     */
    private suspend fun claimsAt(types: List<RecordType>): List<Claim> =
        store.allStated()
            .filter { it.type in types }
            .map { Claim(it.sessionId, it.type, it.seconds) }
            .sortedWith(compareBy({ it.sessionId }, { it.type }))

    /**
     * What one Run is worth at [types] — measured off its track, or stated off a treadmill console
     * (#282) — the same measurement the book ranks.
     *
     * For a reader that is not the book but must agree with it: a Stage's Best Effort requirement
     * (#291) is answered by exactly this, so a Run the book would rank at a time is a Run the Stage
     * sees at that time.
     */
    suspend fun worthAt(session: RunnerSession, types: List<RecordType>): List<BestEffort> =
        effortsAt(session, types, statedEffortsOf(session.id))

    // --- What the book holds, for readers that are not the book (#484) ---
    //
    // The one door to the record tables: the repository reads medals, claims and the fill flag
    // through here rather than holding the tables itself. See the matching part of [RecordBookStore].

    fun quickestInHistoryFlow(type: RecordType): Flow<HistoryBestEffort?> = store.quickestInHistoryFlow(type)
    fun medalCountsFlow(): Flow<List<SessionMedalCount>> = store.medalCountsFlow()
    fun recordsReadingFlow(): Flow<List<RecordsReadingRow>> = store.recordsReadingFlow()
    fun wholesaleFillOwedFlow(): Flow<Boolean> = store.wholesaleFillOwedFlow()
    fun statedForFlow(sessionId: Long): Flow<List<StatedBestEffort>> = store.statedForFlow(sessionId)
    suspend fun statedFor(sessionId: Long): List<StatedBestEffort> = store.statedFor(sessionId)
    suspend fun medalsHeldBy(sessionIds: List<Long>): List<Achievement> = store.medalsHeldBy(sessionIds)

    /**
     * Stores one stated Best Effort. The write alone: the caller puts it inside its own transaction,
     * and scores or mends the book behind it (#282).
     */
    suspend fun state(effort: StatedBestEffort) = store.state(effort)

    /** Takes one stated Best Effort away, on the same terms as [state]. */
    suspend fun withdraw(sessionId: Long, type: RecordType) = store.withdraw(sessionId, type)

    /** What one Run is worth at [types], measuring its track only if one of them needs it. */
    private suspend fun effortsAt(
        session: RunnerSession,
        types: List<RecordType>,
        stated: Map<RecordType, Double> = emptyMap(),
    ): List<BestEffort> {
        // The longest time is asked of the Run's own clock; every other record is measured against
        // ground, so it needs the track read and accuracy-gated first.
        val overGround = types.any { it != RecordType.LONGEST_DURATION }
        val track = if (overGround) store.track(session.id) else emptyList()
        return bestEffortsOf(session, track, types, stated)
    }

    /** What one Run has been told it holds, in the shape the record book ranks (#282). */
    private suspend fun statedEffortsOf(sessionId: Long): Map<RecordType, Double> =
        store.statedFor(sessionId).byType()

    private companion object {
        const val TAG = "Records"
    }
}

/** [act] under this lock where there is one to take, and plainly where there is not. */
private suspend fun Mutex?.holding(act: suspend () -> Unit) {
    if (this == null) act() else withLock { act() }
}
