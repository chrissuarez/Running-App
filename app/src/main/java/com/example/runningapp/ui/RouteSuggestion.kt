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
 * **The plan never states a distance.** A Workout prescribes seconds and a heart-rate zone
 * ([WorkoutTemplate]) and nothing anywhere in it says metres. So the target has to be derived, and
 * it is derived by plain arithmetic on this phone: today's planned seconds × a pace off the
 * runner's own recent Runs. Nothing here reaches the network, asks the AI coach or reads a consent
 * flag, and it must stay that way — "which of these is nearest seven kilometres" is arithmetic, and
 * the seven is already an estimate, so a second estimate laid on top of it can only make it worse.
 *
 * **The pace is taken from Runs of the same kind, never from recent Runs at large.** A Long Run and
 * a set of strides cover ground at paces that are not the same number and were never meant to be;
 * blend them and one hard Quality Run drags the average up and sends the runner out on a nine
 * kilometre "easy" route. Asking one kind at a time is also what makes the arithmetic honest at
 * both ends: a Long Run's planned hour includes its walk breaks, and so did the hours the Long Runs
 * behind it were measured over.
 *
 * **Too little history suggests nothing at all.** See [ROUTE_SUGGESTION_MIN_RUNS].
 */

/**
 * How many Runs of today's kind must be in the window before a distance is suggested.
 *
 * Three, and the alternative considered was one. A single Run is not a pace, it is a day — the day
 * it rained, or the day the runner stopped to tie a lace — and a picker that reordered itself
 * around it would be confidently wrong at exactly the moment the runner has least history to notice
 * it with. Three is the smallest count at which the median is a middle value rather than the only
 * value.
 *
 * Below it the picker shows no hint and keeps the library's own order, which is what it did before
 * this shipped: no suggestion is a state the runner already understands, and a guess is not.
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
 * Null is the honest answer in four cases, and every one of them leaves the picker exactly as it
 * was: no plan today, fewer than [ROUTE_SUGGESTION_MIN_RUNS] Runs of today's kind in the window, a
 * Workout that plans no time at all, and a median pace of nought (a set of Runs that measured a
 * clock but no ground, which the query already refuses but which arithmetic must not depend on).
 */
fun suggestedRouteDistanceMeters(
    workout: WorkoutTemplate?,
    recentRuns: List<RunPaceRow>,
): Double? {
    val plannedSeconds = workout?.plannedSeconds ?: return null
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
 * The line above the picker: `Today ≈ 7 km`.
 *
 * One decimal place, where every other distance in the app is written to two ([routeDistanceLabel]).
 * That is deliberate and it is the whole difference between a measurement and an estimate: this
 * number is a planned duration multiplied by a median, and printing it as `7.24 km` would claim a
 * precision that neither of its two inputs has. A trailing nought goes, so a round answer reads as
 * `Today ≈ 7 km` rather than `Today ≈ 7.0 km`.
 *
 * `≈` and not `=`, for the same reason, and it is the first character the runner's eye lands on
 * after the word "Today".
 */
fun routeSuggestionHint(targetMeters: Double): String {
    val km = String.format(Locale.UK, "%.1f", targetMeters / 1000.0)
    return "Today ≈ ${km.removeSuffix(".0")} km"
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
 * A family needs no case of its own here. The picker lists every length as its own row, so
 * siblings are ranked one by one against the target and the closest of them is simply the one that
 * comes first — which is both "rank on the closest sibling" and "open on it", with no folding for
 * the runner to reach through on a start line.
 *
 * Ties keep the order they arrived in: `sortedBy` is stable, so two courses the same distance from
 * today's target stay newest-first between themselves rather than swapping between two draws of the
 * same list.
 */
fun routesNearestFirst(routes: List<RouteHeader>, targetMeters: Double?): List<RouteHeader> {
    if (targetMeters == null) return routes
    return routes.sortedBy { abs(it.distanceMeters - targetMeters) }
}
