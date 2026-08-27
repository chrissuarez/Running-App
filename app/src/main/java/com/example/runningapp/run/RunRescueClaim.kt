package com.example.runningapp.run

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The claim on rescuing one Run, taken by whichever of its two settlers gets there first (#382).
 *
 * **It decides who speaks and who pays for a rebuild. It does not decide who writes the row.** That
 * distinction is the whole of this class, and it was learnt the hard way.
 *
 * A Run's row is created at START with `endTime = 0` and is finished exactly once. Two writers can
 * finish it, and they finish it differently:
 *
 *  - the Run's own finalize, with the totals the Run banked second by second as it ran — the better
 *    answer, and the one the runner earned;
 *  - the teardown's rescue, with totals rebuilt from what reached the database
 *    ([com.example.runningapp.data.SessionRepository.rescueRunLostToTeardown]) — the fallback, which
 *    exists because a Run taken from its runner mid-recording has no finalize coming.
 *
 * Both writing is the harm #315 was filed about: the row's totals go to whichever landed last, which
 * is decided by thread scheduling. *Neither* writing is the worse harm — an `endTime = 0` row,
 * invisible to history, the export and the coach until some later launch's rescue pass happened to
 * find it — and this class was written as the answer to both: a compare-and-set the settlers raced
 * for, the winner writing the row and the loser standing down.
 *
 * **That answer lost Runs, one level in from where it was looking.** A teardown takes the claim
 * before it knows whether there is anything to rebuild, and the rescue's refusal comes much later
 * and asynchronously: a Run with no reconstructable seconds — a short strapless treadmill Run —
 * rebuilds to nothing and writes nothing at all, while the Run's own finalize has long since found
 * the claim gone and stood down for good. Nobody settled the row. Handing the claim back on that
 * failure was declined for the same reason: the failure is discovered after the finalize has already
 * asked and gone, so a hand-back moves the window rather than closing it — the lesson of #342, where
 * every narrower fix moved a window that only an atomic write could close.
 *
 * **So the rule moved to the row.** The Run's row is settled by the write that finds it unsettled
 * ([com.example.runningapp.data.SETTLE_RUN_ROW_IF_UNSETTLED]): the condition travels with the write,
 * the first settler to arrive writes, and the second changes nothing and is told so. An in-memory
 * claim is an approximation of that database fact, and the approximation is what kept losing Runs.
 * Both settlers now go and write; neither stands down for this.
 *
 * **What is genuinely left for a claim, and why it is worth keeping.** A teardown that finds the Run
 * still recording has two things to do before any write: tell the runner their Run stopped
 * recording, and rebuild the Run from two tables inside a process that may be about to be reclaimed.
 * Both are wrong where the Run's own finish is already on its way — the runner stopped it
 * themselves, and the rebuild is expensive work for an answer that will lose to the Run's own
 * totals. The finalize takes the claim synchronously on the session thread the moment it is
 * performed, so a teardown that finds it gone knows exactly that, and skips both.
 *
 * The cost of getting *that* wrong is now bounded: a teardown that takes the claim and turns out to
 * have nothing to rebuild costs a notification the runner did not need. It cannot cost a Run,
 * because nothing is standing down from the write any more. That is the difference between a
 * mechanism that may approximate and one that may not.
 *
 * **It is reset as each Run starts, not when one ends.** A claim left standing from the last Run
 * would have this Run's teardown believing a finalize was on its way that is not. Reset in the one
 * place a Run is known to be beginning ([List.beginARun]), alongside the row id and the held-work
 * claim, for the same reason all three are reset there: they describe the Run being recorded now.
 *
 * A class rather than a bare `AtomicBoolean` in the service because the rule is read from three
 * places and none of them is next to the others — the finalize, the rescue of a Run that had a row,
 * and the rescue of a Run whose row landed late. A named claim says what winning and losing *mean*
 * in one place, and can be leaned on in a test.
 */
class RunRescueClaim {

    private val taken = AtomicBoolean(false)

    /**
     * Take the claim, and answer whether this caller is the one that rescues the Run.
     *
     * Exactly one caller per Run is answered `true`, however many ask and from however many threads.
     * A caller answered `false` is a teardown whose Run has a finalize of its own already under way:
     * it must not rebuild the Run and must not tell the runner it stopped recording. It is not a
     * caller forbidden to write — no caller is. See the class doc.
     */
    fun takenHere(): Boolean = taken.compareAndSet(false, true)

    /**
     * A new Run is nobody's to rescue yet.
     *
     * Called as a Run begins and only there. Calling it at any other moment would tell this Run's
     * teardown that no finalize is coming when one is, and it would go and rebuild a Run that was
     * finishing itself.
     */
    fun releaseForANewRun() {
        taken.set(false)
    }
}
