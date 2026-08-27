package com.example.runningapp.data

import android.util.Log

/**
 * Everything a Run is measured for the moment it is finished, in the one order that is correct.
 *
 * Four passes, and they are the same four however the Run got here: the finalize of a Run the
 * runner stopped, and the rescue of a Run a teardown left recording ([SessionRepository
 * .rescueRunLostToTeardown], [SessionRepository.rescueInterruptedRuns]). Both used to spell the
 * order out for themselves, each step in its own try/catch with its own comment arguing for it, and
 * nothing anywhere proved that the two copies still said the same thing. They say it here now, once
 * — and [AfterRunMeasurementsTest] is what the order is proved by, on a laptop rather than a phone.
 *
 * **Every pass runs after the Run's track points have landed, and never before.** A moving time, a
 * record, a Segment time or a shape taken over half a Run is a number nobody ran. That is the
 * caller's part of the bargain: this is called once the row and its track are stored.
 *
 * **Every pass is its own attempt.** By the time this runs the Run is saved, and a pass that throws
 * must not undo that, nor cost the runner the passes behind it — the backup, the weather and the
 * coach all sit downstream of this in the finalize. So each failure is logged and left, and each
 * one is left in the state a launch pass already looks for:
 *
 * - a moving time that could not be measured stays null, which is what `backfillMovingTime` takes;
 * - a scoring that could not be written leaves the Run unmarked, which is what the missed-scoring
 *   pass takes — and the mark is written by the scoring itself, only once the book is written,
 *   never beside the finish (#210);
 * - a Segment timing that could not be put leaves the Run owing one, which the next Segment the
 *   runner cuts collects (#70);
 * - a shape that could not be taken leaves no shape row, and the absence of the row is the debt
 *   (#73).
 *
 * **The Stage is not settled here.** It is the finalize's own step and it belongs to the finalize
 * alone: a settlement takes a fresh snapshot of the whole database per Run, and the launch rescue
 * owes one snapshot for the whole pass, not one for each Run it puts back. A rescued Run keeps its
 * settlement debt for the launch pass that pays it.
 */
class AfterRunMeasurements(
    private val computeMovingTime: suspend (Long) -> Long?,
    private val scoreAndMarkRecords: suspend (Long) -> List<Achievement>,
    private val timeRunAgainstSegments: suspend (Long) -> Unit,
    private val shapeRun: suspend (Long) -> Unit,
) {

    /**
     * Measures the finished Run, and returns its moving time — null when there was none to measure
     * or the measuring failed, which is the same null the backfill looks for either way.
     *
     * The pace the app quotes is rewritten by the first pass, over moving time rather than over the
     * duration the finish banked (#163), so the number handed back is the one the Run now carries.
     */
    suspend fun perform(runRowId: Long): Long? {
        val movingTime = try {
            computeMovingTime(runRowId)
        } catch (e: Exception) {
            Log.w(TAG, "Moving time failed for run $runRowId; leaving it to the backfill", e)
            null
        }

        try {
            val earned = scoreAndMarkRecords(runRowId)
            if (earned.isNotEmpty()) {
                Log.d(
                    TAG,
                    "Run $runRowId earned ${earned.size} achievement(s): " +
                        earned.joinToString { "${it.medal} ${it.type}" },
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not score run $runRowId against the record book", e)
        }

        try {
            timeRunAgainstSegments(runRowId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not time run $runRowId against the segments", e)
        }

        try {
            shapeRun(runRowId)
        } catch (e: Exception) {
            Log.w(TAG, "Could not take the shape of run $runRowId", e)
        }

        return movingTime
    }

    private companion object {
        private const val TAG = "AfterRun"
    }
}
