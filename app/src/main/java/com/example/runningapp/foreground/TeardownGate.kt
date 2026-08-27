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
 * Here, as a named rule with nothing else in it, so that the two places that ask it —
 * `HrForegroundService.dispatchRunEvent` and `HrForegroundService.recordForTheRun` — are asking one
 * question rather than each carrying a spelling of it that the next editor can part.
 */
fun runMayBeGivenWork(teardownBegun: Boolean, deliveringHeldWork: Boolean = false): Boolean =
    !teardownBegun || deliveringHeldWork
