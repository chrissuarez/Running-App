package com.example.runningapp.ui

import com.example.runningapp.RunType
import com.example.runningapp.TrainingPlanProvider
import com.example.runningapp.WorkoutTemplate
import com.example.runningapp.data.RouteHeader
import com.example.runningapp.data.RunPaceRow
import com.example.runningapp.data.averagePaceMinPerKm
import com.example.runningapp.plannedSeconds
import java.util.Locale
import kotlin.math.abs

/**
 * Which course fits today's session, offered in the pre-run picker (#422).
 *
 * Pure and outside the composables, the bargain [routeRowSubtitle] and [routeLibraryRows] make: the
 * number the runner is shown and the order they are shown their courses in are the feature, so both
 * are pinned by unit tests rather than by opening the record screen on a phone.
 *
 * **The plan states a distance for one session only, and derives it for the rest.** A Workout
 * prescribes seconds and a heart-rate zone ([WorkoutTemplate]) and nothing in it says metres, so for
 * an ordinary session the target has to be derived, by plain arithmetic on this phone: today's
 * planned seconds × a pace off the runner's own recent Runs. The exception is a Test. A Test exists
 * to be measured against the Stage's [com.example.runningapp.BestEffortRequirement], which is a time
 * at a *set distance*, and its `runDurationSeconds` is the clock the runner is trying to beat rather
 * than a duration to multiply — so multiplying it by a pace answers a question nobody asked and
 * answers it short. There the distance is a fact the plan already holds, and it is passed in.
 *
 * Nothing here reaches the network, asks the AI coach or reads a consent flag, and it must stay that
 * way — "which of these is nearest seven kilometres" is arithmetic, and where the seven is an
 * estimate a second estimate laid on top of it can only make it worse.
 *
 * **The pace is taken from Runs of the same kind, never from recent Runs at large.** A Long Run and
 * a set of strides cover ground at paces that are not the same number and were never meant to be;
 * blend them and one hard Quality Run drags the average up and sends the runner out on a nine
 * kilometre "easy" route. Asking one kind at a time is also what makes the arithmetic honest at
 * both ends: a Long Run's planned hour includes its walk breaks, and so did the hours the Long Runs
 * behind it were measured over.
 *
 * **Too little history estimates nothing at all.** See [ROUTE_SUGGESTION_MIN_RUNS] — and note that
 * it gates the estimate, not a Test's stated distance.
 */

/**
 * How many Runs of today's kind must be in the window before a distance is *estimated*.
 *
 * Three, and the alternative considered was one. A single Run is not a pace, it is a day — the day
 * it rained, or the day the runner stopped to tie a lace — and a picker that reordered itself
 * around it would be confidently wrong at exactly the moment the runner has least history to notice
 * it with. Three is the smallest count at which the median is a middle value rather than the only
 * value.
 *
 * Below it the picker shows no hint and keeps the library's own order, which is what it did before
 * this shipped: no suggestion is a state the runner already understands, and a guess is not.
 *
 * It is a bar on the *estimate* and on nothing else. A Test's distance is stated by the plan, not
 * worked out from Runs, so a runner with no history at all is still told their 5K Test is 5 km —
 * withholding it until three Quality Runs are on the phone would hide a number the plan has been
 * printing on the stage card all along, and hide it from exactly the runner most likely to set off
 * on a course too short to finish the Test on.
 */
const val ROUTE_SUGGESTION_MIN_RUNS = 3

/**
 * How many Runs of today's kind are counted towards the median, newest first.
 *
 * A bound rather than the whole window, because the window is a limit on how *old* a Run may be and
 * this is a limit on how many of them one answer rests on. A runner deep in a heavy block would
 * otherwise have a fortnight of Runs outvoted by the two months in front of them.
 */
const val ROUTE_SUGGESTION_RUNS_COUNTED = 10

/**
 * How far back a Run may be and still count towards today's pace: ninety days.
 *
 * Fitness moves, and a suggestion built from the spring is a suggestion about a runner who no
 * longer exists. Ninety days is long enough that a kind of session run once a week still clears
 * [ROUTE_SUGGESTION_MIN_RUNS] several times over, and short enough that a winter off empties the
 * window rather than answering out of it.
 */
const val ROUTE_SUGGESTION_WINDOW_DAYS = 90L

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

/** The oldest Run [com.example.runningapp.data.SessionDao.recentMeasuredRuns] may return. */
fun routeSuggestionSinceMillis(nowMillis: Long): Long =
    nowMillis - ROUTE_SUGGESTION_WINDOW_DAYS * MILLIS_PER_DAY

/**
 * How far today's session is likely to cover, in metres — or null where nothing may be suggested.
 *
 * [workout] is today's Run as it will actually be run, prescription applied, and null on a day with
 * no plan attached or a plan the runner has skipped: an open run has no planned seconds, so there
 * is no number to multiply and nothing to suggest.
 *
 * [recentRuns] is what [com.example.runningapp.data.SessionRepository.recentMeasuredRuns] found —
 * every kind of Run together, because the query cannot tell kinds apart. The filtering to today's
 * kind happens here, where the plans can be asked.
 *
 * [fixedDistanceMeters] is the distance the plan itself states for today, where it states one — a
 * Test, whose Stage graduates on a time at a set distance. Given, it *is* the answer: it is returned
 * whole, ahead of the pace arithmetic and ahead of the history bar. Passed in as a number rather
 * than looked up from [workout], because a [WorkoutTemplate] does not know which Stage is holding
 * it, and reaching for the plan from in here would trade a function whose answer is its arguments
 * for one that has to be set up before it can be asked. The caller knows both, and says so.
 *
 * The alternative considered was to keep multiplying and let a Test take the same estimate as
 * everything else. It is wrong in the direction that matters: a Test's `runDurationSeconds` is the
 * time to beat, so unless the runner's median pace happens to be exactly the pace the bar is set at,
 * the estimate comes out *short* and the picker offers a course the prescribed Test cannot be
 * completed on — Stage 3's 25-minute bar at a 6:00/km history advertises about 4.2 km.
 *
 * Null is the honest answer in four cases, all of them cases with no [fixedDistanceMeters], and
 * every one of them leaves the picker exactly as it was: no plan today, fewer than
 * [ROUTE_SUGGESTION_MIN_RUNS] Runs of today's kind in the window, a Workout that plans no time at
 * all, and a median pace of nought (a set of Runs that measured a clock but no ground, which the
 * query already refuses but which arithmetic must not depend on).
 */
fun suggestedRouteDistanceMeters(
    workout: WorkoutTemplate?,
    recentRuns: List<RunPaceRow>,
    fixedDistanceMeters: Double? = null,
): Double? {
    if (workout == null) return null
    if (fixedDistanceMeters != null && fixedDistanceMeters > 0.0) return fixedDistanceMeters
    val plannedSeconds = workout.plannedSeconds
    if (plannedSeconds <= 0L) return null
    val pace = recentMedianPaceMinPerKm(recentRuns, workout.runType) ?: return null
    if (pace <= 0.0) return null
    val km = (plannedSeconds / 60.0) / pace
    return km * 1000.0
}

/**
 * The middle pace of the newest [ROUTE_SUGGESTION_RUNS_COUNTED] Runs of [runType] — null where there
 * are too few of them.
 *
 * The median and not the mean, because the thing being guarded against is one unusual Run, and a
 * mean has no defence against one. A runner who spent a Long Run mostly waiting at a level crossing
 * still gets the pace of their other Long Runs.
 *
 * [recentRuns] must arrive newest first, which is the order the query returns and the only order in
 * which "the newest ten" means anything.
 */
fun recentMedianPaceMinPerKm(recentRuns: List<RunPaceRow>, runType: RunType): Double? {
    val ofType = recentRuns
        .filter { row ->
            TrainingPlanProvider.runTypeOfRecordedRun(row.ranUnderStageId, row.ranUnderWorkoutId) ==
                runType
        }
        .take(ROUTE_SUGGESTION_RUNS_COUNTED)
    if (ofType.size < ROUTE_SUGGESTION_MIN_RUNS) return null
    // Measured against the Run's own duration, not its moving time — see [RunPaceRow].
    val paces = ofType
        .map { averagePaceMinPerKm(it.durationSeconds, it.distanceKm) }
        .filter { it > 0.0 }
        .sorted()
    if (paces.size < ROUTE_SUGGESTION_MIN_RUNS) return null
    val middle = paces.size / 2
    return if (paces.size % 2 == 1) {
        paces[middle]
    } else {
        (paces[middle - 1] + paces[middle]) / 2.0
    }
}

/**
 * The line above the picker: `Today ≈ 7 km`, or `Today 5 km` where the plan stated the distance.
 *
 * One decimal place, where every other distance in the app is written to two ([routeDistanceLabel]).
 * That is deliberate and it is the whole difference between a measurement and an estimate: an
 * estimated number is a planned duration multiplied by a median, and printing it as `7.24 km` would
 * claim a precision that neither of its two inputs has. A trailing nought goes, so a round answer
 * reads as `Today ≈ 7 km` rather than `Today ≈ 7.0 km`. A stated distance is written to the same one
 * place, because two hints in the same slot written to different precisions would read as two
 * different kinds of claim over and above the one difference actually being drawn.
 *
 * `≈` and not `=` for an estimate, and it is the first character the runner's eye lands on after the
 * word "Today". [targetIsFixed] drops it: a 5K Test is 5 km because the plan says so, and hedging a
 * number the plan states would be the app disclaiming its own instruction. The sign is dropped only
 * when the line can print the stated distance *exactly* — a mile is 1609.344 m and reads as 1.6 km,
 * which is a rounded number whatever its source, so it keeps the `≈` it has earned. The alternative
 * considered was to print a stated distance to two places so every fact could be exact; it was
 * declined because it makes the runner read `5.00 km` on a start line to buy a distinction that
 * matters only to the one record distance the plan does not use.
 */
fun routeSuggestionHint(targetMeters: Double, targetIsFixed: Boolean = false): String {
    val km = String.format(Locale.UK, "%.1f", targetMeters / 1000.0).removeSuffix(".0")
    // Against what was printed, not against a rounding rule: the question is whether the runner is
    // being shown this distance or a near one. Half a metre of slack, because the comparison is
    // between a decimal read back out of a string and a Double, and a course is not measured to it.
    val printsItExactly = abs(km.toDouble() * 1000.0 - targetMeters) < 0.5
    return if (targetIsFixed && printsItExactly) "Today $km km" else "Today ≈ $km km"
}

/**
 * The courses in the order the picker offers them: nearest today's distance first (#422).
 *
 * Returns [routes] untouched where [targetMeters] is null. That is the "too little history" case
 * and it is not a lesser version of this order — it is the library's own order
 * ([com.example.runningapp.data.RouteDao.getLibraryFlow], newest import first), which stays the
 * order of the library screen either way. Only the picker ever re-sorts, and only when it has
 * something to sort towards.
 *
 * **An estimate is a middle; a stated distance is a floor.** [targetIsFixed] is the whole
 * difference, and it is the same flag [routeSuggestionHint] prints its `≈` from. Where the number
 * is estimated, "nearest" means nearest in either direction: the target is a median multiplied by a
 * planned duration, so a course a hundred metres short and a course a hundred metres long are both
 * about right and the symmetry is the honest answer. Where the plan *stated* the distance — a Test,
 * whose Stage graduates on a time at a set distance — that symmetry offers a course the session
 * cannot be finished on: on a 5 km Test a 4.9 km course is not "as good as" a 5.2 km one, it is a
 * course on which the Test does not happen. So a stated distance ranks every course that reaches it
 * ahead of every course that does not, and only then nearest-first.
 *
 * **The short ones are still ordered, not buried.** Within each of the two groups the order is the
 * same nearest-first it always was, so a runner whose library holds nothing long enough is offered
 * the least-short course first. That is the best of a bad set, and shuffling it would hide the one
 * useful fact left — how far short they are about to be.
 *
 * **"Reaches it" is `>=` with no tolerance.** The alternative considered was to forgive a course
 * measured a little under — 4999 m against a 5000 m Test — on the grounds that these distances come
 * out of imported GPX files and are not exact. It was declined, because a tolerance honest about
 * GPX error would have to be tens of metres wide, and a tolerance that wide is itself a decision to
 * offer a course that may genuinely be short of the Test: it would re-introduce the bug at a size
 * nobody can see. Being strict costs the runner one row of ordering — a 4999 m course drops behind
 * the long-enough ones and sits at the top of the short ones, where nearest-first puts it — and
 * being lax costs them a Test they cannot complete. Neither number is exact either way, so the
 * cheaper mistake is the one that only moves a row.
 *
 * A family needs no case of its own here. The picker lists every length as its own row, so
 * siblings are ranked one by one against the target and the closest of them is simply the one that
 * comes first — which is both "rank on the closest sibling" and "open on it", with no folding for
 * the runner to reach through on a start line.
 *
 * Ties keep the order they arrived in, fixed target or not: `sortedWith` is stable (it is
 * `java.util.List.sort`, a merge sort), and the comparator below only ever compares the two derived
 * numbers — the reach group and the gap — so two courses equal on both are left in the order they
 * arrived in rather than swapping between two draws of the same list.
 */
fun routesNearestFirst(
    routes: List<RouteHeader>,
    targetMeters: Double?,
    targetIsFixed: Boolean = false,
): List<RouteHeader> {
    if (targetMeters == null) return routes
    if (!targetIsFixed) return routes.sortedBy { abs(it.distanceMeters - targetMeters) }
    return routes.sortedWith(
        // 0 for a course that reaches the stated distance, 1 for one that does not: the whole of
        // "long enough first", written as the first key so nearest-first only ever decides between
        // courses on the same side of it.
        compareBy<RouteHeader> { if (it.distanceMeters >= targetMeters) 0 else 1 }
            .thenBy { abs(it.distanceMeters - targetMeters) }
    )
}
