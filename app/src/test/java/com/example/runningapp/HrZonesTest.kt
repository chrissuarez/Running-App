package com.example.runningapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HrZonesTest {

    private val maxHr = 190

    @Test
    fun `zone names follow the published table`() {
        assertEquals(
            listOf("Endurance", "Moderate", "Tempo", "Threshold", "Anaerobic"),
            HrZone.entries.map { it.zoneName }
        )
        assertEquals(listOf(50, 60, 70, 80, 90), HrZone.entries.map { it.lowerPercentOfMaxHr })
    }

    @Test
    fun `every zone boundary at max hr 190`() {
        assertEquals(HrZone.ENDURANCE, hrZoneOf(113, maxHr))
        assertEquals(HrZone.MODERATE, hrZoneOf(114, maxHr))
        assertEquals(HrZone.MODERATE, hrZoneOf(132, maxHr))
        assertEquals(HrZone.TEMPO, hrZoneOf(133, maxHr))
        assertEquals(HrZone.TEMPO, hrZoneOf(151, maxHr))
        assertEquals(HrZone.THRESHOLD, hrZoneOf(152, maxHr))
        assertEquals(HrZone.THRESHOLD, hrZoneOf(170, maxHr))
        assertEquals(HrZone.ANAEROBIC, hrZoneOf(171, maxHr))
    }

    @Test
    fun `hr below 50 percent of max hr counts as zone 1`() {
        assertEquals(HrZone.ENDURANCE, hrZoneOf(95, maxHr)) // exactly 50%
        assertEquals(HrZone.ENDURANCE, hrZoneOf(94, maxHr))
        assertEquals(HrZone.ENDURANCE, hrZoneOf(40, maxHr))
        assertEquals(HrZone.ENDURANCE, hrZoneOf(1, maxHr))
    }

    @Test
    fun `above max hr stays in zone 5`() {
        assertEquals(HrZone.ANAEROBIC, hrZoneOf(190, maxHr))
        assertEquals(HrZone.ANAEROBIC, hrZoneOf(240, maxHr))
    }

    @Test
    fun `no heart rate is not a zone`() {
        assertNull(hrZoneOf(0, maxHr))
        assertNull(hrZoneOf(-5, maxHr))
    }

    @Test
    fun `the old zone 3 bug case is classified by percentage alone`() {
        // The shipped "Derive Defaults" button set zone2High to 143, so the hand-typed Zone 2
        // band swallowed 133-143 — every one of those seconds was filed as Zone 2 rather than
        // Tempo, and Zone 3 was left a 9-BPM sliver. Zones no longer read a typed band at all.
        assertEquals(HrZone.TEMPO, hrZoneOf(140, maxHr)) // 73.7% — was Zone 2
        assertEquals(HrZone.TEMPO, hrZoneOf(143, maxHr)) // 75.3% — was Zone 2
        assertEquals(HrZone.THRESHOLD, hrZoneOf(156, maxHr)) // 82.1% — Threshold per the table
    }

    @Test
    fun `zone 3 is reachable at every possible max hr`() {
        for (candidate in -50..400) {
            val low = zoneLowerBpm(HrZone.TEMPO, candidate)
            val high = zoneUpperBpm(HrZone.TEMPO, candidate)
            assertTrue("Zone 3 collapsed at maxHr=$candidate ($low..$high)", high >= low)
            assertEquals(
                "maxHr=$candidate",
                HrZone.TEMPO,
                hrZoneOf(low, candidate)
            )
            assertEquals(
                "maxHr=$candidate",
                HrZone.TEMPO,
                hrZoneOf(high, candidate)
            )
        }
    }

    @Test
    fun `every zone is reachable at every possible max hr`() {
        for (candidate in -50..400) {
            for (zone in HrZone.entries) {
                val low = zoneLowerBpm(zone, candidate)
                assertEquals("maxHr=$candidate zone=$zone", zone, hrZoneOf(low, candidate))
            }
        }
    }

    @Test
    fun `zones tile the bpm range with no gaps`() {
        var previous: HrZone? = null
        for (bpm in 1..250) {
            val zone = hrZoneOf(bpm, maxHr)!!
            if (previous != null) {
                assertTrue(
                    "Zone went backwards or skipped at $bpm",
                    zone.number == previous.number || zone.number == previous.number + 1
                )
            }
            previous = zone
        }
        assertEquals(HrZone.ANAEROBIC, previous)
    }

    @Test
    fun `zone edges are computed from clamped max hr`() {
        assertEquals(zoneLowerBpm(HrZone.TEMPO, MIN_MAX_HR), zoneLowerBpm(HrZone.TEMPO, 20))
        assertEquals(zoneLowerBpm(HrZone.TEMPO, MAX_MAX_HR), zoneLowerBpm(HrZone.TEMPO, 900))
    }

    @Test
    fun `band compares the current zone to the target zone`() {
        assertEquals(ZoneBand.BELOW, zoneBandOf(100, maxHr, HrZone.MODERATE))
        assertEquals(ZoneBand.IN, zoneBandOf(120, maxHr, HrZone.MODERATE))
        assertEquals(ZoneBand.ABOVE, zoneBandOf(140, maxHr, HrZone.MODERATE))
        assertEquals(ZoneBand.UNKNOWN, zoneBandOf(0, maxHr, HrZone.MODERATE))

        // Same BPM, different target.
        assertEquals(ZoneBand.BELOW, zoneBandOf(140, maxHr, HrZone.THRESHOLD))
        assertEquals(ZoneBand.IN, zoneBandOf(140, maxHr, HrZone.TEMPO))
    }

    @Test
    fun `every target has an outside, so high-HR cues stay reachable`() {
        // The high-HR branch — the safety override included — only fires on ABOVE. A target with
        // no ABOVE silences it. Zone 5 is the case that matters: its chart slice is open-ended.
        assertEquals(ZoneBand.ABOVE, zoneBandOf(maxHr + 1, maxHr, HrZone.ANAEROBIC))
        assertEquals(ZoneBand.ABOVE, zoneBandOf(maxHr + 15, maxHr, HrZone.ANAEROBIC))
        assertEquals(ZoneBand.IN, zoneBandOf(maxHr, maxHr, HrZone.ANAEROBIC))

        // Zone 1 is the mirror: its slice swallows everything below 50%, the band must not.
        assertEquals(ZoneBand.BELOW, zoneBandOf(40, maxHr, HrZone.ENDURANCE))
        assertEquals(ZoneBand.IN, zoneBandOf(100, maxHr, HrZone.ENDURANCE))
    }

    @Test
    fun `no max hr leaves any target trapped in a single band`() {
        for (candidate in -50..400) {
            for (target in HrZone.entries) {
                val bands = (1..500).map { zoneBandOf(it, candidate, target) }.toSet()
                assertTrue(
                    "maxHr=$candidate target=$target never reads ABOVE",
                    bands.contains(ZoneBand.ABOVE)
                )
                assertTrue(
                    "maxHr=$candidate target=$target never reads IN",
                    bands.contains(ZoneBand.IN)
                )
            }
        }
    }

    @Test
    fun `range labels read the way the table does`() {
        assertEquals("114-132", targetRangeLabel(HrZone.MODERATE, maxHr))
        assertEquals("133-151", targetRangeLabel(HrZone.TEMPO, maxHr))
        assertEquals("152-170", targetRangeLabel(HrZone.THRESHOLD, maxHr))
    }

    @Test
    fun `target ranges are closed, so they never advertise BPM outside the band`() {
        // Zones 2-4 read the same either way; only the open-ended ends differ.
        assertEquals("114-132", targetRangeLabel(HrZone.MODERATE, maxHr))
        assertEquals("95-113", targetRangeLabel(HrZone.ENDURANCE, maxHr))
        assertEquals("171-190", targetRangeLabel(HrZone.ANAEROBIC, maxHr))

        // Every edge a target range shows must read IN, and stepping outside must not.
        for (target in HrZone.entries) {
            val low = zoneLowerBpm(target, maxHr)
            val high = zoneUpperBpm(target, maxHr)
            assertEquals(ZoneBand.IN, zoneBandOf(low, maxHr, target))
            assertEquals(ZoneBand.IN, zoneBandOf(high, maxHr, target))
            assertEquals(ZoneBand.BELOW, zoneBandOf(low - 1, maxHr, target))
            assertEquals(ZoneBand.ABOVE, zoneBandOf(high + 1, maxHr, target))
        }
    }

    @Test
    fun `hysteresis holds you out until you reach the midpoint`() {
        // Target Tempo at maxHr 190: low 133, high 151, midpoint 142.
        // Coming from ABOVE, you stay ABOVE until the heart rate drops to the midpoint.
        assertEquals(ZoneBand.ABOVE, bandWithHysteresis(ZoneBand.ABOVE, 150, maxHr, HrZone.TEMPO))
        assertEquals(ZoneBand.ABOVE, bandWithHysteresis(ZoneBand.ABOVE, 143, maxHr, HrZone.TEMPO))
        assertEquals(ZoneBand.IN, bandWithHysteresis(ZoneBand.ABOVE, 142, maxHr, HrZone.TEMPO))
        // Coming from BELOW, you stay BELOW until you climb to the midpoint.
        assertEquals(ZoneBand.BELOW, bandWithHysteresis(ZoneBand.BELOW, 134, maxHr, HrZone.TEMPO))
        assertEquals(ZoneBand.IN, bandWithHysteresis(ZoneBand.BELOW, 142, maxHr, HrZone.TEMPO))
    }

    @Test
    fun `an overshoot to the far side of the zone is out, not a false recovery`() {
        // Falling from ABOVE clean through the zone to below the lower edge is BELOW, not IN — the
        // runner is still out of target, so no "recovered" cue and no ladder reset.
        assertEquals(ZoneBand.BELOW, bandWithHysteresis(ZoneBand.ABOVE, 120, maxHr, HrZone.TEMPO))
        // The mirror: climbing from BELOW clean past the upper edge is ABOVE, not IN.
        assertEquals(ZoneBand.ABOVE, bandWithHysteresis(ZoneBand.BELOW, 160, maxHr, HrZone.TEMPO))
    }

    @Test
    fun `with no prior out-of-zone state hysteresis is just the plain band`() {
        assertEquals(ZoneBand.IN, bandWithHysteresis(ZoneBand.IN, 140, maxHr, HrZone.TEMPO))
        assertEquals(ZoneBand.IN, bandWithHysteresis(ZoneBand.UNKNOWN, 140, maxHr, HrZone.TEMPO))
        assertEquals(ZoneBand.ABOVE, bandWithHysteresis(ZoneBand.IN, 160, maxHr, HrZone.TEMPO))
        assertEquals(ZoneBand.BELOW, bandWithHysteresis(ZoneBand.IN, 120, maxHr, HrZone.TEMPO))
        assertEquals(ZoneBand.UNKNOWN, bandWithHysteresis(ZoneBand.ABOVE, 0, maxHr, HrZone.TEMPO))
    }

    @Test
    fun `zone numbers map back to zones`() {
        assertEquals(HrZone.ENDURANCE, HrZone.ofNumber(1))
        assertEquals(HrZone.ANAEROBIC, HrZone.ofNumber(5))
        assertNull(HrZone.ofNumber(0))
        assertNull(HrZone.ofNumber(9))
    }

    @Test
    fun `stored zone numbers fall back to the default target`() {
        assertEquals(HrZone.TEMPO, HrZone.ofNumberOrDefault(3))
        assertEquals(HrZone.DEFAULT_TARGET, HrZone.ofNumberOrDefault(0))
        assertEquals(HrZone.DEFAULT_TARGET, HrZone.ofNumberOrDefault(9))
        assertEquals(2, HrZone.DEFAULT_TARGET.number)
    }

    @Test
    fun `default settings target the moderate zone`() {
        assertEquals(HrZone.MODERATE, UserSettings().targetHrZone)
        assertEquals(2, UserSettings().targetZone)
    }

    @Test
    fun `coaching targets are the interior zones only`() {
        assertEquals(listOf(HrZone.MODERATE, HrZone.TEMPO, HrZone.THRESHOLD), HrZone.COACHING_TARGETS)
    }

    @Test
    fun `for every coaching target, the chart bucket and the band agree`() {
        // This is why the target is restricted (#117): "In Target" is derived on read as the
        // target zone's own seconds, which is only exact where bucket and band coincide.
        for (maxHr in MIN_MAX_HR..MAX_MAX_HR) {
            for (target in HrZone.COACHING_TARGETS) {
                for (bpm in 1..300) {
                    val inBucket = hrZoneOf(bpm, maxHr) == target
                    val inBand = zoneBandOf(bpm, maxHr, target) == ZoneBand.IN
                    assertEquals("maxHr=$maxHr target=$target bpm=$bpm", inBucket, inBand)
                }
            }
        }
    }

    @Test
    fun `the excluded edge zones are excluded because bucket and band diverge`() {
        // Zone 5's bucket is open-ended upward and Zone 1's swallows everything beneath it, but a
        // target must have an outside — so above Max HR and far below Zone 1 the two disagree.
        assertEquals(HrZone.ANAEROBIC, hrZoneOf(205, maxHr))
        assertEquals(ZoneBand.ABOVE, zoneBandOf(205, maxHr, HrZone.ANAEROBIC))

        assertEquals(HrZone.ENDURANCE, hrZoneOf(60, maxHr))
        assertEquals(ZoneBand.BELOW, zoneBandOf(60, maxHr, HrZone.ENDURANCE))
    }

    @Test
    fun `a stored edge-zone target lands on the nearest coaching target`() {
        assertEquals(HrZone.MODERATE, HrZone.coachingTargetOfNumberOrDefault(1))
        assertEquals(HrZone.THRESHOLD, HrZone.coachingTargetOfNumberOrDefault(5))
        assertEquals(HrZone.TEMPO, HrZone.coachingTargetOfNumberOrDefault(3))
        assertEquals(HrZone.DEFAULT_TARGET, HrZone.coachingTargetOfNumberOrDefault(null))
        assertEquals(HrZone.DEFAULT_TARGET, HrZone.coachingTargetOfNumberOrDefault(9))
    }

    @Test
    fun `a typed max hr is accepted only inside the settable range`() {
        assertEquals(100, parseMaxHr("100"))
        assertEquals(190, parseMaxHr("190"))
        assertEquals(230, parseMaxHr("230"))
        assertEquals(185, parseMaxHr(" 185 "))
    }

    @Test
    fun `a typed max hr outside the range is refused rather than clamped`() {
        // effectiveMaxHr clamps because storage must never hold an unusable number; typing is the
        // opposite case — a refusal the runner can see beats a number they did not choose.
        assertNull(parseMaxHr("99"))
        assertNull(parseMaxHr("231"))
        assertNull(parseMaxHr("0"))
        assertNull(parseMaxHr("-190"))
    }

    @Test
    fun `a max hr that is not a whole number is refused`() {
        assertNull(parseMaxHr(""))
        assertNull(parseMaxHr("   "))
        assertNull(parseMaxHr("abc"))
        assertNull(parseMaxHr("19"))    // mid-typing "190"
        assertNull(parseMaxHr("190.5"))
    }

    @Test
    fun `zone seconds are tallied one second per sample`() {
        assertEquals(
            ZoneSeconds(zone1 = 1, zone2 = 2, zone3 = 1, zone4 = 1, zone5 = 1),
            tallyZoneSeconds(listOf(100, 120, 120, 140, 160, 175), maxHr)
        )
    }

    @Test
    fun `samples with no heart rate bank no zone time`() {
        // The recorder writes one row per second and only when BPM > 0, so seconds with no signal
        // have no row — they must stay unfabricated rather than land in Zone 1.
        assertEquals(ZoneSeconds(), tallyZoneSeconds(listOf(0, -1), maxHr))
        assertEquals(ZoneSeconds(), tallyZoneSeconds(emptyList(), maxHr))
    }

    @Test
    fun `the tally clamps max hr the same way the zone edges do`() {
        assertEquals(tallyZoneSeconds(listOf(150), MAX_MAX_HR), tallyZoneSeconds(listOf(150), 999))
    }
}
