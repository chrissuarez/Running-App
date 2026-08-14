package com.example.runningapp.training

import com.example.runningapp.WorkoutTemplate
import com.example.runningapp.plannedSeconds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * How long after a Test the next one is due — three weeks, and not four (#292).
 *
 * Three weeks is the interval on its own; with [testIsHeldByForm] in front of it the prompt lands
 * at roughly three to four weeks in practice, which is where a 5K test belongs. Test more often
 * than that and the number measures noise; test less often and progression stalls behind a bar
 * that was cleared weeks ago.
 */
const val DAYS_BETWEEN_TESTS = 21L

/**
 * Whether the runner should be prompted to run their Stage's Test (#292).
 *
 * **A prompt, and never a gate.** The Test stays pickable at any time and from the first day of the
 * Stage; this only decides whether the card says anything about it. The Plan is a menu and not a
 * cursor (ADR 0005), so nothing here can stop a Run — and gating would also refuse a Test on the
 * one day the runner actually feels good.
 *
 * [lastTestStartedAtMillis] is when the runner last completed the Stage's Test Workout, read off
 * history and never stored (ADR 0001, [com.example.runningapp.PlanStage.testWorkout]). Null is "no
 * Test in history", which is due: a runner with no number at all has nothing to have measured
 * recently. Passing or failing makes no difference — the effort was paid either way, so a Test that
 * missed the bar resets the three weeks exactly like one that cleared it.
 *
 * [form] is yesterday's Fitness less yesterday's Fatigue, the number the Progress screen shows.
 * Null is "no curve to read yet", which holds nothing: nothing says the runner is tired.
 */
fun testIsDue(
    lastTestStartedAtMillis: Long?,
    form: Double?,
    today: LocalDate,
    zone: ZoneId,
): Boolean {
    if (testIsHeldByForm(form)) return false
    val lastTest = lastTestStartedAtMillis
        ?: return true
    val lastTestDay = Instant.ofEpochMilli(lastTest).atZone(zone).toLocalDate()
    return !today.isBefore(lastTestDay.plusDays(DAYS_BETWEEN_TESTS))
}

/**
 * Whether a due Test is held back because the runner is carrying fatigue — Form below −10 (#292).
 *
 * A test run on a fatigued runner produces a number that measures the fatigue rather than the
 * fitness, and under the app's own rule that number is load-bearing (ADR 0016): it can cost a
 * graduation that was genuinely earned. Waiting three days for freshness to come back is cheap; a
 * false negative on a test run once a month is not.
 *
 * The alternative — prompt anyway and print "you're fatigued today" beside it — pushes the
 * judgement onto the runner at the exact moment they are least placed to make it.
 *
 * The bands are [formVerdictOf]'s and are not restated here, so the number the runner reads on the
 * Progress screen and the number that holds the prompt are one rule rather than two that agree
 * most of the time.
 */
private fun testIsHeldByForm(form: Double?): Boolean =
    form != null && formVerdictOf(form) == FormVerdict.FATIGUED

/**
 * How much of a Test has to actually be run for it to count as having been run — nine tenths of it
 * (#292, Codex P2).
 *
 * A Test is one continuous effort with no envelope around it, so unlike every other Workout there is
 * no part of it that is not the measurement: stopping early is not finishing early, it is not
 * testing. But the Run ends when the runner presses STOP, and pressing it a few seconds before the
 * clock runs out is a Test that was run, so the last tenth is given away rather than argued over.
 *
 * The clock is the second question and never the first, because the Workout's length is the time the
 * Stage expects the distance to take and not the time the test takes: Stage 2 schedules thirty
 * minutes to test a bar of *under* thirty, so the runner who passes it stops before the clock does.
 * Judged on the clock alone, the better the Run the less it counted (Codex P2).
 *
 * The alternative — the two minutes every other "long enough to mean something" check in the app
 * uses — let a thirty-minute Test abandoned after two silence the prompt for three weeks, for a Test
 * nobody ran. That check is asked of Runs in general, where two minutes really is the line between
 * a session and a mis-tap; a Test is a specific length and can be asked against its own.
 */
const val TEST_COMPLETION_SHARE = 0.9

/**
 * Whether a Run of [test] was that Test — it covered the distance, or failing that it ran
 * [TEST_COMPLETION_SHARE] of the Workout (#292).
 *
 * [coveredTheDistance] is the Stage Requirement's distance measured or stated on the Run, answered
 * where distances are read ([com.example.runningapp.data.wasCoveredBy]) and handed here, the same
 * way [testIsDue] is handed a Form rather than working one out. It settles it on its own: a runner
 * who covered 5 km has taken the test whatever the clock says, and that includes every runner the
 * Stage is about to graduate.
 *
 * The clock is the fallback, for the Run that has no distance to read — a treadmill Test whose
 * console was never typed in. Measured against the Workout's whole planned length rather than its
 * main set, because a Test has no warm-up or cool-down to leave out.
 */
fun wasRunFarEnough(
    test: WorkoutTemplate,
    durationSeconds: Int,
    coveredTheDistance: Boolean,
): Boolean = coveredTheDistance || durationSeconds >= test.plannedSeconds * TEST_COMPLETION_SHARE
