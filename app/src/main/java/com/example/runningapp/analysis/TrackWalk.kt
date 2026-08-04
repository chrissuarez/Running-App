package com.example.runningapp.analysis

import com.example.runningapp.data.TrackLeg
import com.example.runningapp.data.TrackPoint
import kotlin.math.roundToInt

/**
 * The two readings every fix of a Run's track is judged by, shared by everything drawn off one.
 *
 * The combined chart (#46) and the route map (#47) both have to know where the recording broke and
 * what the runner's heart was doing over each stretch of ground. Two walks would be two chances to
 * answer differently, and a chart and a map disagreeing about where a Run left its zone is worse
 * than either being absent.
 */

/**
 * Which unbroken stretch of the recording each fix belongs to — the number goes up at every break.
 *
 * A leg that recorded nothing carries no ground and no seconds
 * ([com.example.runningapp.data.TrackLeg]), so a fix that resumes the recording has nothing before
 * it to have been measured over. Everything reading the shape of a Run cuts here.
 */
internal fun stretchOfEachFix(legs: List<TrackLeg>): IntArray {
    val stretchOfFix = IntArray(legs.size + 1)
    legs.forEachIndexed { i, leg -> stretchOfFix[i + 1] = stretchOfFix[i] + if (leg.recorded) 0 else 1 }
    return stretchOfFix
}

/**
 * Every heart rate recorded since the previous fix, averaged, at each fix — null where none was.
 *
 * Averaged rather than sampled, so a sparsely recorded track folds its beats in rather than throwing
 * all but one of them away: on a track backfilled from breadcrumbs a single beat would otherwise
 * speak for hundreds of metres.
 *
 * The first fix of each stretch counts only its own second: the beats before it were measured over
 * ground the recording did not witness.
 */
internal fun bpmAtEachFix(
    points: List<TrackPoint>,
    stretchOfFix: IntArray,
    bpmByWallSecond: Map<Long, Int>,
): List<Int?> {
    val secondAtFix = points.map { it.timestampMillis / 1000 }
    return points.indices.map { i ->
        val startsStretch = i == 0 || stretchOfFix[i] != stretchOfFix[i - 1]
        val since = if (startsStretch) secondAtFix[i] - 1 else secondAtFix[i - 1]
        bpmByWallSecond.averageBetween(afterSecond = since, toSecond = secondAtFix[i])
    }
}

/** The average of every heart rate recorded in `(afterSecond, toSecond]`, or null where none was. */
private fun Map<Long, Int>.averageBetween(afterSecond: Long, toSecond: Long): Int? {
    if (isEmpty()) return null
    val readings = ((afterSecond + 1)..toSecond).mapNotNull { this[it] }
    return if (readings.isEmpty()) null else readings.average().roundToInt()
}
