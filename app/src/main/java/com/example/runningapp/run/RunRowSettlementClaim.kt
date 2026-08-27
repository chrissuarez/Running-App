package com.example.runningapp.run

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The claim on settling one Run's row, taken by whichever of its two settlers gets there first
 * (#382).
 *
 * **The invariant is "exactly one writer settles the row", and both halves of it are load-bearing.**
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
 * is decided by thread scheduling. *Neither* writing is the worse harm, and it is what refusing the
 * finalize during a teardown actually produced (#382): an `endTime = 0` row, invisible to history,
 * the export and the coach until some later launch's rescue pass happened to find it. So the answer
 * is not to refuse a settler but to make them race for a claim: the winner writes, the loser stands
 * down and says so.
 *
 * **A compare-and-set rather than either settler inferring the other is not there.** That was the
 * shape the two sides of the held-work buffer were written with first, and inference is what #360
 * had to undo: a bounded join running out says nothing at all about whether the other side is going
 * to deliver. The same reasoning applies here with more force, because the two settlers do not even
 * observe each other — the finalize runs from the session thread's dispatch of a STOP, and the
 * rescue from `onDestroy` on main, with no ordering between them beyond this claim.
 *
 * **Which settler wins is left to the race, and that is deliberate.** The finalize takes the claim
 * the moment it is performed, synchronously on the session thread and before its own launch, so in
 * the ordinary case it wins and the Run keeps its own totals. Where the teardown wins instead, it
 * won because it got there first — and a rescue that has already read the record and written the row
 * cannot be asked to give it back. A finalize that finds the claim gone therefore stands down rather
 * than overwriting: two answers to "what were this Run's totals" is exactly what the claim exists to
 * prevent, and the rescue's answer, already on disk, is a true one.
 *
 * **It is reset as each Run starts, not when one ends.** A claim left standing from the last Run
 * would have this Run's row settled by nobody at all — both settlers would find it taken and both
 * would stand down, which is the very failure this class was written to end. Reset in the one place
 * a Run is known to be beginning ([List.beginARun]), alongside the row id and the held-work claim,
 * for the same reason all three are reset there: they describe the Run being recorded now.
 *
 * A class rather than a bare `AtomicBoolean` in the service because the rule is read from three
 * places and none of them is next to the others — the finalize, the rescue of a Run that had a row,
 * and the rescue of a Run whose row landed late. A named claim says what winning and losing *mean*
 * in one place, and can be leaned on in a test.
 */
class RunRowSettlementClaim {

    private val taken = AtomicBoolean(false)

    /**
     * Take the claim, and answer whether this caller is the one that settles the row.
     *
     * Exactly one caller per Run is answered `true`, however many ask and from however many threads.
     * A caller answered `false` must not write the row: the settler that won it is writing it, or
     * has already.
     */
    fun takenHere(): Boolean = taken.compareAndSet(false, true)

    /**
     * A new Run's row is nobody's yet.
     *
     * Called as a Run begins and only there. Calling it at any other moment would hand a second
     * settler a row that is already finished.
     */
    fun releaseForANewRun() {
        taken.set(false)
    }
}
