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

    /** Release the wake lock, drop the notification, and stopSelf(). */
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

    var isPromoted: Boolean = false
        private set

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
        isPromoted = host.promote()
    }

    /**
     * Re-decide. Edge-triggered: acts only when the answer changes.
     *
     * That is a correctness requirement, not an optimisation. [demote] ends in stopSelf(), and
     * this runs on every published state change — including each per-second heartbeat while a
     * bare Strap sits connected with no Run.
     *
     * A refused promotion leaves [isPromoted] false, so the next published state change tries
     * again — the rule heals itself rather than latching into a lie.
     */
    fun reconcile(sessionStatus: SessionStatus, acquiringStrap: Boolean) {
        val earned = isEarned(sessionStatus, acquiringStrap)
        if (earned == isPromoted) return
        isPromoted = if (earned) {
            host.promote()
        } else {
            host.demote()
            false
        }
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
         * STOPPING counts: stopSession() publishes it synchronously and it holds until the
         * finalize coroutine publishes STOPPED. STOPPED does not — finalize runs on detached
         * scopes and needs no foreground (3bd4d3e).
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
