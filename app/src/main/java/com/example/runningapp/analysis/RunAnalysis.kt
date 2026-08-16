package com.example.runningapp.analysis

import com.example.runningapp.HrProfile
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.ranAt
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.run.RunMode
import java.time.ZoneId
import kotlin.math.abs

/**
 * Everything the Run detail page draws, worked out from what the Run recorded (#44).
 *
 * The one seam for the whole detail page: the page hands over a Run and its recordings and gets back
 * finished figures, so the composables stay a thin layer that only knows how to put shapes on the
 * screen. Pure and on no clock but the Run's own, so a scripted Run in a unit test is the whole test
 * — nothing here needs a phone. Later tickets extend this same module with the pace and elevation
 * series (#46) and Records (#49); today it produces the heart-rate chart, which every Run in the
 * history has the data for, indoors or out, and the kilometre splits of the ones that have a route.
 */
data class RunAnalysis(
    /** Null when the Run recorded no heart rate worth drawing — the page then has no chart. */
    val chart: RunChart?,
    /**
     * The Run's kilometres (#45). Empty for a treadmill Run and for any Run with no usable track,
     * which is the page's signal to leave the splits table out entirely.
     */
    val splits: List<Split> = emptyList(),
    /**
     * Metres climbed over the whole Run (#45), or null when it recorded no height to climb — the
     * page then shows no elevation line at all rather than a confident zero.
     */
    val elevationGainMeters: Double? = null,
    /**
     * The Run over the ground it covered (#46) — pace and heart rate on an elevation silhouette.
     *
     * Null for a treadmill Run and for any Run with no usable track, which is the page's signal to
     * draw [chart] instead: heart rate over the Run's own clock is the mode every Run can be shown
     * in, and it is all a Run with no route has to offer.
     */
    val distanceChart: DistanceChart? = null,
    /**
     * The Run's route as the map draws it (#47) — null for a treadmill Run, and for any Run whose
     * recording holds no route, which is the page's signal to show no map at all.
     */
    val trackMap: TrackMap? = null,
) {
    companion object {
        /**
         * How long the recording may say nothing before the line is broken rather than drawn across.
         *
         * One second, because that is how often a Run has written a row for as long as the app has
         * recorded one. The very first recorder banked once a second and wrote nothing at all for a
         * second it had no beat for, and every recorder since has done the same; no row with no beat
         * in it has ever been written to the history.
         *
         * So there is nothing to infer from how far apart a Run's readings sit. A second with no row
         * is a second the Strap was lost in, and two readings two seconds apart have a hole between
         * them however tidy the line across it would look.
         */
        private const val RECORDING_BREAK_SECONDS = 1L

        /**
         * [track] is the Run's route, gated for accuracy the way the map gates it
         * ([com.example.runningapp.data.SessionRepository.getTrackPointsForMap]) — a wild fix left
         * in would read as a sprint and put a split on the page nobody ran. Defaults to nothing, so
         * a caller that only wants the chart (a treadmill Run, a Run whose track has not loaded yet)
         * need not pretend to have one.
         *
         * [profile] is the heart rates history is banded against — the Run's route is coloured by
         * the zones they slice (#47), so the map and the zone bars below it are reading the same
         * Run under the same numbers. Null where they are not known, and the route is then drawn in
         * one colour rather than in guessed ones.
         */
        fun of(
            run: RunnerSession,
            samples: List<HrSample>,
            track: List<TrackPoint> = emptyList(),
            profile: HrProfile? = null,
        ): RunAnalysis {
            val ground = groundOf(run, samples, track, profile)
            return RunAnalysis(
                chart = heartRateChart(run, samples),
                splits = ground.splits,
                elevationGainMeters = ground.elevationGainMeters,
                distanceChart = ground.distanceChart,
                trackMap = ground.trackMap,
            )
        }

        private fun heartRateChart(run: RunnerSession, samples: List<HrSample>): RunChart? {
            // The raw reading, not the smoothed one: the chart is a record of the Run, and the
            // smoothed number is a coaching aid that would only flatten it into something it wasn't.
            //
            // A zero is not a slow heart, so no row claiming one is drawn. No recorder has ever
            // written one, but a row that somehow says nothing is a second nothing was measured in
            // either way, and dropping it here leaves exactly the hole that is the truth about it.
            val readings = samples
                .filter { it.rawBpm > 0 }
                .sortedBy { it.elapsedSeconds }
                .map { HeartRateReading(elapsedSeconds = it.elapsedSeconds, bpm = it.rawBpm) }
            if (readings.isEmpty()) return null

            // Held to what a heart can plausibly do before the scale is worked out, so that a Strap
            // glitch reporting a beat no runner has cannot end up with a scale that runs downwards
            // — a run of nothing but such readings would otherwise put its floor above its ceiling.
            // The glitch is still drawn; it rides the edge of the frame.
            return RunChart(
                heartRate = readings.splitWhereNothingWasRecorded(),
                // The Run's own clock, not the last reading's: a Strap that gave up at minute ten of
                // a half hour leaves a line that stops a third of the way across, which is the truth
                // about the recording. Runs still being written, and old rows that banked no
                // duration, fall back to the readings themselves so the chart still has a width.
                elapsedSecondsSpan = maxOf(run.durationSeconds, readings.last().elapsedSeconds),
                bpmFloor = bpmFloorFor(readings.map { it.bpm }),
                bpmCeiling = bpmCeilingFor(readings.map { it.bpm }),
            )
        }

        /** The readings cut into the stretches that may be drawn as one line. */
        private fun List<HeartRateReading>.splitWhereNothingWasRecorded(): List<HeartRateTrace> {
            val traces = mutableListOf(mutableListOf(first()))
            zipWithNext { previous, reading ->
                if (reading.elapsedSeconds - previous.elapsedSeconds > RECORDING_BREAK_SECONDS) {
                    traces += mutableListOf(reading)
                } else {
                    traces.last() += reading
                }
            }
            return traces.map { HeartRateTrace(it) }
        }
    }
}

/** The lowest and highest the beats-per-minute scale is ever allowed to reach. */
private const val LOWEST_PLAUSIBLE_BPM = 40
private const val HIGHEST_PLAUSIBLE_BPM = 220

/** Air above and below the run's own range, so the line never touches the frame. */
private const val BPM_HEADROOM = 5

/**
 * The floor of the beats-per-minute scale: the lowest beat the Run recorded, given air and rounded
 * down to a round number.
 *
 * Held to what a heart can plausibly do before the scale is worked out, so that a Strap glitch
 * reporting a beat no runner has cannot end up with a scale that runs downwards — a run of nothing
 * but such readings would otherwise put its floor above its ceiling. The glitch is still drawn; it
 * rides the edge of the frame.
 *
 * A Run with no heart rate at all still gets a scale, so the combined chart has a frame to draw its
 * pace and its ground in.
 */
internal fun bpmFloorFor(beats: List<Int>): Int {
    val lowest = (beats.minOrNull() ?: LOWEST_PLAUSIBLE_BPM)
        .coerceIn(LOWEST_PLAUSIBLE_BPM, HIGHEST_PLAUSIBLE_BPM)
    return (lowest - BPM_HEADROOM).coerceAtLeast(LOWEST_PLAUSIBLE_BPM) / 10 * 10
}

/** See [bpmFloorFor] — the same rule at the top of the scale. */
internal fun bpmCeilingFor(beats: List<Int>): Int {
    val highest = (beats.maxOrNull() ?: HIGHEST_PLAUSIBLE_BPM)
        .coerceIn(LOWEST_PLAUSIBLE_BPM, HIGHEST_PLAUSIBLE_BPM)
    return ((highest + BPM_HEADROOM).coerceAtMost(HIGHEST_PLAUSIBLE_BPM) + 9) / 10 * 10
}

/** One heart rate, at the second of the Run it was measured in. */
data class HeartRateReading(val elapsedSeconds: Long, val bpm: Int)

/** A stretch of the Run that was recorded without a break, and so may be drawn as one line. */
data class HeartRateTrace(val readings: List<HeartRateReading>)

/**
 * The Run's heart rate over its own elapsed clock, ready to be drawn.
 *
 * Heart rate over time is the mode every Run can be shown in — a treadmill Run and history recorded
 * before the app kept a GPS track have nothing else to plot against. #46 adds the pace and elevation
 * series and the distance axis that outdoor Runs can also offer.
 */
data class RunChart(
    val heartRate: List<HeartRateTrace>,
    val elapsedSecondsSpan: Long,
    val bpmFloor: Int,
    val bpmCeiling: Int
) {
    /**
     * What the runner's finger is over: the nearest heart rate actually recorded at that second of
     * the Run, or null where nothing was.
     *
     * Null rather than the nearest reading at any distance, because the chart's breaks are the point
     * — dragging into the stretch the Strap dropped out of should say so, not hand back a beat from
     * before it. The one exception is a finger just off either end of the whole recording.
     */
    fun readingAt(elapsedSeconds: Long): HeartRateReading? {
        val inside = heartRate.firstOrNull {
            elapsedSeconds >= it.readings.first().elapsedSeconds &&
                elapsedSeconds <= it.readings.last().elapsedSeconds
        }
        if (inside != null) {
            return inside.readings.minByOrNull { abs(it.elapsedSeconds - elapsedSeconds) }
        }
        // Outside every drawn stretch: either side of the recording as a whole, or in one of its
        // breaks. Only the first case is forgiven — a break says nothing was recorded there, and
        // the readout must say the same however short the break is.
        val begins = heartRate.first().readings.first()
        val ends = heartRate.last().readings.last()
        return when {
            elapsedSeconds < begins.elapsedSeconds &&
                begins.elapsedSeconds - elapsedSeconds <= SCRUB_EDGE_TOLERANCE_SECONDS -> begins
            elapsedSeconds > ends.elapsedSeconds &&
                elapsedSeconds - ends.elapsedSeconds <= SCRUB_EDGE_TOLERANCE_SECONDS -> ends
            else -> null
        }
    }

    private companion object {
        /**
         * How far past either end of the whole recording a finger may sit and still read it.
         *
         * A finger dragged to the very edge of the chart asks about second zero, or about the
         * Run's last second — and a recording that began a moment after the clock did, or whose
         * Strap stopped a moment before it, has no reading exactly there. Without this the readout
         * would blank at precisely the ends of the Run the runner is most likely to drag to.
         *
         * The ends of the recording, not the ends of every drawn stretch: a break in the middle is
         * a stretch nothing was measured in, and a finger inside one gets no reading at all.
         */
        const val SCRUB_EDGE_TOLERANCE_SECONDS = 5L
    }
}

/**
 * What the Run is called at the top of its own page: the part of the day it was run in, the way a
 * runner would name it (#44). Replaces "Session Summary", which named the screen rather than the Run.
 */
fun runHeadline(run: RunnerSession, zoneId: ZoneId = ZoneId.systemDefault()): String {
    // The hour the runner set off, on the clock they set off under (#304).
    val hour = run.ranAt(zoneId).hour
    val partOfDay = when {
        hour < 5 -> "Night"
        hour < 12 -> "Morning"
        hour < 17 -> "Afternoon"
        hour < 21 -> "Evening"
        else -> "Night"
    }
    // Said in the title because the page cannot show it any other way: a treadmill Run has no route
    // to draw and no distance measured off the ground, so nothing below would tell the runner which
    // kind of Run they are looking at.
    //
    // A Walk is named as one for the same reason (#275) — it is the runner's own word about the
    // outing, and calling it a Run at the top of its own page would contradict the marker the
    // History row shows it under.
    val outing = if (run.isWalk) "Walk" else "Run"
    val kind = if (RunMode.ofSettingValue(run.runMode) == RunMode.TREADMILL) "Treadmill $outing" else outing
    return "$partOfDay $kind"
}
