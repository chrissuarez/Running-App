package com.example.runningapp.foreground

/**
 * Whether the Run may still be given work, now that the service may have begun going down (#315).
 *
 * **A gate, and it exists because a wait cannot do this job.** The teardown finishes a Run it took,
 * and before it does so it drains the scopes the Run's writers run on ([drainChildren]). A drain
 * closes the gap where a late arrival lands *while the drain is waiting* — it looks again until a
 * pass finds nothing. It cannot close the gap where the scope goes empty and a producer that is
 * still alive launches something afterwards: an empty pass ends the drain, and no number of passes
 * can prove a producer will never produce again. Neither of the Run's producers is stopped
 * definitively — the session inbox is joined with a bounded timeout, and the GPS looper is asked to
 * quit safely and never joined — so what makes an empty scope *proof* rather than an observation is
 * that everything still alive is refused.
 *
 * **What goes past is the finish already under way, and that is the care the gate is built around.**
 * Two things are in that category and there are no others:
 *
 *  - [deliveringHeldWork] — the teardown itself handing over a buffer of seconds the Run recorded
 *    before any of this began.
 *  - [finishingTheRun] — the Run's own finalize, the write that stamps the row with the totals the
 *    Run banked as it ran.
 *
 * Both are the Run *ending*, not work added to it, and a gate that dropped either would cost a real
 * Run its whole recording to fix a rescue's rounding of a metre. They are stated by the caller
 * launching the write rather than worked out from which thread is calling, because the difference is
 * about whose work it is.
 *
 * **[finishingTheRun] is here because refusing it lost the Run outright (#382).** #315 first refused
 * the finalize along with everything else, on the reasoning that the teardown's rescue would write
 * the row instead and one writer is what the ticket is about. That reasoning does not survive the
 * ordinary background STOP. A STOP publishes STOPPED before it performs its effects, the promotion
 * follower reads that publish on main and calls `stopSelf()`, and `onDestroy` — gate and all — can
 * therefore run before the session thread reaches the finalize. The teardown then reads a Run that
 * is no longer recording, which is not a Run it has anything to settle
 * ([com.example.runningapp.run.runLostToTeardown] answers null for STOPPED), so refusing the
 * finalize left *no* writer at all: an `endTime = 0` row, a Run missing from history, the export and
 * the coach until some later launch happened to rescue it. Zero writers is a worse harm than the two
 * it was replacing.
 *
 * So mutual exclusion is made explicit where it belongs — a per-Run claim that both settlers must win
 * before they write the row ([com.example.runningapp.run.RunRowSettlementClaim]) — and this gate goes
 * back to being about *new* work. The finalize still registers here, so it is a child the drains can
 * see; it is simply never refused.
 *
 * Here, as a named rule with nothing else in it, so that the places that ask it — the entry refusal
 * in `HrForegroundService.dispatchRunEvent` and [TeardownGate] itself, which is where the answer is
 * turned into work being registered or not — are asking one question rather than each carrying a
 * spelling of it that the next editor can part.
 */
fun runMayBeGivenWork(
    teardownBegun: Boolean,
    deliveringHeldWork: Boolean = false,
    finishingTheRun: Boolean = false,
): Boolean = !teardownBegun || deliveringHeldWork || finishingTheRun

/**
 * The gate's flag and the registration of work, held together so a producer cannot fall between
 * them (#315).
 *
 * **Why the flag alone was not enough.** [runMayBeGivenWork] states the rule, and a `@Volatile`
 * boolean makes the answer current at the instant it is read — but the answer is not the point, the
 * *work* is. Between reading a false flag and launching onto a scope there is a window, and a
 * producer descheduled inside it resumes into a world where the teardown has flipped the flag, its
 * drains have had their empty pass, and the rescued row has already been settled from what the
 * scope contained. The launch then lands behind all of it: a final heart-rate sample or track point
 * outside the totals the rescue rebuilt. The ticket's claim is that an *empty scope is proof*, and a
 * check-then-launch gap is exactly the thing that reduces it back to an observation. So the decision
 * and the registration are one step, taken under one monitor, and the flag flips under that same
 * monitor: once [beginTeardown] has returned, every producer has either already registered its work
 * — where the drains will see it as a child — or is refused. There is no third state.
 *
 * **A registration that is never refused is still a registration.** The Run's own finalize goes past
 * the rule (`finishingTheRun`, see [runMayBeGivenWork]), and it comes through here anyway rather
 * than launching on its own: what the monitor buys it is not permission but *visibility*. Registered
 * here it is already a child of the finalization scope by the time [beginTeardown] returns, so the
 * teardown's drains wait for it; launched outside, it could appear on that scope after the drains'
 * empty pass, and the teardown would read a settled world that was still being written. Which of the
 * two writers may write the row is a different question and is not this gate's — it is the per-Run
 * claim in [com.example.runningapp.run.RunRowSettlementClaim] (#382).
 *
 * **The monitor is held for a launch and nothing else.** [beginTeardown] runs on main, at the very
 * top of `onDestroy`, and a service that blocked there would be an ANR rather than a fix. What it
 * can be made to wait for is bounded by what [registerWorkForTheRun] does inside the lock, so what
 * that is given must be a `launch` and never the write itself: registering a coroutine on a scope
 * returns immediately, and the write it starts runs outside the lock, where the drains — not this
 * monitor — are what wait for it. Nothing that touches the database, joins a thread or takes
 * another lock belongs inside the block.
 *
 * The entry refusal in `HrForegroundService.dispatchRunEvent` is not replaced by this and is not
 * meant to be. It reads [teardownBegun] and turns a dispatch away *before* it touches anything,
 * which is about not half-doing work whose result is going to be thrown away; this is about the one
 * moment where work becomes something a drain can see.
 */
class TeardownGate {

    /**
     * The monitor. Its own object rather than the gate itself, so that nothing outside this file can
     * synchronize on it and make [beginTeardown]'s wait longer than the launch it is meant to be.
     */
    private val registration = Any()

    /**
     * `@Volatile` as well as guarded, because the entry refusals read it without the lock: they are
     * asking a question, and a stale `false` there costs at most some work that a later refusal
     * throws away, whereas taking the monitor on every dispatch would put the session inbox and the
     * teardown's main thread in each other's way for no gain.
     */
    @Volatile
    private var begun = false

    /** Whether this service has begun going down. See [runMayBeGivenWork] for what that refuses. */
    val teardownBegun: Boolean get() = begun

    /**
     * Close the gate, and do not return until every producer that had passed it has registered.
     *
     * Called on main at the very top of `onDestroy`, before anything is stopped, so nothing can slip
     * between the decision to go down and the gate closing. It waits only for whatever launch is
     * inside the monitor right now — see the class doc for why that is all it may ever wait for.
     */
    fun beginTeardown() {
        synchronized(registration) { begun = true }
    }

    /**
     * Register one piece of the Run's own work, if the Run may still be given work.
     *
     * @param deliveringHeldWork seconds the Run recorded before the teardown began, being handed
     *   over by the teardown itself. See [runMayBeGivenWork].
     * @param finishingTheRun the Run's own finalize, which is never refused — it is the finish, and
     *   refusing it left the Run with no writer at all (#382). It registers all the same, so the
     *   drains can see it. See [runMayBeGivenWork].
     * @param register launches the work onto its scope. Runs under the monitor, so it must do that
     *   and nothing else; the work itself runs outside it.
     * @return whether the work was registered, so the caller can say what it turned away.
     */
    fun registerWorkForTheRun(
        deliveringHeldWork: Boolean = false,
        finishingTheRun: Boolean = false,
        register: () -> Unit,
    ): Boolean =
        synchronized(registration) {
            if (!runMayBeGivenWork(begun, deliveringHeldWork, finishingTheRun)) {
                false
            } else {
                register()
                true
            }
        }
}
