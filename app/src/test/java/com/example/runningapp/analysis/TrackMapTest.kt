package com.example.runningapp.analysis

import com.example.runningapp.HrProfile
import com.example.runningapp.HrZone
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Run's route as the detail page's map draws it (#47).
 *
 * Every test scripts a Run the way a runner would describe it — "held Tempo for a while and then
 * pushed", "paused at a crossing", "a treadmill Run" — and asks what the map is allowed to draw.
 *
 * On [profile]: 190 with no resting heart rate stated puts Tempo at 133-151 and Threshold at
 * 152-170, so 140 and 160 are a zone apart and neither sits near an edge.
 */
class TrackMapTest {

    private val profile = HrProfile(maxHr = 190)

    @Test
    fun `a run held in one zone is drawn as one stretch`() {
        val run = aRun()
        val analysis = analyse(run, script { running(speedMps = 3.0, seconds = 10) }, beats(run, 0..10, 140))

        val map = requireNotNull(analysis.trackMap)
        assertEquals(listOf(HrZone.TEMPO), map.stretches.map { it.zone })
        assertEquals(11, map.stretches.single().fixes.size)
    }

    @Test
    fun `pushing into the next zone starts a new stretch, joined to the last`() {
        val run = aRun()
        val analysis = analyse(
            run,
            script { running(speedMps = 3.0, seconds = 10) },
            beats(run, 0..5, 140) + beats(run, 6..10, 160),
        )

        val map = requireNotNull(analysis.trackMap)
        assertEquals(listOf(HrZone.TEMPO, HrZone.THRESHOLD), map.stretches.map { it.zone })
        // The two share the fix the runner crossed the edge on, so the line has no gap in it.
        assertEquals(map.stretches[0].fixes.last(), map.stretches[1].fixes.first())
    }

    @Test
    fun `a run with no heart rate recorded is drawn, in no zone at all`() {
        val analysis = analyse(aRun(), script { running(speedMps = 3.0, seconds = 10) }, noSamples)

        val map = requireNotNull(analysis.trackMap)
        assertEquals(listOf(null), map.stretches.map { it.zone })
    }

    @Test
    fun `a stretch the strap dropped out of is drawn in no zone, between the ones it did record`() {
        val run = aRun()
        val analysis = analyse(
            run,
            script { running(speedMps = 3.0, seconds = 10) },
            beats(run, 0..3, 140) + beats(run, 8..10, 140),
        )

        val map = requireNotNull(analysis.trackMap)
        assertEquals(listOf(HrZone.TEMPO, null, HrZone.TEMPO), map.stretches.map { it.zone })
    }

    @Test
    fun `no stated heart rates leaves the whole route uncoloured`() {
        val run = aRun()
        val track = script { running(speedMps = 3.0, seconds = 10) }
        val samples = beats(run, 0..5, 140) + beats(run, 6..10, 160)

        val uncoloured = requireNotNull(RunAnalysis.of(run, samples, track, profile = null).trackMap)

        assertEquals(listOf(null), uncoloured.stretches.map { it.zone })
        // The same Run with the heart rates stated is the one that has zones to be cut at.
        assertNotEquals(uncoloured.stretches.size, requireNotNull(analyse(run, track, samples).trackMap).stretches.size)
    }

    @Test
    fun `a pause is a break in the line, not a stretch drawn across it`() {
        val run = aRun()
        val track = script {
            running(speedMps = 3.0, seconds = 5)
            pauseAndMoveOn(meters = 100.0, seconds = 60)
            running(speedMps = 3.0, seconds = 5)
        }
        val analysis = analyse(run, track, beats(run, 0..80, 140))

        val map = requireNotNull(analysis.trackMap)
        assertEquals(listOf(HrZone.TEMPO, HrZone.TEMPO), map.stretches.map { it.zone })
        // The fix the runner paused on ends the first line and the one they resumed on starts the
        // second: nothing joins them, because nothing recorded the ground between.
        assertNotEquals(map.stretches[0].fixes.last(), map.stretches[1].fixes.first())
        assertEquals(6, map.stretches[0].fixes.size)
        assertEquals(6, map.stretches[1].fixes.size)
    }

    @Test
    fun `a lost signal is a break in the line too`() {
        val run = aRun()
        val track = script {
            running(speedMps = 3.0, seconds = 5)
            gap(meters = 200.0, seconds = 60)
            running(speedMps = 3.0, seconds = 5)
        }

        val map = requireNotNull(analyse(run, track, beats(run, 0..80, 140)).trackMap)

        assertEquals(2, map.stretches.size)
        assertNotEquals(map.stretches[0].fixes.last(), map.stretches[1].fixes.first())
    }

    @Test
    fun `the start and the finish are the run's own first and last fixes`() {
        val run = aRun()
        val track = script {
            running(speedMps = 3.0, seconds = 5)
            pauseAndMoveOn(meters = 100.0, seconds = 60)
            running(speedMps = 3.0, seconds = 5)
        }

        val map = requireNotNull(analyse(run, track, beats(run, 0..80, 140)).trackMap)

        assertEquals(MapFix(track.first().latitude, track.first().longitude), map.start)
        assertEquals(MapFix(track.last().latitude, track.last().longitude), map.finish)
    }

    @Test
    fun `a treadmill run has no route to draw`() {
        val run = aRun(runMode = "treadmill")

        assertNull(analyse(run, script { running(speedMps = 3.0, seconds = 10) }, beats(run, 0..10, 140)).trackMap)
    }

    @Test
    fun `a run that recorded a single fix has no route to draw`() {
        val run = aRun()
        val track = script { running(speedMps = 3.0, seconds = 10) }.take(1)

        assertNull(analyse(run, track, beats(run, 0..10, 140)).trackMap)
    }

    @Test
    fun `a run with no track at all has no route to draw`() {
        assertNull(analyse(aRun(), emptyList(), noSamples).trackMap)
    }

    @Test
    fun `a sparsely recorded track colours each stretch by every beat it covers`() {
        val run = aRun()
        // Fixes ten seconds apart, as a track backfilled from old breadcrumbs is. The beats between
        // two fixes are what the ground between them was covered at: a single Threshold beat
        // landing on the second fix must not colour the sixty metres before it Threshold.
        val track = script { sparse(meters = 60.0, seconds = 10, fixes = 2) }
        val samples = beats(run, 1..9, 140) + beats(run, 10..10, 160) + beats(run, 11..20, 140)

        val map = requireNotNull(analyse(run, track, samples).trackMap)

        assertEquals(listOf(HrZone.TEMPO), map.stretches.map { it.zone })
    }

    @Test
    fun `every stretch is a line, never a lone point`() {
        val run = aRun()
        val track = script {
            running(speedMps = 3.0, seconds = 2)
            pauseAndMoveOn(meters = 50.0, seconds = 60)
            pauseAndMoveOn(meters = 50.0, seconds = 60)
            running(speedMps = 3.0, seconds = 2)
        }

        val map = requireNotNull(analyse(run, track, beats(run, 0..130, 140)).trackMap)

        assertTrue(map.stretches.all { it.fixes.size >= 2 })
    }

    private fun analyse(run: RunnerSession, track: List<com.example.runningapp.data.TrackPoint>, samples: List<HrSample>) =
        RunAnalysis.of(run, samples, track, profile)

    /** One heart rate per second of [seconds], all of them [bpm]. */
    private fun beats(run: RunnerSession, seconds: IntRange, bpm: Int): List<HrSample> =
        seconds.map { aSample(run, it, bpm) }
}
