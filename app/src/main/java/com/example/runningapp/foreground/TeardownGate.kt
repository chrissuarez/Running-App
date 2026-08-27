package com.example.runningapp.foreground

/**
 * Whether the Run may still be given work, now that the service may have begun going down (#315).
 *
 * **A gate, and it exists because a wait cannot do this job.** The teardown finishes a Run it took,
 * and before it does so it drains the scopes the Run's writers run on. A drain ([drainChildren])
 * closes the gap where a late arrival lands *while the drain is waiting* — it looks again until a
 * pass finds nothing. It cannot close the gap where the scope goes empty and a producer that is
 * still alive launches something afterwards: an empty pass ends the drain, and no number of passes
 * can prove a producer will never produce again. Neither of the Run's producers is stopped
 * definitively — the session inbox is joined with a bounded timeout, and the GPS looper is asked to
 * quit safely and never joined — so what makes an empty scope *proof* rather than an observation is
 * that everything still alive is refused.
 *
 * **[deliveringHeldWork] is the one thing that goes past, and the care the gate is built around.** A
 * background STOP finalizes and then takes the service down, so the teardown runs while the Run's
 * own last writes are legitimately being made; and the teardown itself hands over a buffer of
 * seconds the Run recorded before any of this began. Those are the finish already under way, not new
 * work — and a gate that dropped them would cost a real Run its whole recording to fix a rescue's
 * rounding of a metre. It is stated by the caller launching the write rather than worked out from
 * which thread is calling, because the difference is about whose work it is.
 *
 * Here, as a named rule with nothing else in it, so that the places that ask it — the entry refusal
 * in `HrForegroundService.dispatchRunEvent`, the one in `HrForegroundService.finalizeRun`, and
 * [TeardownGate] itself, which is where the answer is turned into work being registered or not —
 * are asking one question rather than each carrying a spelling of it that the next editor can part.
 */
fun runMayBeGivenWork(teardownBegun: Boolean, deliveringHeldWork: Boolean = false): Boolean =
    !teardownBegun || deliveringHeldWork

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
 * outside the totals the rescue rebuilt, or — from the finalize's own launch — a second writer for
 * the row the rescue just wrote, with the totals going to whichever landed last. The ticket's claim
 * is that an *empty scope is proof*, and a check-then-launch gap is exactly the thing that reduces
 * it back to an observation. So the decision and the registration are one step, taken under one
 * monitor, and the flag flips under that same monitor: once [beginTeardown] has returned, every
 * producer has either already registered its work — where the drains will see it as a child — or is
 * refused. There is no third state.
 *
 * **The monitor is held for a launch and nothing else.** [beginTeardown] runs on main, at the very
 * top of `onDestroy`, and a service that blocked there would be an ANR rather than a fix. What it
 * can be made to wait for is bounded by what [registerWorkForTheRun] does inside the lock, so what
 * that is given must be a `launch` and never the write itself: registering a coroutine on a scope
 * returns immediately, and the write it starts runs outside the lock, where the drains — not this
 * monitor — are what wait for it. Nothing that touches the database, joins a thread or takes
 * another lock belongs inside the block.
 *
 * The entry refusals elsewhere are not replaced by this and are not meant to be. They read
 * [teardownBegun] and turn a dispatch or a finalize away *before* it touches anything, which is
 * about not half-doing work whose result is going to be thrown away; this is about the one moment
 * where work becomes something a drain can see.
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
     * @param deliveringHeldWork the one exception, stated by the caller: seconds the Run recorded
     *   before the teardown began, being handed over by the teardown itself. See [runMayBeGivenWork].
     * @param register launches the work onto its scope. Runs under the monitor, so it must do that
     *   and nothing else; the work itself runs outside it.
     * @return whether the work was registered, so the caller can say what it turned away.
     */
    fun registerWorkForTheRun(deliveringHeldWork: Boolean = false, register: () -> Unit): Boolean =
        synchronized(registration) {
            if (!runMayBeGivenWork(begun, deliveringHeldWork)) {
                false
            } else {
                register()
                true
            }
        }
}
