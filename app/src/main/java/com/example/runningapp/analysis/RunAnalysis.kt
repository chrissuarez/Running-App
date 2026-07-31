package com.example.runningapp.analysis

import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.run.RunMode
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Everything the Run detail page draws, worked out from what the Run recorded (#44).
 *
 * The one seam for the whole detail page: the page hands over a Run and its recordings and gets back
 * finished figures, so the composables stay a thin layer that only knows how to put shapes on the
 * screen. Pure and on no clock but the Run's own, so a scripted Run in a unit test is the whole test
 * — nothing here needs a phone. Later tickets extend this same module with Splits (#45), pace and
 * elevation (#46) and Records (#49); today it produces the heart-rate chart, which every Run in the
 * history has the data for, indoors or out.
 */
data class RunAnalysis(
    /** Null when the Run recorded no heart rate worth drawing — the page then has no chart. */
    val chart: RunChart?
) {
    companion object {
        /**
         * The longest a break may ever have to be, however loosely the Run was sampled.
         *
         * A Run with only a couple of readings in it has no cadence to read off, and must not end up
         * so tolerant that a silence of minutes is drawn as a line.
         */
        private const val LONGEST_RECORDING_BREAK_SECONDS = 60L

        /** The lowest and highest the beats-per-minute scale is ever allowed to reach. */
        private const val LOWEST_PLAUSIBLE_BPM = 40
        private const val HIGHEST_PLAUSIBLE_BPM = 220

        /** Air above and below the run's own range, so the line never touches the frame. */
        private const val BPM_HEADROOM = 5

        fun of(run: RunnerSession, samples: List<HrSample>): RunAnalysis =
            RunAnalysis(chart = heartRateChart(run, samples))

        private fun heartRateChart(run: RunnerSession, samples: List<HrSample>): RunChart? {
            // The raw reading, not the smoothed one: the chart is a record of the Run, and the
            // smoothed number is a coaching aid that would only flatten it into something it wasn't.
            //
            // A zero is not a slow heart — it is a second the Strap reported nothing in, written
            // down by history old enough to have banked a row for such a second at all. A Run
            // recorded today writes no row for one, so both kinds of no-data second end up as the
            // same thing here: a hole in the recording, to be left as a break in the line rather
            // than drawn as a dive to the floor.
            val readings = samples
                .filter { it.rawBpm > 0 }
                .sortedBy { it.elapsedSeconds }
                .map { HeartRateReading(elapsedSeconds = it.elapsedSeconds, bpm = it.rawBpm) }
            if (readings.isEmpty()) return null

            // Held to what a heart can plausibly do before the scale is worked out, so that a Strap
            // glitch reporting a beat no runner has cannot end up with a scale that runs downwards
            // — a run of nothing but such readings would otherwise put its floor above its ceiling.
            // The glitch is still drawn; it rides the edge of the frame.
            val lowest = readings.minOf { it.bpm }.coerceIn(LOWEST_PLAUSIBLE_BPM, HIGHEST_PLAUSIBLE_BPM)
            val highest = readings.maxOf { it.bpm }.coerceIn(LOWEST_PLAUSIBLE_BPM, HIGHEST_PLAUSIBLE_BPM)
            return RunChart(
                heartRate = readings.splitWhereNothingWasRecorded(),
                // The Run's own clock, not the last reading's: a Strap that gave up at minute ten of
                // a half hour leaves a line that stops a third of the way across, which is the truth
                // about the recording. Runs still being written, and old rows that banked no
                // duration, fall back to the readings themselves so the chart still has a width.
                elapsedSecondsSpan = maxOf(run.durationSeconds, readings.last().elapsedSeconds),
                bpmFloor = floorToTen((lowest - BPM_HEADROOM).coerceAtLeast(LOWEST_PLAUSIBLE_BPM)),
                bpmCeiling = ceilingToTen((highest + BPM_HEADROOM).coerceAtMost(HIGHEST_PLAUSIBLE_BPM))
            )
        }

        private fun List<HeartRateReading>.splitWhereNothingWasRecorded(): List<HeartRateTrace> {
            val breakSeconds = recordingBreakSeconds()
            val traces = mutableListOf(mutableListOf(first()))
            zipWithNext { previous, reading ->
                if (reading.elapsedSeconds - previous.elapsedSeconds > breakSeconds) {
                    traces += mutableListOf(reading)
                } else {
                    traces.last() += reading
                }
            }
            return traces.map { HeartRateTrace(it) }
        }

        /**
         * How long this Run's recording may say nothing before the line is broken rather than drawn
         * across — read off the recording itself, because the history holds two kinds of Run.
         *
         * A Run recorded since the app banked a second at a time writes a row for each second it had
         * a beat for and no row at all for the seconds the Strap was lost in, so on those Runs a
         * couple of missing seconds is a real hole, however short it looks. Older history was
         * sampled loosely, and the seconds between its readings are simply how it was written down.
         *
         * The Run's own typical step between readings is therefore the unit: a step is what this
         * recording does when nothing is wrong, and anything past twice it is a stretch nothing was
         * recorded in. Never more than [LONGEST_RECORDING_BREAK_SECONDS], so a Run too short to have
         * a cadence still breaks on a real silence.
         */
        private fun List<HeartRateReading>.recordingBreakSeconds(): Long {
            val steps = zipWithNext { previous, reading -> reading.elapsedSeconds - previous.elapsedSeconds }
            // One reading has no step to measure and no gap to break, so nothing can exceed this.
            if (steps.isEmpty()) return Long.MAX_VALUE
            val typicalStep = steps.sorted()[steps.size / 2].coerceAtLeast(1L)
            return (typicalStep * 2).coerceAtMost(LONGEST_RECORDING_BREAK_SECONDS)
        }

        private fun floorToTen(bpm: Int): Int = bpm / 10 * 10

        private fun ceilingToTen(bpm: Int): Int = (bpm + 9) / 10 * 10
    }
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
    val hour = Instant.ofEpochMilli(run.startTime).atZone(zoneId).hour
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
    val kind = if (RunMode.ofSettingValue(run.runMode) == RunMode.TREADMILL) "Treadmill Run" else "Run"
    return "$partOfDay $kind"
}
