package com.example.runningapp.analysis

import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scripted runs, drawn against the ground they covered (#46). Each test lays out a run with
 * [RunScript] — a speed held for a while, a hill, a pause, a wobbling instrument — and asks what the
 * combined chart makes of it.
 */
class DistanceChartTest {

    // -- Which runs get one ----------------------------------------------------------------------

    @Test
    fun `a treadmill run has no chart to draw against the ground`() {
        // Nothing was measured off a route, so there is no distance axis to offer — the page falls
        // back to heart rate over the run's own clock.
        val chart = chartOf(aRun(runMode = "treadmill"), script { running(3.0, seconds = 600) })

        assertNull(chart)
    }

    @Test
    fun `a run with no track has no chart to draw against the ground`() {
        assertNull(RunAnalysis.of(aRun(), noSamples).distanceChart)
    }

    @Test
    fun `a run that never left the spot has no chart to draw against the ground`() {
        // Every fix at the same place: an axis from zero to zero is nothing to put a finger on.
        val chart = chartOf(aRun(), script { running(0.0, seconds = 120) })

        assertNull(chart)
    }

    // -- The pace line ---------------------------------------------------------------------------

    @Test
    fun `an even run reads its own pace the whole way along`() {
        // 3 m/s is 5:33 /km, held for a kilometre and a half.
        val chart = chartOf(aRun(), script { running(3.0, seconds = 500) })!!

        val paces = chart.traces.single().points.mapNotNull { it.paceMinPerKm }
        assertTrue(paces.isNotEmpty())
        paces.forEach { assertEquals(1000.0 / 3.0 / 60.0, it, 0.05) }
    }

    @Test
    fun `the axis runs from the start of the run to the ground it recorded`() {
        val chart = chartOf(aRun(), script { running(3.0, seconds = 500) })!!

        assertEquals(1500.0, chart.distanceMetersSpan, 2.0)
        assertEquals(0.0, chart.traces.first().points.first().distanceMeters, 0.001)
        assertEquals(chart.distanceMetersSpan, chart.traces.last().points.last().distanceMeters, 0.001)
    }

    @Test
    fun `a single wild fix does not spike the pace line`() {
        // The whole point of smoothing: one fix landing 30 m off the route and the next landing back
        // on it is a GPS glitch, not a sprint and a stop. Read off those two legs alone it is a
        // 0:33 /km second followed by a standstill — the spike this ticket exists to remove.
        //
        // Smoothing spreads the glitch's extra sixty metres over two hundred rather than putting it
        // in one second. It cannot make them go away: they are in the run's own distance too, and a
        // fix wild enough to matter is meant to be turned away by the accuracy gate upstream.
        val chart = chartOf(
            aRun(),
            script {
                running(3.0, seconds = 200)
                oneWildFix(offsetMeters = 30.0)
                running(3.0, seconds = 200)
            }
        )!!

        val evenPace = 1000.0 / 3.0 / 60.0
        val paces = chart.traces.flatMap { it.points }.mapNotNull { it.paceMinPerKm }
        // The sprint is gone: the fastest the line reads anywhere is about 4:09 /km, against the
        // 0:33 /km those two legs say on their own. What is left is the glitch's sixty metres spread
        // over the two hundred around it, and the run's own distance total carries them too.
        assertTrue("the line still reported a ${paces.min()} min/km sprint", paces.min() > 4.0)
        // And the standstill on the way back is gone with it — nothing reads slow either.
        assertTrue("the line reported a ${paces.max()} min/km stop", paces.max() <= evenPace + 0.5)
    }

    @Test
    fun `a change of pace still shows up`() {
        // Smoothing must not flatten the run into one number: a kilometre run and a kilometre walked
        // have to read as two different paces.
        val chart = chartOf(
            aRun(),
            script {
                running(3.0, seconds = 400)
                running(1.2, seconds = 800)
            }
        )!!

        val points = chart.traces.flatMap { it.points }
        val early = points.first { it.distanceMeters > 500 }.paceMinPerKm!!
        val late = points.first { it.distanceMeters > 1500 }.paceMinPerKm!!

        assertEquals(1000.0 / 3.0 / 60.0, early, 0.2)
        assertEquals(1000.0 / 1.2 / 60.0, late, 0.5)
    }

    @Test
    fun `a run that never got going is quoted at no pace at all`() {
        // Every leg under the moving threshold, so the run banked no moving seconds anywhere. Time
        // over no seconds is not a fast pace — it is no pace, and the line is simply absent rather
        // than reporting an infinity.
        val chart = chartOf(aRun(), script { running(0.5, seconds = 400) })!!

        assertTrue(chart.traces.flatMap { it.points }.all { it.paceMinPerKm == null })
    }

    @Test
    fun `standing still occupies no ground, so the line runs straight through it`() {
        // The axis is distance, and a runner who stops covers none of it. Six minutes at a standstill
        // is a single point on this chart, not a flat stretch — which is what makes the line readable
        // as "how fast over this ground" rather than "how fast at this moment".
        val chart = chartOf(
            aRun(),
            script {
                running(3.0, seconds = 200)
                running(0.05, seconds = 400)
                running(3.0, seconds = 200)
            }
        )!!

        val points = chart.traces.flatMap { it.points }
        assertEquals(1, chart.traces.size)
        // The stop banked twenty metres of shuffling and no seconds, which flatters the pace over
        // the ground around it by about that much — the same arithmetic the splits table does (#45).
        points.mapNotNull { it.paceMinPerKm }.forEach { assertEquals(1000.0 / 3.0 / 60.0, it, 0.6) }
    }

    // -- The elevation silhouette ----------------------------------------------------------------

    @Test
    fun `a hill rises across the silhouette`() {
        val chart = chartOf(
            aRun(),
            script { running(3.0, seconds = 400, climbMeters = 40.0, barometer = true) }
        )!!

        val heights = chart.traces.flatMap { it.points }.mapNotNull { it.metersAboveLowestPoint }
        assertEquals(40.0, heights.last() - heights.first(), 4.0)
        // Stated against the run's own low point, never as an altitude: the barometer's zero is the
        // standard atmosphere's sea level, which is about sixty metres out from the real one.
        assertEquals(0.0, heights.min(), 0.001)
        assertTrue(chart.elevationBand!!.floorMeters <= heights.min())
        assertTrue(chart.elevationBand!!.ceilingMeters >= heights.max())
    }

    @Test
    fun `a run that recorded no height has no silhouette`() {
        val chart = chartOf(aRun(), script { running(3.0, seconds = 400) })!!

        assertTrue(chart.traces.flatMap { it.points }.all { it.metersAboveLowestPoint == null })
        assertNull(chart.elevationBand)
    }

    @Test
    fun `flat ground is drawn flat rather than magnified into a hill`() {
        // A run over ground that never moves more than a metre must not have that metre stretched
        // over the full height of the chart.
        val chart = chartOf(
            aRun(),
            script { wobblingBarometer(seconds = 400) }
        )!!

        val band = chart.elevationBand!!.let { it.ceilingMeters - it.floorMeters }
        assertTrue("a flat run's band was only $band m", band >= MINIMUM_ELEVATION_BAND_METERS)
    }

    // -- Heart rate ------------------------------------------------------------------------------

    @Test
    fun `heart rate is read onto the ground it was measured over`() {
        val run = aRun()
        val track = script { running(3.0, seconds = 300) }
        val samples = (0..300).map { aSample(run, second = it, bpm = 120 + it / 10) }

        val chart = chartOf(run, track, samples)!!

        val points = chart.traces.flatMap { it.points }
        assertEquals(120, points.first().bpm)
        assertEquals(150, points.last().bpm)
        assertTrue(chart.bpmFloor <= 120)
        assertTrue(chart.bpmCeiling >= 150)
    }

    @Test
    fun `a run with no heart rate still draws its pace and its ground`() {
        val chart = chartOf(aRun(), script { running(3.0, seconds = 300, barometer = true) })!!

        assertTrue(chart.traces.flatMap { it.points }.all { it.bpm == null })
        assertNotNull(chart.traces.first().points.first().paceMinPerKm)
    }

    // -- Breaks in the recording -----------------------------------------------------------------

    @Test
    fun `a pause cuts the chart in two rather than drawing across it`() {
        // The runner stopped the recording, walked up something, and set off again. Joining the two
        // sides would draw a slope they never ran and a pace over ground nothing witnessed.
        val chart = chartOf(
            aRun(),
            script {
                running(3.0, seconds = 200, barometer = true)
                pauseAndMoveOn(meters = 100.0, seconds = 300, climbMeters = 30.0, barometer = true)
                running(3.0, seconds = 200, barometer = true)
            }
        )!!

        assertEquals(2, chart.traces.size)
        // The unwitnessed ground carries no distance, so the two stretches meet on the axis.
        assertEquals(
            chart.traces.first().points.last().distanceMeters,
            chart.traces.last().points.first().distanceMeters,
            0.001
        )
    }

    @Test
    fun `pace on one side of a break is not measured over the other`() {
        // A fast stretch, a break, then a slow one: the first point after the break must read the
        // slow pace, not an average of the two.
        val chart = chartOf(
            aRun(),
            script {
                running(4.0, seconds = 300)
                pauseAndMoveOn(meters = 0.0, seconds = 300)
                running(1.5, seconds = 300)
            }
        )!!

        val afterTheBreak = chart.traces.last().points
        assertEquals(1000.0 / 1.5 / 60.0, afterTheBreak[1].paceMinPerKm!!, 1.0)
    }

    // -- The readout under the finger ------------------------------------------------------------

    @Test
    fun `the readout says pace, heart rate and height at the distance touched`() {
        val run = aRun()
        val track = script { running(3.0, seconds = 400, climbMeters = 40.0, barometer = true) }
        val samples = (0..400).map { aSample(run, second = it, bpm = 140) }
        val chart = chartOf(run, track, samples)!!

        val reading = chart.readingAt(600.0)!!

        assertEquals(600.0, reading.distanceMeters, 5.0)
        assertEquals(1000.0 / 3.0 / 60.0, reading.paceMinPerKm!!, 0.1)
        assertEquals(140, reading.bpm)
        assertNotNull(reading.metersAboveLowestPoint)
    }

    @Test
    fun `the readout says nothing past either end of the run`() {
        val chart = chartOf(aRun(), script { running(3.0, seconds = 300) })!!

        assertNull(chart.readingAt(-1.0))
        assertNull(chart.readingAt(chart.distanceMetersSpan + 1.0))
    }

    // -- Scales --------------------------------------------------------------------------------

    @Test
    fun `a pace no runner has held rides the edge of the scale rather than setting it`() {
        // Two fixes 300 m apart one second later is a fix landing in the next county, not a sprint.
        // It is still drawn, but it must not squash the run's real pace into the last pixel.
        val chart = chartOf(
            aRun(),
            script {
                running(3.0, seconds = 300)
                oneWildFix(offsetMeters = 300.0)
                running(3.0, seconds = 300)
            }
        )!!

        assertTrue(
            "the scale reached ${chart.paceFastestMinPerKm} min/km",
            chart.paceFastestMinPerKm >= FASTEST_PLAUSIBLE_PACE_MIN_PER_KM
        )
        assertTrue(chart.paceSlowestMinPerKm <= SLOWEST_PLAUSIBLE_PACE_MIN_PER_KM)
        assertTrue(chart.paceSlowestMinPerKm > chart.paceFastestMinPerKm)
    }

    @Test
    fun `a backfilled track's sparse legs each still get a pace`() {
        // Runs recorded before the app kept a track are backfilled from breadcrumbs — fixes fifteen
        // seconds and thirty metres apart rather than one a second (#45). Far fewer points to draw,
        // but every one of them still has to come out with the pace of the ground around it rather
        // than a hole in the line.
        val chart = chartOf(aRun(), script { sparse(meters = 30.0, seconds = 15, fixes = 60) })!!

        val points = chart.traces.single().points
        assertEquals(61, points.size)
        points.forEach { assertEquals(1000.0 / 2.0 / 60.0, it.paceMinPerKm!!, 0.05) }
    }

    @Test
    fun `a window takes both legs it straddles, not only the one behind it`() {
        // Legs longer than half the window — a fix every hundred-and-twenty metres — are the case
        // where the window's two edges could disagree: each edge cuts through a leg rather than
        // falling between two. Both are folded in proportionally, so a change of speed reads at the
        // fix it happened at rather than one fix later.
        //
        // Twelve seconds for a leg and then nineteen, over the same ground: the pace at the junction
        // has to sit between the two, not on the one behind it.
        val chart = chartOf(
            aRun(),
            script {
                sparse(meters = 120.0, seconds = 12, fixes = 5)
                sparse(meters = 120.0, seconds = 19, fixes = 5)
            }
        )!!

        val junction = chart.traces.single().points.single { it.distanceMeters in 599.0..601.0 }
        // 100 m of the leg behind at 12 s per 120 m and 100 m of the leg ahead at 19 s per 120 m.
        val expected = ((12_000.0 + 19_000.0) * (100.0 / 120.0) / 60_000.0) / 0.2
        assertEquals(expected, junction.paceMinPerKm!!, 0.02)
    }

    // -- Which lines the run recorded ------------------------------------------------------------

    @Test
    fun `a strapless run says it has no heart rate to name or scale`() {
        // The flag the heading, the key and the right-hand scale all read: an outdoor run with no
        // Strap is a first-class run (#110), and everything about a red line has to be left off it.
        val chart = chartOf(aRun(), script { running(3.0, seconds = 300, barometer = true) })!!

        assertTrue(chart.hasPace)
        assertTrue(!chart.hasHeartRate)
        assertTrue(chart.hasElevation)
    }

    @Test
    fun `a run with a strap says it has a heart rate`() {
        val run = aRun()
        val chart = chartOf(
            run,
            script { running(3.0, seconds = 300) },
            samples = (0..300).map { aSample(run, second = it, bpm = 140) },
        )!!

        assertTrue(chart.hasHeartRate)
        assertTrue(!chart.hasElevation)
    }

    // -- The distance axis -----------------------------------------------------------------------

    @Test
    fun `a short run is ticked in metres and a long one in kilometres`() {
        // Every run gets a handful of ticks, not three on a jog and forty on a long one.
        listOf(800.0, 2_500.0, 10_000.0, 42_195.0).forEach { span ->
            val ticks = kilometreTicks(span)
            assertTrue("$span m got ${ticks.size} ticks", ticks.size in 3..7)
            assertEquals(0.0, ticks.first(), 0.001)
            assertEquals("the finish went unlabelled on a $span m run", span, ticks.last(), 0.001)
            assertEquals(ticks, ticks.sorted())
        }
    }

    @Test
    fun `the last round tick gives way to the finish rather than crowding it`() {
        // 2.02 km: a tick at 2 km twenty metres from the total would print two labels on top of
        // each other, and the one worth keeping is the run's own distance.
        val ticks = kilometreTicks(2_020.0)

        assertEquals(listOf(0.0, 500.0, 1_000.0, 1_500.0, 2_020.0), ticks)
    }

    @Test
    fun `the finish gives the last round tick a label's width of room`() {
        // Measured on the phone, not guessed: 4.53 km left a tick at 4 km, and the two labels
        // printed as "4.00 km4.53 km" — touching. A label is most of a step wide on its own.
        assertEquals(listOf(0.0, 1_000.0, 2_000.0, 3_000.0, 4_530.0), kilometreTicks(4_530.0))
        // Far enough apart, and both are kept.
        assertEquals(listOf(0.0, 1_000.0, 2_000.0, 3_000.0, 4_000.0, 4_900.0), kilometreTicks(4_900.0))
    }

    @Test
    fun `an ultra gets a handful of ticks like everything else`() {
        // The step sizes go up for ever rather than stopping at ten kilometres: a hundred-kilometre
        // run ticked every ten would print eleven labels across the width the handful is for.
        listOf(60_000.0, 100_000.0, 250_000.0).forEach { span ->
            assertTrue("$span m got ${kilometreTicks(span).size} ticks", kilometreTicks(span).size in 3..7)
        }
    }

    @Test
    fun `a distance is written the way a runner says it`() {
        assertEquals("640 m", formatDistance(640.0))
        assertEquals("1.00 km", formatDistance(1_000.0))
        assertEquals("12.34 km", formatDistance(12_340.0))
    }

    // -- Scripting -------------------------------------------------------------------------------

    private fun chartOf(
        run: RunnerSession,
        track: List<TrackPoint>,
        samples: List<HrSample> = noSamples,
    ) = RunAnalysis.of(run, samples, track).distanceChart
}
