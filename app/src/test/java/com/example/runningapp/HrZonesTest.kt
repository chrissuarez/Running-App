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
    fun `zone range labels read the way the table does`() {
        assertEquals("up to 113", zoneRangeLabel(HrZone.ENDURANCE, maxHr))
        assertEquals("114-132", zoneRangeLabel(HrZone.MODERATE, maxHr))
        assertEquals("133-151", zoneRangeLabel(HrZone.TEMPO, maxHr))
        assertEquals("152-170", zoneRangeLabel(HrZone.THRESHOLD, maxHr))
        assertEquals("171+", zoneRangeLabel(HrZone.ANAEROBIC, maxHr))
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
}
