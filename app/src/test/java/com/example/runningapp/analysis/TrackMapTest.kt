package com.example.runningapp.analysis

import com.example.runningapp.HrProfile
import com.example.runningapp.HrZone
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun `an Outage hands over its ground and nothing else`() {
        // The invariant #204 turns on: the leg carries the straight line the runner covered, and
        // still nothing may be drawn, climbed or joined across it. One Run, every reader of the
        // flag whose meaning changed, in one place — because "unrecorded" no longer means "no
        // ground", and the day it starts meaning "no line" too is the day the map lies.
        val run = aRun()
        val track = script {
            running(speedMps = 3.0, seconds = 300, barometer = true)
            gap(meters = 600.0, seconds = 120, climbMeters = 200.0, barometer = true)
            running(speedMps = 3.0, seconds = 300, barometer = true)
        }
        val analysis = analyse(run, track, beats(run, 0..720, 140))

        // The ground changes hands: 900 m either side plus the 600 m of tunnel.
        assertEquals(2_400.0, analysis.splits.sumOf { it.distanceMeters }, 5.0)
        // And nothing else does.
        assertEquals(2, requireNotNull(analysis.trackMap).stretches.size)
        assertEquals(2, requireNotNull(analysis.distanceChart).traces.size)
        assertEquals(0.0, requireNotNull(analysis.elevationGainMeters), 1.0)
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
    fun `the camera is framed on the markers as well as the lines`() {
        val run = aRun()
        // Paused in its opening seconds: the fix the runner set off from is on no drawn line at
        // all, and framing on the lines alone would put the start marker off the edge of the card.
        val track = script {
            pauseAndMoveOn(meters = 300.0, seconds = 60)
            running(speedMps = 3.0, seconds = 5)
        }

        val map = requireNotNull(analyse(run, track, beats(run, 0..80, 140)).trackMap)

        assertTrue(map.framedFixes.containsAll(listOf(map.start, map.finish)))
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

    // -- Where the chart's scrubber puts the dot (#48) --------------------------------------------

    @Test
    fun `a distance along the run reads back the fix the runner was at`() {
        val run = aRun()
        // Three metres a second, so the fifth fix is fifteen metres in.
        val track = script { running(speedMps = 3.0, seconds = 10) }
        val map = requireNotNull(analyse(run, track, beats(run, 0..10, 140)).trackMap)

        // A centimetre either way: the scale is the ground the run walked, measured geodesically,
        // and it is metres of it rather than a count of fixes multiplied out.
        assertEquals(15.0, map.route[5].distanceMeters, 0.01)
        val at = requireNotNull(map.fixAt(map.route[5].distanceMeters))
        assertEquals(track[5].latitude, at.latitude, 1e-9)
        assertEquals(track[5].longitude, at.longitude, 1e-9)
    }

    @Test
    fun `a distance between two fixes reads the nearer of them, the way the chart reads it out`() {
        val run = aRun()
        val track = script { running(speedMps = 3.0, seconds = 10) }
        val analysis = analyse(run, track, beats(run, 0..10, 140))
        val map = requireNotNull(analysis.trackMap)
        val chart = requireNotNull(analysis.distanceChart)

        // A third of the way along the leg from the first fix to the second, so the first is nearer.
        val third = map.route[0].distanceMeters +
            (map.route[1].distanceMeters - map.route[0].distanceMeters) / 3

        assertEquals(track[0].latitude, requireNotNull(map.fixAt(third)).latitude, 1e-9)
        // And it is the same point the chart puts its line and its readout on, rather than a place
        // between two of them that the chart has nothing to say about.
        assertEquals(map.route[0].distanceMeters, requireNotNull(chart.readingAt(third)).distanceMeters, 1e-9)
    }

    @Test
    fun `the two ends of the run are the two ends of the scale`() {
        val run = aRun()
        val track = script { running(speedMps = 3.0, seconds = 10) }
        val map = requireNotNull(analyse(run, track, beats(run, 0..10, 140)).trackMap)
        val total = map.route.last().distanceMeters

        assertEquals(map.start, map.fixAt(0.0))
        assertEquals(map.finish, map.fixAt(total))
        // Past either end there is no ground to point at, so there is no dot rather than a guess.
        assertNull(map.fixAt(-1.0))
        assertNull(map.fixAt(total + 1.0))
    }

    @Test
    fun `a pause covers no distance, so nothing reads back from inside it`() {
        val run = aRun()
        val track = script {
            running(speedMps = 3.0, seconds = 5)
            pauseAndMoveOn(meters = 100.0, seconds = 60)
            running(speedMps = 3.0, seconds = 5)
        }
        val map = requireNotNull(analyse(run, track, beats(run, 0..80, 140)).trackMap)

        // The fix the runner paused on and the fix they resumed on sit at the same metre — the
        // hundred metres they covered unrecorded is not on this scale at all. The one the scrubber
        // points at is the earlier of the two, which is the point the chart reads out there, so the
        // dot and the readout cannot name different halves of the run.
        assertEquals(map.route[5].distanceMeters, map.route[6].distanceMeters, 0.0)
        val at = requireNotNull(map.fixAt(map.route[5].distanceMeters))
        assertEquals(track[5].latitude, at.latitude, 1e-9)
        // And the unrecorded hundred metres take up no room on the scale: the run is thirty metres
        // of ground however far the runner walked in between.
        assertEquals(30.0, map.route.last().distanceMeters, 0.01)
    }

    @Test
    fun `every point the chart can be scrubbed to has ground on the map`() {
        val run = aRun()
        val track = script {
            running(speedMps = 3.0, seconds = 5)
            pauseAndMoveOn(meters = 100.0, seconds = 60)
            running(speedMps = 3.0, seconds = 5)
            gap(meters = 200.0, seconds = 60)
            running(speedMps = 3.0, seconds = 5)
        }
        val analysis = analyse(run, track, beats(run, 0..140, 140))
        val chart = requireNotNull(analysis.distanceChart)
        val map = requireNotNull(analysis.trackMap)

        // The chart and the map count the same metres, so anywhere the runner can put a finger on
        // one, the other has a place to put the dot.
        chart.traces.flatMap { it.points }.forEach { point ->
            assertTrue(
                "no fix at ${point.distanceMeters} m",
                map.fixAt(point.distanceMeters) != null
            )
        }
    }

    @Test
    fun `the dot and the readout name the same point of the run, wherever the finger lands`() {
        val run = aRun()
        val track = script {
            running(speedMps = 3.0, seconds = 5)
            pauseAndMoveOn(meters = 100.0, seconds = 60)
            running(speedMps = 3.0, seconds = 5)
            gap(meters = 200.0, seconds = 60)
            running(speedMps = 3.0, seconds = 5)
        }
        val analysis = analyse(run, track, beats(run, 0..140, 140))
        val chart = requireNotNull(analysis.distanceChart)
        val map = requireNotNull(analysis.trackMap)

        // Dragged across the whole chart a centimetre at a time — every place a finger can be,
        // including inside every leg and on both breaks.
        val steps = (chart.distanceMetersSpan * 100).toInt()
        (0..steps).forEach { step ->
            val meters = chart.distanceMetersSpan * step / steps
            val readout = requireNotNull(chart.readingAt(meters)) { "no readout at $meters m" }
            val at = requireNotNull(map.fixAt(meters)) { "no fix at $meters m" }
            // The dot sits on a fix the chart is reading out, never between two of them and never on
            // a different one. Only the distance can be compared: a break's two fixes are a hundred
            // metres apart on the ground and at the same metre of the run, so the readout — which is
            // three numbers and no place — cannot say which of them the finger is on.
            assertTrue(
                "at $meters m the dot is off the point the chart read out",
                map.route.any { it.fix == at && it.distanceMeters == readout.distanceMeters }
            )
        }
    }

    @Test
    fun `dragging through a pause takes the dot to the far side of it`() {
        val run = aRun()
        val track = script {
            running(speedMps = 3.0, seconds = 5)
            pauseAndMoveOn(meters = 100.0, seconds = 60)
            running(speedMps = 3.0, seconds = 5)
        }
        val map = requireNotNull(analyse(run, track, beats(run, 0..80, 140)).trackMap)
        val pausedAt = map.route[5].distanceMeters

        // The two fixes are at the same metre of the run and a hundred metres apart on the ground.
        // Up to it, the dot is where the runner stopped; past it, where they started again — so the
        // hundred metres they covered unrecorded is crossed in one step rather than run along.
        assertEquals(map.route[5].fix, map.fixAt(pausedAt))
        assertEquals(map.route[6].fix, map.fixAt(pausedAt + 0.01))
    }

    @Test
    fun `the readout crosses a pause on the same step the dot does`() {
        val run = aRun()
        val track = script {
            running(speedMps = 3.0, seconds = 5)
            pauseAndMoveOn(meters = 100.0, seconds = 60)
            running(speedMps = 3.0, seconds = 5)
        }
        val analysis = analyse(run, track, beats(run, 0..80, 140))
        val chart = requireNotNull(analysis.distanceChart)
        val pausedAt = requireNotNull(analysis.trackMap).route[5].distanceMeters

        // A pause leaves two points on the same metre, so "nearest" alone cannot separate them and
        // would hold the readout on the fix the runner stopped at while the dot had already moved to
        // the one they resumed at — three numbers from one half of the run under a dot on the other.
        assertSame(chart.traces[0].points.last(), chart.readingAt(pausedAt))
        assertSame(chart.traces[1].points.first(), chart.readingAt(pausedAt + 0.01))
    }

    private fun analyse(run: RunnerSession, track: List<TrackPoint>, samples: List<HrSample>) =
        RunAnalysis.of(run, samples, track, profile)

    /** One heart rate per second of [seconds], all of them [bpm]. */
    private fun beats(run: RunnerSession, seconds: IntRange, bpm: Int): List<HrSample> =
        seconds.map { aSample(run, it, bpm) }
}
