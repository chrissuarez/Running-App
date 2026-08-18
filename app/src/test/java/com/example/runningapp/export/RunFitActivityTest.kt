package com.example.runningapp.export

import com.example.runningapp.analysis.RunAnalysis
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
import com.example.runningapp.recording.geodesicDistanceMeters
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recorded run turned into the activity the FIT writer encodes (#218).
 *
 * The question every test here asks is the one the ticket is about: does the file say what the run's
 * own page says? Garmin re-derives everything it is not told, so a number that goes missing here is
 * a number that quietly becomes Garmin's instead of the app's.
 */
class RunFitActivityTest {

    private val startTime = 1_753_500_000_000L // 2025-07-26T03:20:00Z
    private val utc = ZoneId.of("UTC")

    @Test
    fun `the summary is the app's own, not a re-derivation from the fixes`() {
        val run = outdoorRun(distanceKm = 2.4, durationSeconds = 600, movingTimeSeconds = 540)

        val activity = build(run, track(), samples())

        assertEquals(600_000L, activity.elapsedMillis)
        assertEquals(540_000L, activity.movingMillis)
        assertEquals(2400.0, activity.distanceMeters!!, 0.001)
        assertEquals(130, activity.averageBpm)
        assertEquals(150, activity.maxBpm)
        assertEquals(FitSport.RUN, activity.sport)
    }

    @Test
    fun `a run with no moving time of its own falls back to its own clock`() {
        // A Run recorded before #163, or one with no usable track: Duration is the only clock it has,
        // and it is the one the page quotes its pace against.
        val run = outdoorRun(durationSeconds = 600, movingTimeSeconds = null)

        assertEquals(600_000L, build(run, track(), samples()).movingMillis)
    }

    @Test
    fun `the laps are the splits the page shows`() {
        val run = outdoorRun(distanceKm = 2.4, durationSeconds = 1200, movingTimeSeconds = 1200)
        val analysis = RunAnalysis.of(run, samples(), evenTrack(seconds = 1200, speedMps = 2.0))

        val activity = RunFitActivity.build(run, evenTrack(seconds = 1200, speedMps = 2.0), samples(), analysis)

        assertEquals(analysis.splits.size, activity.laps.size)
        activity.laps.forEachIndexed { index, lap ->
            val split = analysis.splits[index]
            assertEquals(split.distanceMeters, lap.distanceMeters!!, 0.001)
            assertEquals(split.movingMillis, lap.movingMillis)
            assertEquals(split.startTimeMillis, lap.startTimeMillis)
            assertEquals(split.endTimeMillis, lap.endTimeMillis)
        }
    }

    @Test
    fun `a run the app cut no splits from is still one lap of itself`() {
        // FIT requires a lap; the app has none for a treadmill Run. One lap of the whole run states
        // what is known without inventing kilometre markers.
        val treadmill = outdoorRun(distanceKm = 5.0, durationSeconds = 1800, movingTimeSeconds = null)
            .copy(runMode = "treadmill")

        val activity = build(treadmill, emptyList(), samples())

        assertEquals(1, activity.laps.size)
        assertEquals(5000.0, activity.laps.single().distanceMeters!!, 0.001)
        assertEquals(1_800_000L, activity.laps.single().movingMillis)
        assertEquals(FitSport.TREADMILL_RUN, activity.sport)
    }

    @Test
    fun `the one lap of a whole run states the run's own heart rate and climb`() {
        // The lap covers exactly the span the summary does, so it states the same two numbers. A
        // lap left blank beside a summary that has them is the deriving this export exists to end.
        val treadmill = outdoorRun(distanceKm = 5.0, durationSeconds = 1800, movingTimeSeconds = null)
            .copy(runMode = "treadmill")

        val activity = build(treadmill, emptyList(), samples())

        val lap = activity.laps.single()
        assertEquals(activity.averageBpm, lap.averageBpm)
        assertEquals(130, lap.averageBpm)
        assertEquals(activity.ascentMeters, lap.ascentMeters)
    }

    @Test
    fun `a Run with neither Strap nor GPS is one lap, no moments, and its own summary`() {
        // The treadmill Run started without the strap on (#329). Nothing was recorded, so there is
        // nothing to put in a record and no split to cut — and the summary the runner stated is
        // still the whole of what the file has to say.
        val strapless = outdoorRun(distanceKm = 5.0, durationSeconds = 1800, movingTimeSeconds = null)
            .copy(runMode = "treadmill", avgBpm = 0, maxBpm = 0)

        val activity = build(strapless, emptyList(), emptyList())

        assertTrue(activity.records.isEmpty())
        assertEquals(1, activity.laps.size)
        assertEquals(5000.0, activity.laps.single().distanceMeters!!, 0.001)
        assertEquals(1_800_000L, activity.elapsedMillis)
        assertEquals(5000.0, activity.distanceMeters!!, 0.001)
        // A heart rate nobody measured is left out rather than written as a zero.
        assertNull(activity.averageBpm)
        assertNull(activity.maxBpm)
    }

    // -- Where the Run's clock stopped -----------------------------------------------------------

    @Test
    fun `a Pause is the stretch between the last fix before it and the fix that resumed`() {
        val resumed = point(60).copy(startsAfterPause = true)
        val trackPoints = listOf(point(0), point(10), resumed, point(61))

        val activity = build(outdoorRun(), trackPoints, samples())

        val pause = activity.pauses.single()
        assertEquals(startTime + 10_000, pause.startTimeMillis)
        assertEquals(startTime + 60_000, pause.endTimeMillis)
    }

    @Test
    fun `a Pause held before the first fix landed runs from the Run's own start`() {
        // PauseMark puts that Pause on the opening fix, which every reader walking consecutive pairs
        // steps over. Nothing precedes it, so its near side is the Run's start.
        val opening = point(45).copy(startsAfterPause = true)
        val trackPoints = listOf(opening, point(46), point(47))

        val activity = build(outdoorRun(), trackPoints, samples())

        val pause = activity.pauses.single()
        assertEquals(startTime, pause.startTimeMillis)
        assertEquals(startTime + 45_000, pause.endTimeMillis)
    }

    @Test
    fun `a gap nobody declared is an Outage, and does not stop the timer`() {
        // An Outage is a leg the Run counted — its seconds are Moving time and its line is distance.
        // A timer stopped for one would contradict the Moving time this same file states.
        val trackPoints = listOf(point(0), point(10), point(600), point(601))

        val activity = build(outdoorRun(), trackPoints, samples())

        assertTrue(activity.pauses.isEmpty())
    }

    // -- What FIT carries and GPX cannot ---------------------------------------------------------

    @Test
    fun `a run with no GPS at all still exports its heart-rate trace`() {
        val treadmill = outdoorRun(distanceKm = 5.0, durationSeconds = 300).copy(runMode = "treadmill")

        val activity = build(treadmill, trackPoints = emptyList(), hrSamples = samples(count = 300))

        assertEquals(300, activity.records.size)
        assertTrue(activity.records.all { it.latitude == null && it.longitude == null })
        assertTrue(activity.records.all { it.heartRateBpm != null })
        // The strap's own clock, in order.
        assertEquals(startTime, activity.records.first().timeMillis)
        assertEquals(startTime + 299_000, activity.records.last().timeMillis)
    }

    @Test
    fun `heart rates recorded where the track has no fix become records of their own`() {
        // The strap kept reporting through a two-minute loss of signal. GPX drops those beats
        // entirely, because a trackpoint needs a position to hang them on.
        val run = outdoorRun(durationSeconds = 300)
        val fixes = listOf(point(0), point(1), point(2))
        val hr = (0L until 300L).map { sample(it, 120 + (it % 10).toInt()) }

        val activity = build(run, fixes, hr)

        assertEquals(300, activity.records.size)
        assertEquals(3, activity.records.count { it.latitude != null })
        assertTrue(activity.records.all { it.heartRateBpm != null })
        assertTrue(activity.records.zipWithNext().all { (a, b) -> a.timeMillis <= b.timeMillis })
    }

    @Test
    fun `a fix takes the nearest heart rate within five seconds and none beyond it`() {
        val run = outdoorRun(durationSeconds = 300)
        val fixes = listOf(point(0), point(20), point(40))
        val hr = listOf(sample(3, 140), sample(14, 150))

        val records = build(run, fixes, hr).records.filter { it.latitude != null }

        assertEquals(140, records[0].heartRateBpm) // three seconds away
        assertNull(records[1].heartRateBpm) // six seconds from the nearest reading
        assertNull(records[2].heartRateBpm)
    }

    @Test
    fun `the raw reading is exported, never the coach's smoothed one`() {
        val run = outdoorRun(durationSeconds = 300)

        val record = build(run, listOf(point(0)), listOf(sample(0, 160))).records.single()

        assertEquals(160, record.heartRateBpm)
    }

    // -- The distance axis -----------------------------------------------------------------------

    @Test
    fun `the Run's distance is stated once, in the summary`() {
        // Not against each fix as well. The summary carries the distance the recorder banked as it
        // ran; anything derived per fix would be the track re-measured on read, and the file would
        // hold two answers to one question (ADR 0017).
        val run = outdoorRun(distanceKm = 2.4, durationSeconds = 1200, movingTimeSeconds = 1200)
        val track = evenTrack(seconds = 1200, speedMps = 2.0)

        val activity = RunFitActivity.build(run, track, samples(), RunAnalysis.of(run, samples(), track))

        assertEquals(2400.0, activity.distanceMeters!!, 0.001)
    }

    @Test
    fun `a treadmill Run nobody stated a distance for says nothing about distance`() {
        // The runner never typed the console's number, which is why the Run's page offers to add
        // one. A stated zero would turn "nobody said how far" into "it went nowhere" — a claim off
        // no page, which ADR 0017 leaves out rather than fills in (#330).
        val unstated = outdoorRun(distanceKm = 0.0, durationSeconds = 1800, movingTimeSeconds = null)
            .copy(runMode = "treadmill")

        val activity = build(unstated, emptyList(), samples())

        assertNull(activity.distanceMeters)
        assertNull(activity.laps.single().distanceMeters)
        // Everything the Run does know is still stated.
        assertEquals(1_800_000L, activity.elapsedMillis)
        assertEquals(130, activity.averageBpm)
    }

    // -- What the run is called ------------------------------------------------------------------

    @Test
    fun `a Run marked a walk is written as a walk`() {
        val walk = outdoorRun(durationSeconds = 600).copy(isWalk = true)

        assertEquals(FitSport.WALK, build(walk, track(), samples()).sport)
    }

    @Test
    fun `the file is named for the evening the runner ran, and ends in fit`() {
        assertEquals("run-2025-07-26-0320-1.fit", RunFitActivity.fileName(outdoorRun(), utc))
    }

    // -- The file's own clock --------------------------------------------------------------------

    @Test
    fun `the activity's clock is wide enough to hold every record`() {
        // A fix banked a moment before the run's own start — the recorder had a location before the
        // clock began — must still sit inside the file's timer.
        val run = outdoorRun(durationSeconds = 600)
        val early = point(0).copy(timestampMillis = startTime - 4_000)

        val activity = build(run, listOf(early, point(10)), samples())

        assertTrue(activity.startTimeMillis <= activity.records.first().timeMillis)
        assertTrue(activity.endTimeMillis >= activity.records.last().timeMillis)
        assertNotNull(activity.records.first().latitude)
    }

    // -- The run these tests are written against --------------------------------------------------

    private fun build(run: RunnerSession, trackPoints: List<TrackPoint>, hrSamples: List<HrSample>) =
        RunFitActivity.build(run, trackPoints, hrSamples, RunAnalysis.of(run, hrSamples, trackPoints))

    private fun outdoorRun(
        distanceKm: Double = 2.4,
        durationSeconds: Long = 600,
        movingTimeSeconds: Long? = 540,
    ) = RunnerSession(
        id = 1L,
        startTime = startTime,
        endTime = startTime + durationSeconds * 1000,
        durationSeconds = durationSeconds,
        avgBpm = 130,
        maxBpm = 150,
        runMode = "outdoor",
        distanceKm = distanceKm,
        movingTimeSeconds = movingTimeSeconds,
    )

    private fun point(offsetSeconds: Long, lat: Double = 51.5, lon: Double = -0.1) = TrackPoint(
        sessionId = 1L,
        latitude = lat,
        longitude = lon,
        altitudeMeters = 10.0,
        horizontalAccuracyMeters = 5f,
        timestampMillis = startTime + offsetSeconds * 1000,
        source = TrackPointSource.GPS,
    )

    private fun track() = listOf(point(0), point(1, lat = 51.5001), point(2, lat = 51.5002))

    /** A fix a second, holding [speedMps] due north — enough ground to be cut into kilometres. */
    private fun evenTrack(seconds: Int, speedMps: Double): List<TrackPoint> {
        var latitude = 51.5
        val metersPerDegree = geodesicDistanceMeters(latitude, -0.1, latitude + 0.001, -0.1) * 1_000.0
        return (0..seconds).map { second ->
            val at = point(second.toLong(), lat = latitude)
            latitude += speedMps / metersPerDegree
            at
        }
    }

    private fun sample(atSecond: Long, rawBpm: Int) = HrSample(
        sessionId = 1L,
        elapsedSeconds = atSecond,
        rawBpm = rawBpm,
        // Deliberately unlike rawBpm: the export must carry what the strap measured, not the
        // coach's smoothed number.
        smoothedBpm = rawBpm - 20,
        connectionState = "Connected",
        timestampMillis = startTime + atSecond * 1000,
    )

    private fun samples(count: Int = 3) = (0L until count).map { sample(it, 130 + (it % 5).toInt()) }
}
