package com.example.runningapp

import com.example.runningapp.data.SessionRepository
import com.example.runningapp.routes.RouteShaping
import java.util.concurrent.atomic.AtomicBoolean

/** One pass the app pays at launch: the name it is logged under, and the work (#477). */
class LaunchPass(val name: String, val work: suspend () -> Unit)

/**
 * The passes the app pays at every launch, started once per process and in the order they are
 * listed (#477).
 *
 * Each pass goes back for work a previous process owed and could not finish — a Run left
 * interrupted, a history measured before a feature shipped, a debt written down on the way out. On
 * an ordinary launch each reads an empty list and returns.
 *
 * **Started in order, not run in order.** Every pass is launched on the same scope and none waits for
 * another. Nothing needs more than that: each entry in [launchPassesOver] says why the passes around
 * it cannot spoil it, and where two of them could collide it is a lock they share that keeps them
 * apart, not the order they start in.
 *
 * **On the container's scope, never an Activity's.** The latch below is process-wide, so a pass tied
 * to an Activity the runner backs out of would be cancelled with its work half done and never started
 * again for the life of the process. A pass cut short by the process itself leaves its work owed for
 * the next launch: most mark each item as they pay it and keep what they paid, and the record seeding,
 * which commits the whole book at once, simply runs again.
 */
class LaunchPasses(
    private val passes: BackgroundPasses,
    private val inOrder: List<LaunchPass>,
) {
    private val paid = AtomicBoolean(false)

    /** Starts every pass in [inOrder], in order. Only the first call in a process does anything. */
    fun payOnce() {
        if (!paid.compareAndSet(false, true)) return
        inOrder.forEach { passes.launch(it.name, it.work) }
    }
}

/**
 * The launch passes, in the order they are started — the one place a new pass is added (#477).
 *
 * [repository] and [routeShaping] are asked for only when a pass is paid, never while the list is
 * built: the repository sits behind a lazy that opens the database, and the list is built on the way
 * to the first screen, on the main thread.
 *
 * [processStartedAtMillis] is read at the container's construction rather than when the rescue runs,
 * and that is what makes the rescue safe: it draws the line before this process can have started a
 * Run of its own, so nothing it finds can be a Run being recorded now.
 */
fun launchPassesOver(
    repository: () -> SessionRepository,
    routeShaping: () -> RouteShaping,
    processStartedAtMillis: Long,
): List<LaunchPass> = listOf(
    // Runs recorded before moving time (#163) would quote pace over a different clock from today's
    // Runs until this fills them in.
    LaunchPass("moving-time backfill") { repository().backfillMovingTime() },

    // A Run whose process was killed mid-recording never reached the finish that stamps its totals,
    // so it sits in the database invisible to every screen that reads Runs (#192). First of the
    // passes that read Runs, so a Run it finishes is eligible for the rest — though each of them
    // below says why it does not need that.
    LaunchPass("interrupted-run rescue") { repository().rescueInterruptedRuns(processStartedAtMillis) },

    // Puts the history recorded before the record book shipped to the book, and awards the medals
    // those Runs earned at the time (#50). A rescued Run scores itself, and this pass carries over
    // the rows of any Run it did not see, so either order against the rescue leaves the same book.
    LaunchPass("record seeding") { repository().seedRecordsFromHistory() },

    // A Run whose scoring was missed — the process killed on the way to the book, or the write
    // logged and lost — holds no medals and nothing else would ever give it any (#210). A Run this
    // ran past while it was still interrupted is finished by the rescue and is on the next launch's
    // list; while history is still owed its seeding, this declines outright, because the seeding
    // measures every Run at once and marks them itself.
    LaunchPass("missed-record scoring") { repository().scoreMissedRecords() },

    // A Run whose finish sheet was never answered was never put to the Plan, so it holds no
    // graduation and nothing else would ever offer it one (#297). A Run this ran past while it was
    // still interrupted has no end time, so the settlement declines it and leaves its debt for the
    // launch after the rescue finishes it.
    LaunchPass("Stage settlement") { repository().settleStagesMissedAtTheFinish() },

    // A Run judged a walk whose mark could not be written reads wrong, and its settlement is spent
    // (#371). The order against the settlement does not matter: a debt this ran past is one the
    // settlement raised a moment later, and it is still in the table at the next launch.
    LaunchPass("Walk-mark debt") { repository().payWalkMarkDebts() },

    // A delete cut short between the rows and the coaching leaves a Prescription standing on a Run
    // that is gone (#270). None of the passes around this takes a Run out of history, which is the
    // only thing it reads.
    LaunchPass("coaching reconciliation") { repository().reconcileCoachingWithHistory() },

    // Runs recorded before the Effort Score shipped have the beats to work one out and no Score
    // stored (#62). A rescued Run is scored as it is finished; what keeps this and the rescue from
    // colliding is the lock they share ([SessionRepository.backfillEffortScores]).
    LaunchPass("Effort Score backfill") { repository().backfillEffortScores() },

    // What the Segments and the Runs owe each other (#70): a Segment cut before efforts existed, and
    // either side of a walk lost to a process reclaimed half way through it. A Run this ran past
    // while it was still interrupted is not on the list; the rescue walks it against the Segments
    // itself.
    LaunchPass("Segment-timing debt") { repository().payWhatSegmentTimingOwes() },

    // Runs recorded before the weather shipped, or saved offline, have the position and the time to
    // look one up and nothing stored (#81, #79). Minutes of fetching over a whole history, which is
    // why it must not be tied to a screen. It writes five columns nothing else reads and reads none
    // that anything else writes.
    LaunchPass("weather backfill") { repository().backfillWeather() },

    // Runs recorded before matched runs shipped have a track and no shape, so nothing would
    // recognise a route run fifty times until it was run again (#73).
    LaunchPass("Run-shape debt") { repository().payWhatRunShapesOwe() },

    // And the library's half of it: every course kept before this shipped has a line and no shape,
    // so its page would open empty (#74). A course kept since is shaped in the transaction that kept
    // it ([com.example.runningapp.data.RouteDao.keepRoute]).
    LaunchPass("Route-shape debt") { routeShaping().payWhatIsOwed() },
)
