package com.example.runningapp.analysis

import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
import com.example.runningapp.recording.geodesicDistanceMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scripted runs, cut into splits. Each test lays out a run as a sequence of legs — a speed held for
 * a number of seconds, a pause, a gap in the recording — and asks what the table makes of it.
 */
class SplitsTest {

    @Test
    fun `an even run is cut into whole kilometres at one pace`() {
        // 3 m/s for 1000s: 3000 m, 5:33 /km throughout.
        val splits = splitsOfRun(aRun(), noSamples, script { running(3.0, seconds = 1000) })

        assertEquals(3, splits.size)
        assertEquals(listOf(1, 2, 3), splits.map { it.number })
        splits.forEach {
            assertEquals(1000.0, it.distanceMeters, 0.5)
            assertEquals(1000.0 / 3.0 / 60.0, it.paceMinPerKm, 0.01)
            assertEquals(false, it.isPartial)
        }
    }

    @Test
    fun `the leftover final stretch is a partial split at its own projected pace`() {
        // 3 m/s for 1000s, then 2 m/s for 200s: 3000 m + 400 m, the last at 8:20 /km.
        val splits = splitsOfRun(
            aRun(),
            noSamples,
            script {
                running(3.0, seconds = 1000)
                running(2.0, seconds = 200)
            }
        )

        assertEquals(4, splits.size)
        val partial = splits.last()
        assertTrue(partial.isPartial)
        assertEquals(4, partial.number)
        assertEquals(400.0, partial.distanceMeters, 1.0)
        // The pace a whole kilometre at that speed would have been run in - the projection.
        assertEquals(1000.0 / 2.0 / 60.0, partial.paceMinPerKm, 0.02)
    }

    @Test
    fun `a run that finishes on the kilometre grows no partial split`() {
        val splits = splitsOfRun(aRun(), noSamples, script { running(2.0, seconds = 1000) })

        assertEquals(2, splits.size)
        assertTrue(splits.none { it.isPartial })
    }

    @Test
    fun `bars are scaled against the slowest split`() {
        // Three kilometres run at 4, 2 and 2.5 m/s - so the second is the slowest.
        val splits = splitsOfRun(
            aRun(),
            noSamples,
            script {
                running(4.0, seconds = 250)
                running(2.0, seconds = 500)
                running(2.5, seconds = 400)
            }
        )

        assertEquals(3, splits.size)
        assertEquals(1.0, splits[1].relativePace, 0.001)
        // 4:10 and 6:40 against the slowest kilometre's 8:20.
        assertEquals(0.5, splits[0].relativePace, 0.01)
        assertEquals(0.8, splits[2].relativePace, 0.01)
    }

    @Test
    fun `the partial split contests the scale like any other`() {
        // Two fast kilometres and a slow 300 m limp home: the limp sets the scale.
        val splits = splitsOfRun(
            aRun(),
            noSamples,
            script {
                running(4.0, seconds = 500)
                running(1.0, seconds = 300)
            }
        )

        assertEquals(3, splits.size)
        assertEquals(1.0, splits.last().relativePace, 0.001)
        assertTrue(splits.last().isPartial)
    }

    @Test
    fun `pace is measured over moving time, so a rest inside a kilometre does not slow it`() {
        // 500 m run, 60s stood still, 500 m run. The standstill is rest, so the kilometre reads as
        // the 1000 m of running it was - the same clock the run's own average pace is quoted on.
        val splits = splitsOfRun(
            aRun(),
            noSamples,
            script {
                running(2.0, seconds = 250)
                running(0.0, seconds = 60)
                running(2.0, seconds = 250)
            }
        )

        assertEquals(1, splits.size)
        assertEquals(1000.0 / 2.0 / 60.0, splits.single().paceMinPerKm, 0.05)
    }

    @Test
    fun `a recorded pause carries neither ground nor seconds into the split`() {
        // The runner pauses and walks 400 m to a shop before resuming. Nothing about that stretch
        // was recorded, so the split gets none of it: only the two 500 m halves of real running.
        val splits = splitsOfRun(
            aRun(),
            noSamples,
            script {
                running(2.0, seconds = 250)
                pauseAndMoveOn(meters = 400.0, seconds = 300)
                running(2.0, seconds = 250)
            }
        )

        assertEquals(1, splits.size)
        val split = splits.single()
        assertEquals(1000.0, split.distanceMeters, 1.0)
        // 1000 m of recorded ground at 2 m/s - the paused 400 m never joins it.
        assertEquals(1000.0 / 2.0 / 60.0, split.paceMinPerKm, 0.05)
    }

    @Test
    fun `a gap in the recording is not read as a sprint`() {
        // Two minutes of lost signal covering 600 m. Counting that ground against the seconds moving
        // time already refuses would put an impossible split on the page.
        val splits = splitsOfRun(
            aRun(),
            noSamples,
            script {
                running(2.0, seconds = 250)
                gap(meters = 600.0, seconds = 120)
                running(2.0, seconds = 250)
            }
        )

        assertEquals(1, splits.size)
        assertEquals(1000.0 / 2.0 / 60.0, splits.single().paceMinPerKm, 0.05)
    }

    @Test
    fun `a sparse backfilled track is still cut at the kilometre`() {
        // A breadcrumb every 15s, 30 m apart. The kilometre marker lands inside a leg every time,
        // and each split still reads the 8:20 /km the runner actually held.
        //
        // Fifteen seconds, not a minute: the backfill wrote one point per heart-rate sample that
        // carried a position, and those were banked once a second. A sparse backfilled track is one
        // whose fixes were slow to move, not one recorded at a slow cadence - so it stays inside
        // TRACK_BREAK_MS, and a track that did not would be read as broken by every other measure
        // in the app too.
        val splits = splitsOfRun(
            aRun(),
            noSamples,
            script { sparse(meters = 30.0, seconds = 15, fixes = 100) }
        )

        assertEquals(3, splits.size)
        assertEquals(1000.0, splits[0].distanceMeters, 0.5)
        splits.take(2).forEach {
            assertEquals(1000.0 / 2.0 / 60.0, it.paceMinPerKm, 0.05)
        }
    }

    @Test
    fun `each split carries the average of the heart rates recorded inside it`() {
        // 130 bpm through the first kilometre, 160 through the second.
        val run = aRun()
        val samples = (0 until 1000).map { second ->
            HrSample(
                sessionId = 1,
                elapsedSeconds = second.toLong(),
                rawBpm = if (second < 500) 130 else 160,
                smoothedBpm = 0,
                connectionState = "CONNECTED",
                timestampMillis = run.startTime + second * 1000,
            )
        }
        val splits = splitsOfRun(run, samples, script { running(2.0, seconds = 1000) })

        assertEquals(2, splits.size)
        assertEquals(130, splits[0].averageBpm)
        assertEquals(160, splits[1].averageBpm)
    }

    @Test
    fun `a split the strap recorded nothing in has no heart rate rather than a made-up one`() {
        val splits = splitsOfRun(aRun(), noSamples, script { running(2.0, seconds = 1000) })

        splits.forEach { assertNull(it.averageBpm) }
    }

    @Test
    fun `a treadmill run has no splits`() {
        val splits = splitsOfRun(
            aRun(runMode = "treadmill"),
            noSamples,
            script { running(3.0, seconds = 1000) }
        )

        assertTrue(splits.isEmpty())
    }

    @Test
    fun `a run with no track has no splits`() {
        assertTrue(splitsOfRun(aRun(), noSamples, emptyList()).isEmpty())
    }

    @Test
    fun `a run shorter than a kilometre is a single partial split`() {
        val splits = splitsOfRun(aRun(), noSamples, script { running(2.0, seconds = 200) })

        assertEquals(1, splits.size)
        assertTrue(splits.single().isPartial)
        assertEquals(400.0, splits.single().distanceMeters, 1.0)
    }

    // -- Elevation ---------------------------------------------------------------------------

    @Test
    fun `a barometer run banks the climb it measured`() {
        // Up 50 m over the first kilometre, back down over the second.
        val track = script {
            running(2.0, seconds = 500, climbMeters = 50.0, barometer = true)
            running(2.0, seconds = 500, climbMeters = -50.0, barometer = true)
        }
        val splits = splitsOfRun(aRun(), noSamples, track)

        assertEquals(50.0, splits[0].elevationGainMeters!!, 2.0)
        assertEquals(0.0, splits[1].elevationGainMeters!!, 0.5)
        assertEquals(50.0, elevationGainOf(aRun(), track)!!, 2.0)
    }

    @Test
    fun `a phone with no barometer banks the climb from its GPS altitude`() {
        // The tier every phone without a pressure sensor falls to. Fifty metres over the first
        // kilometre is well past the ten-metre threshold GPS heights are held to.
        val track = script {
            running(2.0, seconds = 500, climbMeters = 50.0, gps = true)
            running(2.0, seconds = 500, climbMeters = -50.0, gps = true)
        }
        val splits = splitsOfRun(aRun(), noSamples, track)

        // Somewhere between 40 and 50: a climb is banked in threshold-sized bites, so the last bite
        // of a hill - up to the ten metres this tier holds GPS heights to - is still being climbed
        // when the top arrives and is never banked. Under-reporting a hill by less than its
        // threshold is the trade every one of these accumulators makes, Strava's included.
        assertEquals(45.0, elevationGainOf(aRun(), track)!!, 5.5)
        assertEquals(45.0, splits[0].elevationGainMeters!!, 5.5)
        assertEquals(0.0, splits[1].elevationGainMeters!!, 1.0)
    }

    @Test
    fun `a fix that disowns its own height is passed over`() {
        // One fix mid-climb reports a 60 m vertical error and a height 300 m out with it. Trusted,
        // it would bank a mountain; the last height the run stood behind stands in for it instead.
        val track = script {
            running(2.0, seconds = 200, climbMeters = 20.0, gps = true)
            wildGpsHeight(offsetMeters = 300.0)
            running(2.0, seconds = 200, climbMeters = 20.0, gps = true)
        }

        // Forty metres of real climb, less the last bite of it the threshold is still holding.
        // Trusting the wild fix would have banked something nearer three hundred.
        assertEquals(35.0, elevationGainOf(aRun(), track)!!, 5.5)
    }

    @Test
    fun `a gust of wind is not a hill`() {
        // A one-second pressure spike worth eight metres, on otherwise flat ground. It is past the
        // barometer's two-metre threshold, so without the median it would bank permanently.
        val track = script {
            running(2.0, seconds = 300, barometer = true)
            gustOfWind(metersWorth = 8.0)
            running(2.0, seconds = 300, barometer = true)
        }

        assertEquals(0.0, elevationGainOf(aRun(), track)!!, 1.0)
    }

    @Test
    fun `a barometer that had not reported yet does not demote the run`() {
        // The pressure reader starts empty, so the first fixes of a run can land before its first
        // sensor event. Read as "not a barometer phone", the whole run would fall to the GPS tier's
        // ten-metre threshold and lose most of a rolling route.
        val track = script {
            running(2.0, seconds = 5, barometer = false)
            running(2.0, seconds = 500, climbMeters = 30.0, barometer = true)
        }

        assertEquals(30.0, elevationGainOf(aRun(), track)!!, 3.0)
    }

    @Test
    fun `a flat run that wobbles reports no climb`() {
        // GPS altitude jittering a few metres either side of flat: below the ten-metre threshold, so
        // none of it is banked. Summed raw it would read as hundreds of metres of climbing.
        val track = script { wobblingGps(seconds = 600, amplitudeMeters = 4.0) }

        assertEquals(0.0, elevationGainOf(aRun(), track)!!, 0.001)
    }

    @Test
    fun `a run with no recorded height shows no elevation at all`() {
        // Backfilled breadcrumbs: a position and nothing else.
        val track = script { running(2.0, seconds = 1000) }

        assertNull(elevationGainOf(aRun(), track))
        assertTrue(splitsOfRun(aRun(), noSamples, track).all { it.elevationGainMeters == null })
    }

    @Test
    fun `a treadmill run has no elevation`() {
        assertNull(
            elevationGainOf(
                aRun(runMode = "treadmill"),
                script { running(2.0, seconds = 500, climbMeters = 50.0, barometer = true) }
            )
        )
    }

    @Test
    fun `climbing across a gap in the recording is not banked`() {
        // The runner was driven up a hill while the signal was lost. Nothing witnessed the climb,
        // so nothing is credited for it.
        val track = script {
            running(2.0, seconds = 300, climbMeters = 0.0, barometer = true)
            gap(meters = 600.0, seconds = 120, climbMeters = 200.0, barometer = true)
            running(2.0, seconds = 300, climbMeters = 0.0, barometer = true)
        }

        assertEquals(0.0, elevationGainOf(aRun(), track)!!, 1.0)
    }

    @Test
    fun `the page gets its splits and its climb from the one analysis`() {
        val track = script { running(2.0, seconds = 1000, climbMeters = 40.0, barometer = true) }
        val analysis = RunAnalysis.of(aRun(), noSamples, track)

        assertEquals(2, analysis.splits.size)
        assertEquals(40.0, analysis.elevationGainMeters!!, 2.0)
    }

    @Test
    fun `a run whose track has not loaded yet is analysed without one`() {
        // The detail page draws before the route arrives from the database, and the chart must not
        // wait on it - the splits table and the elevation line are simply absent for that moment.
        val analysis = RunAnalysis.of(aRun(), noSamples)

        assertTrue(analysis.splits.isEmpty())
        assertNull(analysis.elevationGainMeters)
    }

    // -- Scripting a run ---------------------------------------------------------------------

    private val noSamples = emptyList<HrSample>()

    /**
     * Asked the way the page asks it, so every test runs the production path rather than a seam kept
     * alive for tests. [RunAnalysis.of] is the whole of what the detail page calls.
     */
    private fun splitsOfRun(run: RunnerSession, samples: List<HrSample>, track: List<TrackPoint>) =
        RunAnalysis.of(run, samples, track).splits

    private fun elevationGainOf(run: RunnerSession, track: List<TrackPoint>) =
        RunAnalysis.of(run, noSamples, track).elevationGainMeters

    private fun aRun(runMode: String = "outdoor") = RunnerSession(
        id = 1,
        startTime = 1_700_000_000_000L,
        endTime = 1_700_000_600_000L,
        durationSeconds = 600,
        runMode = runMode,
    )

    private fun script(build: RunScript.() -> Unit): List<TrackPoint> =
        RunScript().apply(build).points

    /**
     * A run laid out due north from a fixed start, one fix per second unless told otherwise. Heights
     * are supplied either as a barometer pressure or as a GPS altitude, never both, so each script
     * picks the tier it is testing.
     */
    private class RunScript {
        val points = mutableListOf<TrackPoint>()
        private var latitude = 50.7900
        private var timestamp = 1_700_000_000_000L
        private var height = 100.0

        /**
         * Moves the run [meters] due north, measured with the app's own distance function rather
         * than a metres-per-degree constant. A constant is out by about a metre per kilometre at
         * this latitude, which is enough to grow a run that should finish on the kilometre a
         * three-metre split of its own — and the splits under test are cut at exactly that boundary.
         */
        private fun advance(meters: Double) {
            if (meters == 0.0) return
            val metersPerDegree = geodesicDistanceMeters(latitude, LONGITUDE, latitude + 0.001, LONGITUDE) * 1_000.0
            latitude += meters / metersPerDegree
        }

        private fun add(
            startsAfterPause: Boolean = false,
            barometer: Boolean = false,
            gps: Boolean = false,
            verticalAccuracyMeters: Float = 6f,
            source: String = TrackPointSource.GPS,
        ) {
            points += TrackPoint(
                sessionId = 1,
                latitude = latitude,
                longitude = LONGITUDE,
                altitudeMeters = if (gps) height else null,
                horizontalAccuracyMeters = 5f,
                verticalAccuracyMeters = if (gps) verticalAccuracyMeters else null,
                barometerPressureHpa = if (barometer) pressureAt(height) else null,
                timestampMillis = timestamp,
                source = source,
                startsAfterPause = startsAfterPause,
            )
        }

        /** A stretch held at [speedMps] for [seconds], climbing [climbMeters] evenly over it. */
        fun running(
            speedMps: Double,
            seconds: Int,
            climbMeters: Double = 0.0,
            barometer: Boolean = false,
            gps: Boolean = false,
        ) {
            if (points.isEmpty()) add(barometer = barometer, gps = gps)
            repeat(seconds) {
                advance(speedMps)
                height += climbMeters / seconds
                timestamp += 1_000
                add(barometer = barometer, gps = gps)
            }
        }

        /** Fixes [seconds] apart and [meters] apart — a track backfilled from old breadcrumbs. */
        fun sparse(meters: Double, seconds: Int, fixes: Int) {
            if (points.isEmpty()) add(source = TrackPointSource.BACKFILL)
            repeat(fixes) {
                advance(meters)
                timestamp += seconds * 1_000L
                add(source = TrackPointSource.BACKFILL)
            }
        }

        /** One fix that reports a height wildly out, and admits it with a poor vertical accuracy. */
        fun wildGpsHeight(offsetMeters: Double) {
            advance(2.0)
            timestamp += 1_000
            height += offsetMeters
            add(gps = true, verticalAccuracyMeters = 60f)
            height -= offsetMeters
        }

        /** One second of pressure that is weather rather than height, on an otherwise steady run. */
        fun gustOfWind(metersWorth: Double) {
            advance(2.0)
            timestamp += 1_000
            height += metersWorth
            add(barometer = true)
            height -= metersWorth
        }

        /** The runner pauses, covers [meters] over [seconds] unrecorded, and resumes. */
        fun pauseAndMoveOn(meters: Double, seconds: Int) {
            advance(meters)
            timestamp += seconds * 1_000L
            add(startsAfterPause = true)
        }

        /** The signal is lost for [seconds], over [meters] of ground nothing recorded. */
        fun gap(
            meters: Double,
            seconds: Int,
            climbMeters: Double = 0.0,
            barometer: Boolean = false,
            gps: Boolean = false,
        ) {
            advance(meters)
            height += climbMeters
            timestamp += seconds * 1_000L
            add(barometer = barometer, gps = gps)
        }

        /** Flat ground, with GPS altitude bouncing [amplitudeMeters] either side of it each second. */
        fun wobblingGps(seconds: Int, amplitudeMeters: Double) {
            val flat = height
            if (points.isEmpty()) add(gps = true)
            repeat(seconds) { second ->
                advance(2.0)
                height = flat + if (second % 2 == 0) amplitudeMeters else -amplitudeMeters
                timestamp += 1_000
                add(gps = true)
            }
        }

        private companion object {
            const val LONGITUDE = 0.2200

            /** The standard atmosphere read backwards: the pressure a barometer sees at a height. */
            fun pressureAt(heightMeters: Double): Float =
                (1013.25 * Math.pow(1.0 - heightMeters / 44_330.0, 5.255)).toFloat()
        }
    }
}
