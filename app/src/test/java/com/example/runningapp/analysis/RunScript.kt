package com.example.runningapp.analysis

import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
import com.example.runningapp.recording.geodesicDistanceMeters
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Scripting a run, for the tests of everything the detail page works out from one.
 *
 * A test lays a run out as the sequence of things that happened to it — a speed held for a number of
 * seconds, a hill, a pause, a lost signal, a barometer wobbling the way Chris's phone actually
 * wobbles — and then asks the module under test what it makes of it. Shared, because the splits
 * table (#45) and the combined chart (#46) read the same run the same way and a run scripted twice
 * would drift.
 */

/** A run with no heart rate recorded — the only thing many of these tests have to say about it. */
internal val noSamples = emptyList<HrSample>()

internal fun aRun(runMode: String = "outdoor") = RunnerSession(
    id = 1,
    startTime = 1_700_000_000_000L,
    endTime = 1_700_000_600_000L,
    durationSeconds = 600,
    runMode = runMode,
)

internal fun script(build: RunScript.() -> Unit): List<TrackPoint> = RunScript().apply(build).points

/** One heart rate, banked at [second] of a run that started when [aRun] says it did. */
internal fun aSample(run: RunnerSession, second: Int, bpm: Int) = HrSample(
    sessionId = 1,
    elapsedSeconds = second.toLong(),
    rawBpm = bpm,
    smoothedBpm = 0,
    connectionState = "CONNECTED",
    timestampMillis = run.startTime + second * 1_000L,
)

internal class RunScript {
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
        verticalAccuracyMeters: Float? = 6f,
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

    /**
     * One fix landing [offsetMeters] off the route, with the next one back on it — a GPS glitch
     * under trees or beside a building, which reads as a sprint out and a sprint back.
     */
    fun oneWildFix(offsetMeters: Double) {
        advance(offsetMeters)
        timestamp += 1_000
        add()
        advance(-offsetMeters)
        timestamp += 1_000
        add()
    }

    /** One fix that reports a height wildly out, and admits it with a poor vertical accuracy. */
    fun wildGpsHeight(offsetMeters: Double) {
        advance(2.0)
        timestamp += 1_000
        height += offsetMeters
        add(gps = true, verticalAccuracyMeters = 60f)
        height -= offsetMeters
    }

    /** One fix the phone gave a height for without saying how wrong it might be. */
    fun fixWithoutVerticalAccuracy() {
        advance(2.0)
        timestamp += 1_000
        add(gps = true, verticalAccuracyMeters = null)
    }

    /**
     * The opening [seconds] of a run before the sky is properly acquired: fixes reporting a
     * height [offsetMeters] out and admitting it with a poor vertical accuracy.
     */
    fun unacquiredGpsStart(seconds: Int, offsetMeters: Double) {
        height += offsetMeters
        if (points.isEmpty()) add(gps = true, verticalAccuracyMeters = 60f)
        repeat(seconds) {
            advance(2.0)
            timestamp += 1_000
            add(gps = true, verticalAccuracyMeters = 60f)
        }
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
    fun pauseAndMoveOn(
        meters: Double,
        seconds: Int,
        climbMeters: Double = 0.0,
        barometer: Boolean = false,
        gps: Boolean = false,
    ) {
        advance(meters)
        height += climbMeters
        timestamp += seconds * 1_000L
        add(startsAfterPause = true, barometer = barometer, gps = gps)
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

    /**
     * Flat ground, with the barometer as noisy as Chris's Pixel 8a actually records it (#45).
     *
     * The shape of the jitter is taken from three of his runs: mostly under a metre from one
     * second to the next, with a heavy tail that reaches several metres often enough to matter —
     * a median step of about 0.9 m, about 5.6 m at the 95th percentile. That tail is the whole
     * problem, because it clears the barometer's two-metre threshold on its own.
     *
     * Seeded, so the run is the same every time this test is run.
     *
     * [climbMeters] is real ground underneath the noise, climbed evenly across the stretch.
     */
    fun wobblingBarometer(seconds: Int, climbMeters: Double = 0.0, seed: Int = 45) {
        val noise = Random(seed)
        val flat = height
        if (points.isEmpty()) add(barometer = true)
        repeat(seconds) { second ->
            advance(2.0)
            timestamp += 1_000
            val ground = flat + climbMeters * (second + 1) / seconds
            // A calm second and a gusty one, mixed the way the recorded runs mix them.
            val spread = if (noise.nextInt(10) == 0) 3.0 else 0.7
            height = ground + noise.nextGaussian(spread)
            add(barometer = true)
            height = ground
        }
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

        /** A normally distributed draw of standard deviation [sd] — Box-Muller, so no platform
         * random-number generator's bell curve has to be the same across JDKs for a test to hold. */
        fun Random.nextGaussian(sd: Double): Double =
            sd * sqrt(-2.0 * ln(1.0 - nextDouble())) * cos(2.0 * Math.PI * nextDouble())
    }
}
