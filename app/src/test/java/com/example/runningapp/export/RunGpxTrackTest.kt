package com.example.runningapp.export

import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunGpxTrackTest {

    private val startTime = 1_753_500_000_000L // 2025-07-26T03:20:00Z
    private val utc = ZoneId.of("UTC")

    private fun session(id: Long = 1L, start: Long = startTime) = RunnerSession(
        id = id,
        startTime = start,
        endTime = start + 600_000,
        durationSeconds = 600,
        avgBpm = 130,
        maxBpm = 150,
        targetZone = 2
    )

    private fun point(offsetSeconds: Long, lat: Double = 51.5, lon: Double = -0.1, altitude: Double? = 10.0) =
        TrackPoint(
            sessionId = 1L,
            latitude = lat,
            longitude = lon,
            altitudeMeters = altitude,
            horizontalAccuracyMeters = 5f,
            timestampMillis = startTime + offsetSeconds * 1000,
            source = TrackPointSource.GPS
        )

    /** A sample as the app writes them today: running seconds *and* the wall clock it was banked at. */
    private fun sample(elapsedSeconds: Long, rawBpm: Int, atOffsetSeconds: Long = elapsedSeconds) = HrSample(
        sessionId = 1L,
        elapsedSeconds = elapsedSeconds,
        rawBpm = rawBpm,
        // Deliberately unlike rawBpm: the export must carry what the strap measured, not the
        // coach's smoothed number.
        smoothedBpm = rawBpm - 20,
        connectionState = "Connected",
        timestampMillis = startTime + atOffsetSeconds * 1000
    )

    /** A sample from before the v16 timestamp column, where only running seconds were recorded. */
    private fun legacySample(elapsedSeconds: Long, rawBpm: Int) = HrSample(
        sessionId = 1L,
        elapsedSeconds = elapsedSeconds,
        rawBpm = rawBpm,
        smoothedBpm = rawBpm - 20,
        connectionState = "Connected",
        timestampMillis = null
    )

    @Test
    fun `carries position, elevation and timestamp of every track point`() {
        val track = RunGpxTrack.build(
            session = session(),
            trackPoints = listOf(point(0, lat = 51.5074, lon = -0.1278), point(1, lat = 51.5075, lon = -0.1277)),
            hrSamples = emptyList(),
            zoneId = utc
        )

        assertEquals(2, track.points.size)
        assertEquals(51.5074, track.points[0].latitude, 0.0)
        assertEquals(-0.1278, track.points[0].longitude, 0.0)
        assertEquals(10.0, track.points[0].elevationMeters!!, 0.0)
        assertEquals(startTime, track.points[0].timeMillis)
        assertEquals(startTime + 1000, track.points[1].timeMillis)
        assertEquals(startTime, track.startTimeMillis)
    }

    @Test
    fun `matches the heart rate sample recorded at the same second`() {
        val track = RunGpxTrack.build(
            session = session(),
            trackPoints = listOf(point(0), point(1), point(2)),
            hrSamples = listOf(sample(0, 120), sample(1, 122), sample(2, 125)),
            zoneId = utc
        )

        assertEquals(listOf(120, 122, 125), track.points.map { it.heartRateBpm })
    }

    @Test
    fun `falls back to the nearest sample within the tolerance when a second is missing`() {
        // The strap drops out for a couple of seconds mid-run: HrSample rows only exist when BPM > 0.
        val track = RunGpxTrack.build(
            session = session(),
            trackPoints = listOf(point(0), point(1), point(2)),
            hrSamples = listOf(sample(0, 120), sample(2, 126)),
            zoneId = utc
        )

        assertEquals(listOf(120, 120, 126), track.points.map { it.heartRateBpm })
    }

    @Test
    fun `covers a drop-out from both ends but leaves the middle of a longer one empty`() {
        // The strap goes quiet between seconds 0 and 11. Points within five seconds of either real
        // reading keep one; the second in the middle, six seconds from both, keeps none.
        val track = RunGpxTrack.build(
            session = session(),
            trackPoints = listOf(point(5), point(6), point(20), point(26)),
            hrSamples = listOf(sample(0, 120), sample(11, 130), sample(20, 140)),
            zoneId = utc
        )

        assertEquals(listOf(120, 130, 140, null), track.points.map { it.heartRateBpm })
    }

    @Test
    fun `leaves heart rate out when the nearest sample is too far away`() {
        val track = RunGpxTrack.build(
            session = session(),
            trackPoints = listOf(point(60)),
            hrSamples = listOf(sample(0, 120)),
            zoneId = utc
        )

        assertNull(track.points.single().heartRateBpm)
    }

    @Test
    fun `keeps heart rate on the points recorded after a pause`() {
        // The runner pauses for 60s at second 1. Running seconds stop counting, so from then on a
        // sample's elapsedSeconds sits a minute behind the wall clock the GPS fixes are stamped with.
        val track = RunGpxTrack.build(
            session = session(),
            trackPoints = listOf(point(0), point(1), point(61), point(62)),
            hrSamples = listOf(
                sample(elapsedSeconds = 0, rawBpm = 120, atOffsetSeconds = 0),
                sample(elapsedSeconds = 1, rawBpm = 121, atOffsetSeconds = 1),
                sample(elapsedSeconds = 2, rawBpm = 118, atOffsetSeconds = 61),
                sample(elapsedSeconds = 3, rawBpm = 124, atOffsetSeconds = 62)
            ),
            zoneId = utc
        )

        assertEquals(listOf(120, 121, 118, 124), track.points.map { it.heartRateBpm })
    }

    @Test
    fun `falls back to elapsed seconds for samples recorded before the timestamp column existed`() {
        val track = RunGpxTrack.build(
            session = session(),
            trackPoints = listOf(point(0), point(1)),
            hrSamples = listOf(legacySample(0, 120), legacySample(1, 122)),
            zoneId = utc
        )

        assertEquals(listOf(120, 122), track.points.map { it.heartRateBpm })
    }

    @Test
    fun `still carries heart rate on a paused run recorded before the timestamp column existed`() {
        // Ran for 600s but spanned 660s of wall clock, so the run paused for a minute somewhere and
        // a legacy sample's elapsed seconds sit up to that much behind the fixes. Deliberately kept:
        // a graph offset by a known, bounded amount beats no graph at all, and only rows written
        // before v16 are affected (#84 review).
        val paused = session().copy(endTime = startTime + 660_000, durationSeconds = 600)

        val track = RunGpxTrack.build(
            session = paused,
            trackPoints = listOf(point(0), point(1)),
            hrSamples = listOf(legacySample(0, 120), legacySample(1, 122)),
            zoneId = utc
        )

        assertEquals(listOf(120, 122), track.points.map { it.heartRateBpm })
    }

    @Test
    fun `breaks the route where the run was paused`() {
        // Fixes stop while the runner is paused and pick up again somewhere else on resume.
        val track = RunGpxTrack.build(
            session = session(),
            trackPoints = listOf(point(0), point(1), point(120), point(121)),
            hrSamples = emptyList(),
            zoneId = utc
        )

        assertEquals(2, track.segments.size)
        assertEquals(listOf(2, 2), track.segments.map { it.points.size })
        assertEquals(4, track.points.size)
    }

    @Test
    fun `keeps a run with no break in it as a single stretch`() {
        val track = RunGpxTrack.build(
            session = session(),
            trackPoints = listOf(point(0), point(1), point(2)),
            hrSamples = emptyList(),
            zoneId = utc
        )

        assertEquals(1, track.segments.size)
    }

    @Test
    fun `leaves elevation out when the fix recorded no altitude`() {
        val track = RunGpxTrack.build(
            session = session(),
            trackPoints = listOf(point(0, altitude = null)),
            hrSamples = emptyList(),
            zoneId = utc
        )

        assertNull(track.points.single().elevationMeters)
    }

    @Test
    fun `orders points by time even when the rows arrive unsorted`() {
        val track = RunGpxTrack.build(
            session = session(),
            trackPoints = listOf(point(2), point(0), point(1)),
            hrSamples = emptyList(),
            zoneId = utc
        )

        assertEquals(listOf(startTime, startTime + 1000, startTime + 2000), track.points.map { it.timeMillis })
    }

    @Test
    fun `names the run after its local start time`() {
        val track = RunGpxTrack.build(
            session = session(),
            trackPoints = listOf(point(0)),
            hrSamples = emptyList(),
            zoneId = utc
        )

        assertEquals("Run 26 Jul 2025, 03:20", track.name)
    }

    @Test
    fun `builds a file name that is safe on every platform`() {
        assertEquals(
            "run-2025-07-26-0320-1.gpx",
            RunGpxTrack.fileName(session(), utc)
        )
    }

    @Test
    fun `two runs started in the same minute get different file names`() {
        assertNotEquals(
            RunGpxTrack.fileName(session(id = 7L, start = startTime), utc),
            RunGpxTrack.fileName(session(id = 8L, start = startTime + 20_000), utc)
        )
    }
}
