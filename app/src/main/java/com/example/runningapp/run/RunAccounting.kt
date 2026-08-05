package com.example.runningapp.run

import com.example.runningapp.HrProfile
import com.example.runningapp.HrZone
import com.example.runningapp.ZoneBand
import com.example.runningapp.ZoneSeconds
import com.example.runningapp.plusSecondIn
import com.example.runningapp.training.effortScoreOfWeightedSeconds
import com.example.runningapp.training.effortWeightOf
import com.example.runningapp.zoneBandOf
import kotlin.math.roundToInt

/**
 * What the Run has counted so far.
 *
 * Banked one second at a time against the heart-rate profile pinned at START (see [RunConfig]),
 * never against whatever Settings says now — a Run that banked its first minutes against one Max HR
 * and its last against another meant two things at once.
 *
 * A second with no reading is banked as [noDataSeconds] rather than dropped. That covers both a Run
 * with no Strap at all and a dropout that zeroed the reading (#110): the clock kept running, so the
 * summary's zone breakdown must account for those seconds rather than quietly understating the Run.
 */
data class RunTally(
    val zoneSeconds: ZoneSeconds = ZoneSeconds(),
    /**
     * Seconds spent above the target band. Banked by band rather than by zone number, because
     * Zone 5 charts wider than it bands — zone arithmetic would find no zone above a target of 5.
     *
     * Counted but not yet saved anywhere: the record has no column for it, which is true of the
     * service today too. It is banked here because it is what the number would come from.
     */
    val aboveTargetSeconds: Long = 0,
    val noDataSeconds: Long = 0,
    val maxBpm: Int = 0,
    val bpmSum: Long = 0,
    /** Seconds that had a reading to average — the divisor for [averageBpm]. */
    val bpmSeconds: Long = 0,
    /**
     * Each second's zone weight, added up — what the Run's Effort score is made of (#61).
     *
     * Banked as the Run goes rather than re-derived from the saved samples afterwards, for the
     * reason [zoneSeconds] is: these are the seconds the runner was actually coached through, under
     * the profile pinned at START. Seconds below Zone 1 add nothing, which is what separates this
     * from [zoneSeconds] — see [effortWeightOf].
     */
    val effortWeightedSeconds: Long = 0,
) {
    /** The Run's mean heart rate over the seconds that had one, and 0 for a Run with none. */
    val averageBpm: Int get() = if (bpmSeconds > 0) (bpmSum / bpmSeconds).toInt() else 0

    /**
     * What the Run cost, or null for a Run that never read a heart rate at all.
     *
     * A Run with beats always has a score even if every one of them was below Zone 1: that is a 0,
     * and it says the hour was easy. A Run with no beats has nothing to say.
     */
    val effortScore: Int?
        get() = if (bpmSeconds > 0) effortScoreOfWeightedSeconds(effortWeightedSeconds) else null

    /**
     * One second with a reading, in the [zone] its caller resolved it to.
     *
     * The zone arrives already decided rather than being looked up here, because deciding it is the
     * same act as deciding whether there was a reading at all — see [Run]'s `bankSecond`. That is
     * what leaves this with no "and if it had no zone" branch to write: #115's dead one, which
     * banked a no-data second from inside a positive-reading guard, has nowhere to go.
     *
     * [effortWeightedSeconds] is the one thing here that goes back to the [bpm], because [zone] is
     * not enough to answer it: Zone 1 swallows everything below its lower edge so no second vanishes
     * from the chart, and the load model must not credit that time as training (#99). What the
     * second charts as and what it cost are genuinely two questions.
     */
    fun bank(zone: HrZone, bpm: Int, profile: HrProfile, targetZone: HrZone): RunTally = copy(
        zoneSeconds = zoneSeconds.plusSecondIn(zone),
        aboveTargetSeconds =
            if (zoneBandOf(bpm, profile, targetZone) == ZoneBand.ABOVE) aboveTargetSeconds + 1
            else aboveTargetSeconds,
        maxBpm = maxOf(maxBpm, bpm),
        bpmSum = bpmSum + bpm,
        bpmSeconds = bpmSeconds + 1,
        effortWeightedSeconds = effortWeightedSeconds + effortWeightOf(bpm, profile),
    )

    /** One second with none. */
    fun bankNoData(): RunTally = copy(noDataSeconds = noDataSeconds + 1)
}

/** One reading from the Strap, kept only long enough to be averaged. */
data class HrReading(val atMillis: Long, val bpm: Int)

/** How long a reading stays in the rolling average. */
const val HR_WINDOW_MILLIS = 5_000L

/**
 * What the Strap is saying, and the short rolling average the coach reasons about.
 *
 * The coach never judges a single packet: a five-second average is what decides the band, so one
 * stray beat cannot send the runner a cue. [bpm] — the raw reading — is what the second is banked
 * as, and what a saved sample records; [smoothedBpm] is what the coach and the sample's smoothed
 * column carry.
 *
 * The window is fed only by samples that reach the coach. That is the service's shape reproduced
 * rather than designed: while coaching is off, or during the cool-down, packets keep arriving and
 * the window stands still.
 */
data class RunHeartRate(
    val bpm: Int = 0,
    val smoothedBpm: Int = 0,
    val connectionStatus: String = "",
    val recent: List<HrReading> = emptyList(),
) {
    /** No reading to be had: the Strap went away, so the last one must not be held as if fresh. */
    fun lost(connectionStatus: String): RunHeartRate =
        copy(bpm = 0, smoothedBpm = 0, connectionStatus = connectionStatus)

    /** A reading arrived, but not one the coach is listening for. */
    fun read(bpm: Int, connectionStatus: String, coachingEnabled: Boolean): RunHeartRate = copy(
        bpm = bpm,
        smoothedBpm = smoothedOf(recent, coachingEnabled),
        connectionStatus = connectionStatus,
    )

    /** A reading the coach is listening for: it joins the window, and the window is aged out. */
    fun heard(bpm: Int, connectionStatus: String, nowMillis: Long): RunHeartRate {
        val window = (recent + HrReading(nowMillis, bpm))
            .filter { nowMillis - it.atMillis <= HR_WINDOW_MILLIS }
        return copy(
            bpm = bpm,
            smoothedBpm = smoothedOf(window, coachingEnabled = true),
            connectionStatus = connectionStatus,
            recent = window,
        )
    }

    private companion object {
        /**
         * The smoothed number as the service publishes it — the window's average, except that with
         * coaching off it is the last raw reading. That second rule is reproduced, not chosen: it
         * falls out of the debug read-out being the one place the number was computed.
         */
        fun smoothedOf(window: List<HrReading>, coachingEnabled: Boolean): Int = when {
            window.isEmpty() -> 0
            !coachingEnabled -> window.last().bpm
            else -> window.map { it.bpm }.average().roundToInt()
        }
    }
}
