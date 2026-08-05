package com.example.runningapp.training

import com.example.runningapp.HrProfile
import com.example.runningapp.HrZone
import com.example.runningapp.hrZoneOf
import com.example.runningapp.zoneLowerBpm
import kotlin.math.roundToInt

/** Minutes are what the weights are counted in; seconds are what a Run banks. */
private const val SECONDS_PER_MINUTE = 60.0

/**
 * What one second at [bpm] is worth — and, through every caller below, the whole of the Effort
 * Score: Edwards zone-weighted TRIMP over a Run's own seconds (#61).
 *
 * A second is worth its zone number, 1 through 5, and the Score is those weights added up over
 * minutes. So an hour in Zone 3 scores 180, and an hour of the same *average* heart rate reached by
 * alternating hard running with walk breaks scores more, because the running seconds are weighted as
 * running rather than being averaged down by the walking ones. That is the whole reason this is a
 * function of one second and never of a Run's average BPM: this app's runners take walk breaks, and
 * an average is exactly the thing that hides them (`docs/research/training-load-model.md`, #21).
 *
 * Pure, and deliberately: no Android, no database, no settings — beats and a [HrProfile] are the
 * only inputs. That is what lets the Run bank a Score second by second as it accrues while a stored
 * Run is re-scored from its saved samples, with one definition of the arithmetic under both.
 *
 * **Zones are the app's zones**, sliced from heart-rate reserve (ADR 0004) rather than from Max HR
 * alone as the research note assumed — the note predates the reserve model, and the weights ride on
 * whatever the zone edges are. With no resting heart rate stated the two are the same model edge for
 * edge ([com.example.runningapp.RESTING_HR_UNSTATED]), which is why #61's golden %HRmax figures still
 * hold. Reading through the one door is what keeps a Run's Effort Score and the zone chart beside it
 * describing the same seconds; a Score computed against edges the chart does not draw would be a
 * number nobody could check.
 *
 * The one place they part is the bottom, and it is intentional (#99): the chart's Zone 1 swallows
 * everything below its lower edge so that no second vanishes from the picture, while here anything
 * under that edge weighs **nothing**. Idling is not training. This is why `zone1Seconds` is not
 * substitutable for zone 1 time here, however well the boundaries match.
 *
 * Zero also covers the second with no reading at all — a dropout, or a Run with no Strap.
 */
fun effortWeightOf(bpm: Int, profile: HrProfile): Int {
    if (bpm < zoneLowerBpm(HrZone.ENDURANCE, profile)) return 0
    return hrZoneOf(bpm, profile)?.number ?: 0
}

/**
 * The Score for a Run whose weighted seconds have already been banked one at a time.
 *
 * Rounded to the nearest whole number rather than truncated: #61 asks for an integer, and a Run that
 * weighed 89 seconds is nearer to 1 than to 2 either way you say it.
 */
fun effortScoreOfWeightedSeconds(weightedSeconds: Long): Int =
    (weightedSeconds / SECONDS_PER_MINUTE).roundToInt()

/**
 * The Score for a Run's beats, one per second — or null for a Run that recorded none.
 *
 * The same answer as banking each second through [effortWeightOf] as the Run goes, and for the same
 * reason the zone re-tally reproduces the Run's live one ([com.example.runningapp.tallyZoneSeconds]):
 * the recorder writes exactly one sample per second of the Run and only when BPM > 0, which is the
 * same condition under which the Run banked a weighted second. Seconds with no signal have no sample
 * and weigh nothing on either side.
 *
 * Null and zero are different answers and both are reachable: a Run with no beats has no Score to
 * show, while a Run that spent every second below Zone 1 scores 0, which is a measurement.
 */
fun effortScoreOf(bpms: Iterable<Int>, profile: HrProfile): Int? {
    var weightedSeconds = 0L
    var beats = 0
    bpms.forEach { bpm ->
        beats++
        weightedSeconds += effortWeightOf(bpm, profile)
    }
    if (beats == 0) return null
    return effortScoreOfWeightedSeconds(weightedSeconds)
}
