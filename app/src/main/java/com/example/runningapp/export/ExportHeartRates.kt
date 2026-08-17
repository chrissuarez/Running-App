package com.example.runningapp.export

/**
 * How far a heart-rate sample may sit from a moment and still describe it. Samples are written once
 * a second but only while the strap reports a beat, so short drop-outs leave gaps; five seconds
 * bridges a gap without inventing a reading for a real disconnection.
 *
 * Measured from the moment, not across the gap: a moment is described by any real reading taken
 * within five seconds of it, on either side. A ten-second drop-out is therefore covered from both
 * ends and a longer one is not covered in the middle, which is the intent — no moment ever carries a
 * heart rate more than five seconds removed from a beat the strap actually reported.
 */
private const val HR_MATCH_TOLERANCE_SECONDS = 5L

/**
 * The reading nearest [atSecond] within the tolerance, or null where the strap said nothing (#84,
 * #218).
 *
 * Both Exports match this way, because the two files describe one Run: a reading attached to one
 * moment in the GPX and to another in the FIT would be the app disagreeing with itself. The map is
 * [com.example.runningapp.data.heartRatesByWallSecond]'s, shared further down still with the splits
 * table and the route map.
 */
fun Map<Long, Int>.nearestBpm(atSecond: Long): Int? {
    for (offset in 0..HR_MATCH_TOLERANCE_SECONDS) {
        // Earlier before later on a tie: the reading already taken describes the runner better than
        // one that has not happened yet.
        get(atSecond - offset)?.let { return it }
        get(atSecond + offset)?.let { return it }
    }
    return null
}
