package com.example.runningapp.foreground

import com.example.runningapp.SessionStatus

/**
 * Is an Acquisition in flight — is the app scanning for, connecting to, or retrying a Strap?
 *
 * The single definition. It used to be spelled twice by searching status text (the START guard
 * in HrForegroundService and the record screen's spinner in MainActivity) and the two copies
 * disagreed: one omitted "Scanning". Promotion now depends on the answer, so there is exactly
 * one of it.
 *
 * Everything not listed here is terminal — connected, given up on, or blocked. Unrecognised text
 * reads as terminal on purpose: the failure this whole module exists to prevent is a Promotion
 * nobody releases, so an unknown status should fail towards releasing it.
 */
fun isAcquiringStrap(connectionStatus: String): Boolean =
    connectionStatus.contains("Scanning", ignoreCase = true) ||
        connectionStatus.contains("Connecting", ignoreCase = true) ||
        connectionStatus.contains("Reconnecting", ignoreCase = true) ||
        connectionStatus.contains("Retrying", ignoreCase = true)

/**
 * What the Android service can do on Promotion's behalf. Behind an interface so the decision
 * above and the transitions below are testable without a device.
 */
interface PromotionHost {
    /**
     * startForeground() plus the wake lock. Returns whether the platform actually granted it.
     *
     * Android 12+ refuses a foreground start from the background, and Promotion is now derived,
     * so it can be asked for at moments the old caller-driven code never reached — a pre-run
     * reconnect starting on its own, say. Report the refusal rather than throwing; answering
     * `true` regardless would leave [ForegroundPromotion] believing in a notification that does
     * not exist, and posting to it.
     */
    fun promote(): Boolean

    /**
     * Release the wake lock, drop the notification, and stopSelf().
     *
     * Also how a start that was never promoted is handed back, so every call here must be safe
     * when there was no Promotion to begin with — none of the three may assume one.
     */
    fun demote()

    /** Post [text] to the Promotion's notification. */
    fun showNotification(text: String)
}

/**
 * Owns Promotion. Nothing else in the app promotes or demotes.
 *
 * Promotion used to be taken once at the top of onStartCommand and released by the caller at
 * eleven scattered exits; missing one stranded a 10-hour wake lock, which happened four times in
 * forty commits. There is now no release to forget: [reconcile] derives the answer from state the
 * app already publishes.
 *
 * See docs/adr/0001-promotion-is-derived-not-claimed.md.
 */
class ForegroundPromotion(private val host: PromotionHost) {

    /**
     * Written only from the main thread, but read from the session timer's own thread: a pulse
     * already in flight when a Run stops calls [showNotification] from there. This is the guard
     * that stops it reposting notification ID 1 over a Promotion that has just been dropped, and
     * a guard the reader can't see the current value of guards nothing — hence volatile. The
     * check it replaced read a StateFlow, which carried that visibility for free.
     */
    @Volatile
    var isPromoted: Boolean = false
        private set

    /**
     * A startForegroundService() we took delivery of and never promoted.
     *
     * The start lands whether or not the platform then grants the Promotion, and [demote] — which
     * ends in stopSelf() — is the only thing in the app that stops the service. So a refusal that
     * is merely recorded leaves a started service with no notification, waiting on Android's
     * start-up watchdog and reachable by nothing. It stays set until a promotion is granted or the
     * start is unwound.
     */
    private var startNeedsUnwinding: Boolean = false

    /**
     * Android gives roughly five seconds from startForegroundService() to startForeground(),
     * whatever the intent turns out to want — so onStartCommand promotes unconditionally through
     * here, before it knows. [reconcile] at the tail of dispatch takes it back if nothing earned
     * it. That pairing is what closes d335ef3's ignored-START leak.
     *
     * Unconditional on purpose, unlike [reconcile]: the deadline is tracked per
     * startForegroundService() call, so "already promoted" is not an excuse to skip this one.
     * startForeground() on an already-foreground service just refreshes the notification.
     */
    fun promoteForStartCommand() {
        // The one thing this entry point knows that [reconcile] doesn't: a start is waiting on
        // the answer, so a refusal here leaves a stop owed.
        if (!attemptPromote()) startNeedsUnwinding = true
    }

    /**
     * Re-decide. Edge-triggered: acts only when the answer changes — with one exception, below.
     *
     * That is a correctness requirement, not an optimisation. [demote] ends in stopSelf(), and
     * this runs on every published state change — including each per-second heartbeat while a
     * bare Strap sits connected with no Run.
     *
     * A refused promotion leaves [isPromoted] false, so the next state change that moves the
     * answer tries again — the rule heals itself rather than latching into a lie. What it must
     * not do is let that refusal pass unanswered forever: if nothing ends up earning the
     * Promotion, the start that asked for it is still owed a stop. That is the exception — an
     * unchanged answer of "not promoted" still unwinds an outstanding start, once. See
     * [startNeedsUnwinding].
     */
    fun reconcile(sessionStatus: SessionStatus, acquiringStrap: Boolean) {
        val earned = isEarned(sessionStatus, acquiringStrap)
        if (earned == isPromoted) {
            if (!earned && startNeedsUnwinding) unwind()
            return
        }
        if (earned) attemptPromote() else unwind()
    }

    /**
     * Ask the platform, and record what it said.
     *
     * A granted Promotion answers whatever start was outstanding, whichever entry point asked for
     * it — so the settling lives here rather than at the two call sites, where the two halves of
     * that invariant could drift apart.
     */
    private fun attemptPromote(): Boolean {
        isPromoted = host.promote()
        if (isPromoted) startNeedsUnwinding = false
        return isPromoted
    }

    /**
     * Hand the service back: drop whatever was taken, and stop what was started.
     *
     * [isPromoted] falls before [demote] runs, not after. A session pulse on the timer thread may
     * be between its guard and its notify() right now; closing the gate first is what keeps it
     * from posting over a notification stopForeground is about to remove.
     */
    private fun unwind() {
        isPromoted = false
        startNeedsUnwinding = false
        host.demote()
    }

    /**
     * Show [text] on the Promotion's notification, or drop it if there is no Promotion to show it
     * on. Without that guard, a pause or resume landing just after a demotion would post a
     * notification that nothing owns and nothing removes.
     */
    fun showNotification(text: String) {
        if (!isPromoted) return
        host.showNotification(text)
    }

    companion object {
        /**
         * Promotion is earned by a live Run or an in-flight Acquisition, and by nothing else.
         *
         * STOPPING counts: it is the Run that has been stopped but whose database row does not
         * exist yet, and it holds until the id lands and the Run publishes STOPPED. STOPPED does
         * not — finalization runs on detached scopes and needs no foreground (3bd4d3e).
         *
         * Simulation is deliberately absent. It always produces a Run, so the Run covers it — and
         * because isSimulationEnabled is never cleared by STOP, treating it as a reason of its own
         * would re-promote a runless service after every simulated run.
         */
        fun isEarned(sessionStatus: SessionStatus, acquiringStrap: Boolean): Boolean =
            acquiringStrap ||
                sessionStatus == SessionStatus.RUNNING ||
                sessionStatus == SessionStatus.PAUSED ||
                sessionStatus == SessionStatus.STOPPING
    }
}
