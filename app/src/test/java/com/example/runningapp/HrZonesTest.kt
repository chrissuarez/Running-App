package com.example.runningapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HrZonesTest {

    private val profile = HrProfile(190)

    @Test
    fun `the profile is what the zone edges are sliced from`() {
        // The whole point of the value: what a zone edge computes to depends on the profile and
        // on nothing else, so the settings the zones came from can never be half-applied.
        assertEquals(114, zoneLowerBpm(HrZone.MODERATE, HrProfile(190)))
        assertEquals(96, zoneLowerBpm(HrZone.MODERATE, HrProfile(160)))
    }

    @Test
    fun `zone names follow the published table`() {
        assertEquals(
            listOf("Endurance", "Moderate", "Tempo", "Threshold", "Anaerobic"),
            HrZone.entries.map { it.zoneName }
        )
        assertEquals(listOf(50, 60, 70, 80, 90), HrZone.entries.map { it.lowerPercentOfReserve })
    }

    @Test
    fun `every zone boundary at max hr 190`() {
        assertEquals(HrZone.ENDURANCE, hrZoneOf(113, profile))
        assertEquals(HrZone.MODERATE, hrZoneOf(114, profile))
        assertEquals(HrZone.MODERATE, hrZoneOf(132, profile))
        assertEquals(HrZone.TEMPO, hrZoneOf(133, profile))
        assertEquals(HrZone.TEMPO, hrZoneOf(151, profile))
        assertEquals(HrZone.THRESHOLD, hrZoneOf(152, profile))
        assertEquals(HrZone.THRESHOLD, hrZoneOf(170, profile))
        assertEquals(HrZone.ANAEROBIC, hrZoneOf(171, profile))
    }

    @Test
    fun `hr below 50 percent of max hr counts as zone 1`() {
        assertEquals(HrZone.ENDURANCE, hrZoneOf(95, profile)) // exactly 50%
        assertEquals(HrZone.ENDURANCE, hrZoneOf(94, profile))
        assertEquals(HrZone.ENDURANCE, hrZoneOf(40, profile))
        assertEquals(HrZone.ENDURANCE, hrZoneOf(1, profile))
    }

    @Test
    fun `above max hr stays in zone 5`() {
        assertEquals(HrZone.ANAEROBIC, hrZoneOf(190, profile))
        assertEquals(HrZone.ANAEROBIC, hrZoneOf(240, profile))
    }

    @Test
    fun `no heart rate is not a zone`() {
        assertNull(hrZoneOf(0, profile))
        assertNull(hrZoneOf(-5, profile))
    }

    @Test
    fun `the old zone 3 bug case is classified by percentage alone`() {
        // The shipped "Derive Defaults" button set zone2High to 143, so the hand-typed Zone 2
        // band swallowed 133-143 — every one of those seconds was filed as Zone 2 rather than
        // Tempo, and Zone 3 was left a 9-BPM sliver. Zones no longer read a typed band at all.
        assertEquals(HrZone.TEMPO, hrZoneOf(140, profile)) // 73.7% — was Zone 2
        assertEquals(HrZone.TEMPO, hrZoneOf(143, profile)) // 75.3% — was Zone 2
        assertEquals(HrZone.THRESHOLD, hrZoneOf(156, profile)) // 82.1% — Threshold per the table
    }

    @Test
    fun `zone 3 is reachable at every possible max hr`() {
        for (candidate in -50..400) {
            val profile = HrProfile(candidate)
            val low = zoneLowerBpm(HrZone.TEMPO, profile)
            val high = zoneUpperBpm(HrZone.TEMPO, profile)
            assertTrue("Zone 3 collapsed at maxHr=$candidate ($low..$high)", high >= low)
            assertEquals(
                "maxHr=$candidate",
                HrZone.TEMPO,
                hrZoneOf(low, profile)
            )
            assertEquals(
                "maxHr=$candidate",
                HrZone.TEMPO,
                hrZoneOf(high, profile)
            )
        }
    }

    @Test
    fun `every zone is reachable at every possible max hr`() {
        for (candidate in -50..400) {
            val profile = HrProfile(candidate)
            for (zone in HrZone.entries) {
                val low = zoneLowerBpm(zone, profile)
                assertEquals("maxHr=$candidate zone=$zone", zone, hrZoneOf(low, profile))
            }
        }
    }

    @Test
    fun `zones tile the bpm range with no gaps`() {
        var previous: HrZone? = null
        for (bpm in 1..250) {
            val zone = hrZoneOf(bpm, profile)!!
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
        assertEquals(zoneLowerBpm(HrZone.TEMPO, HrProfile(MIN_MAX_HR)), zoneLowerBpm(HrZone.TEMPO, HrProfile(20)))
        assertEquals(zoneLowerBpm(HrZone.TEMPO, HrProfile(MAX_MAX_HR)), zoneLowerBpm(HrZone.TEMPO, HrProfile(900)))
    }

    @Test
    fun `band compares the current zone to the target zone`() {
        assertEquals(ZoneBand.BELOW, zoneBandOf(100, profile, HrZone.MODERATE))
        assertEquals(ZoneBand.IN, zoneBandOf(120, profile, HrZone.MODERATE))
        assertEquals(ZoneBand.ABOVE, zoneBandOf(140, profile, HrZone.MODERATE))
        assertEquals(ZoneBand.UNKNOWN, zoneBandOf(0, profile, HrZone.MODERATE))

        // Same BPM, different target.
        assertEquals(ZoneBand.BELOW, zoneBandOf(140, profile, HrZone.THRESHOLD))
        assertEquals(ZoneBand.IN, zoneBandOf(140, profile, HrZone.TEMPO))
    }

    @Test
    fun `every target has an outside, so high-HR cues stay reachable`() {
        // The high-HR branch — the safety override included — only fires on ABOVE. A target with
        // no ABOVE silences it. Zone 5 is the case that matters: its chart slice is open-ended.
        assertEquals(ZoneBand.ABOVE, zoneBandOf(profile.maxHr + 1, profile, HrZone.ANAEROBIC))
        assertEquals(ZoneBand.ABOVE, zoneBandOf(profile.maxHr + 15, profile, HrZone.ANAEROBIC))
        assertEquals(ZoneBand.IN, zoneBandOf(profile.maxHr, profile, HrZone.ANAEROBIC))

        // Zone 1 is the mirror: its slice swallows everything below 50%, the band must not.
        assertEquals(ZoneBand.BELOW, zoneBandOf(40, profile, HrZone.ENDURANCE))
        assertEquals(ZoneBand.IN, zoneBandOf(100, profile, HrZone.ENDURANCE))
    }

    @Test
    fun `no max hr leaves any target trapped in a single band`() {
        for (candidate in -50..400) {
            val profile = HrProfile(candidate)
            for (target in HrZone.entries) {
                val bands = (1..500).map { zoneBandOf(it, profile, target) }.toSet()
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
        assertEquals("114-132", targetRangeLabel(HrZone.MODERATE, profile))
        assertEquals("133-151", targetRangeLabel(HrZone.TEMPO, profile))
        assertEquals("152-170", targetRangeLabel(HrZone.THRESHOLD, profile))
    }

    @Test
    fun `target ranges are closed, so they never advertise BPM outside the band`() {
        // Zones 2-4 read the same either way; only the open-ended ends differ.
        assertEquals("114-132", targetRangeLabel(HrZone.MODERATE, profile))
        assertEquals("95-113", targetRangeLabel(HrZone.ENDURANCE, profile))
        assertEquals("171-190", targetRangeLabel(HrZone.ANAEROBIC, profile))

        // Every edge a target range shows must read IN, and stepping outside must not.
        for (target in HrZone.entries) {
            val low = zoneLowerBpm(target, profile)
            val high = zoneUpperBpm(target, profile)
            assertEquals(ZoneBand.IN, zoneBandOf(low, profile, target))
            assertEquals(ZoneBand.IN, zoneBandOf(high, profile, target))
            assertEquals(ZoneBand.BELOW, zoneBandOf(low - 1, profile, target))
            assertEquals(ZoneBand.ABOVE, zoneBandOf(high + 1, profile, target))
        }
    }

    @Test
    fun `hysteresis holds you out until you reach the midpoint`() {
        // Target Tempo at maxHr 190: low 133, high 151, midpoint 142.
        // Coming from ABOVE, you stay ABOVE until the heart rate drops to the midpoint.
        assertEquals(ZoneBand.ABOVE, bandWithHysteresis(ZoneBand.ABOVE, 150, profile, HrZone.TEMPO))
        assertEquals(ZoneBand.ABOVE, bandWithHysteresis(ZoneBand.ABOVE, 143, profile, HrZone.TEMPO))
        assertEquals(ZoneBand.IN, bandWithHysteresis(ZoneBand.ABOVE, 142, profile, HrZone.TEMPO))
        // Coming from BELOW, you stay BELOW until you climb to the midpoint.
        assertEquals(ZoneBand.BELOW, bandWithHysteresis(ZoneBand.BELOW, 134, profile, HrZone.TEMPO))
        assertEquals(ZoneBand.IN, bandWithHysteresis(ZoneBand.BELOW, 142, profile, HrZone.TEMPO))
    }

    @Test
    fun `an overshoot to the far side of the zone is out, not a false recovery`() {
        // Falling from ABOVE clean through the zone to below the lower edge is BELOW, not IN — the
        // runner is still out of target, so no "recovered" cue and no ladder reset.
        assertEquals(ZoneBand.BELOW, bandWithHysteresis(ZoneBand.ABOVE, 120, profile, HrZone.TEMPO))
        // The mirror: climbing from BELOW clean past the upper edge is ABOVE, not IN.
        assertEquals(ZoneBand.ABOVE, bandWithHysteresis(ZoneBand.BELOW, 160, profile, HrZone.TEMPO))
    }

    @Test
    fun `with no prior out-of-zone state hysteresis is just the plain band`() {
        assertEquals(ZoneBand.IN, bandWithHysteresis(ZoneBand.IN, 140, profile, HrZone.TEMPO))
        assertEquals(ZoneBand.IN, bandWithHysteresis(ZoneBand.UNKNOWN, 140, profile, HrZone.TEMPO))
        assertEquals(ZoneBand.ABOVE, bandWithHysteresis(ZoneBand.IN, 160, profile, HrZone.TEMPO))
        assertEquals(ZoneBand.BELOW, bandWithHysteresis(ZoneBand.IN, 120, profile, HrZone.TEMPO))
        assertEquals(ZoneBand.UNKNOWN, bandWithHysteresis(ZoneBand.ABOVE, 0, profile, HrZone.TEMPO))
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
    fun `a settings target is always a coaching target, whatever is stored`() {
        assertEquals(HrZone.THRESHOLD, UserSettings(targetZone = 5).targetHrZone)
        assertEquals(HrZone.MODERATE, UserSettings(targetZone = 1).targetHrZone)
        assertEquals(HrZone.TEMPO, UserSettings(targetZone = 3).targetHrZone)
    }

    @Test
    fun `coaching targets are the interior zones only`() {
        assertEquals(listOf(HrZone.MODERATE, HrZone.TEMPO, HrZone.THRESHOLD), HrZone.COACHING_TARGETS)
    }

    @Test
    fun `for every coaching target, the chart bucket and the band agree`() {
        // This is why the target is restricted (#117): "In Target" is derived on read as the
        // target zone's own seconds, which is only exact where bucket and band coincide.
        for (max in MIN_MAX_HR..MAX_MAX_HR) {
            for (target in HrZone.COACHING_TARGETS) {
                for (bpm in 1..300) {
                    val profile = HrProfile(max)
                    val inBucket = hrZoneOf(bpm, profile) == target
                    val inBand = zoneBandOf(bpm, profile, target) == ZoneBand.IN
                    assertEquals("maxHr=$max target=$target bpm=$bpm", inBucket, inBand)
                }
            }
        }
    }

    @Test
    fun `the excluded edge zones are excluded because bucket and band diverge`() {
        // Zone 5's bucket is open-ended upward and Zone 1's swallows everything beneath it, but a
        // target must have an outside — so above Max HR and far below Zone 1 the two disagree.
        assertEquals(HrZone.ANAEROBIC, hrZoneOf(205, profile))
        assertEquals(ZoneBand.ABOVE, zoneBandOf(205, profile, HrZone.ANAEROBIC))

        assertEquals(HrZone.ENDURANCE, hrZoneOf(60, profile))
        assertEquals(ZoneBand.BELOW, zoneBandOf(60, profile, HrZone.ENDURANCE))
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
        assertEquals(100, parseMaxHrAlone("100"))
        assertEquals(190, parseMaxHrAlone("190"))
        assertEquals(230, parseMaxHrAlone("230"))
        assertEquals(185, parseMaxHrAlone(" 185 "))
    }

    @Test
    fun `a typed max hr outside the range is refused rather than clamped`() {
        // effectiveMaxHr clamps because storage must never hold an unusable number; typing is the
        // opposite case — a refusal the runner can see beats a number they did not choose.
        assertNull(parseMaxHrAlone("99"))
        assertNull(parseMaxHrAlone("231"))
        assertNull(parseMaxHrAlone("0"))
        assertNull(parseMaxHrAlone("-190"))
    }

    @Test
    fun `a max hr that is not a whole number is refused`() {
        assertNull(parseMaxHrAlone(""))
        assertNull(parseMaxHrAlone("   "))
        assertNull(parseMaxHrAlone("abc"))
        assertNull(parseMaxHrAlone("19"))    // mid-typing "190"
        assertNull(parseMaxHrAlone("190.5"))
    }

    @Test
    fun `zone seconds are tallied one second per sample`() {
        assertEquals(
            ZoneSeconds(zone1 = 1, zone2 = 2, zone3 = 1, zone4 = 1, zone5 = 1),
            tallyZoneSeconds(listOf(100, 120, 120, 140, 160, 175), profile)
        )
    }

    @Test
    fun `samples with no heart rate bank no zone time`() {
        // The recorder writes one row per second and only when BPM > 0, so seconds with no signal
        // have no row — they must stay unfabricated rather than land in Zone 1.
        assertEquals(ZoneSeconds(), tallyZoneSeconds(listOf(0, -1), profile))
        assertEquals(ZoneSeconds(), tallyZoneSeconds(emptyList(), profile))
    }

    @Test
    fun `the tally clamps max hr the same way the zone edges do`() {
        assertEquals(tallyZoneSeconds(listOf(150), HrProfile(MAX_MAX_HR)), tallyZoneSeconds(listOf(150), HrProfile(999)))
    }

    @Test
    fun `the tally clamps resting hr the same way the zone edges do`() {
        // The v12 to v13 migration re-tallies in SQL against a raw database, where no clamp of its
        // own is reachable — so a clamp anywhere but inside the zone edges makes the migration's
        // tally silently disagree with the app's. Same guarantee as the Max HR case above.
        assertEquals(
            tallyZoneSeconds(listOf(150), HrProfile(190, effectiveRestingHr(999, 190))),
            tallyZoneSeconds(listOf(150), HrProfile(190, 999))
        )
        assertEquals(
            tallyZoneSeconds(listOf(150), HrProfile(190, RESTING_HR_UNSTATED)),
            tallyZoneSeconds(listOf(150), HrProfile(190, -40))
        )
    }

    // --- Zones sliced from heart-rate reserve (#172, ADR 0004) ---

    @Test
    fun `zone edges are sliced from the gap between the two numbers`() {
        // The runner's own numbers: Max HR 181, resting 60, so a reserve of 121. Under Max HR
        // alone Zone 2 was 109-126 and their easy jog at 140 read as Tempo; the talk test says
        // otherwise, and reserve agrees with the talk test.
        val reserve = HrProfile(maxHr = 181, restingHr = 60)

        assertEquals(121, zoneLowerBpm(HrZone.ENDURANCE, reserve))
        assertEquals(133, zoneLowerBpm(HrZone.MODERATE, reserve))
        assertEquals(145, zoneLowerBpm(HrZone.TEMPO, reserve))
        assertEquals(157, zoneLowerBpm(HrZone.THRESHOLD, reserve))
        assertEquals(169, zoneLowerBpm(HrZone.ANAEROBIC, reserve))
        // Zone 5 still tops out at Max HR itself.
        assertEquals(181, zoneUpperBpm(HrZone.ANAEROBIC, reserve))
        assertEquals("133-144", targetRangeLabel(HrZone.MODERATE, reserve))
    }

    @Test
    fun `an easy jog reads as moderate under reserve where it read as tempo before`() {
        val maxHrOnly = HrProfile(maxHr = 181)
        val reserve = HrProfile(maxHr = 181, restingHr = 60)

        assertEquals(HrZone.TEMPO, hrZoneOf(140, maxHrOnly))
        assertEquals(HrZone.MODERATE, hrZoneOf(140, reserve))
        assertEquals(ZoneBand.ABOVE, zoneBandOf(140, maxHrOnly, HrZone.MODERATE))
        assertEquals(ZoneBand.IN, zoneBandOf(140, reserve, HrZone.MODERATE))
    }

    @Test
    fun `with no resting hr stated every zone edge is exactly what it was before`() {
        // The property the whole change rests on: `0 + (max - 0) x pct` is `max x pct`, so nobody
        // upgrading sees a single second move zone until they state a number. If an expected value
        // anywhere in this file had to change, the formula is wrong, not the test.
        for (candidate in -50..400) {
            val profile = HrProfile(candidate)
            val max = effectiveMaxHr(candidate)
            for (zone in HrZone.entries) {
                assertEquals(
                    "maxHr=$candidate zone=$zone",
                    (max * zone.lowerPercentOfReserve + 99) / 100,
                    zoneLowerBpm(zone, profile)
                )
            }
        }
    }

    @Test
    fun `unstated is a value, not a number to be clamped up into the typeable range`() {
        assertEquals(RESTING_HR_UNSTATED, effectiveRestingHr(RESTING_HR_UNSTATED, 190))
        assertEquals(HrProfile(190), HrProfile(190, RESTING_HR_UNSTATED))
    }

    @Test
    fun `the typeable ceiling follows the max hr beside it`() {
        // A refusal at exactly the point storage would have clamped: without this, a runner with a
        // low Max HR types an accepted 90, storage quietly holds 50, and the field shows the 50
        // back with nothing said — the failure the visible refusal exists to delete.
        assertEquals(50, highestStatableRestingHr(100))
        assertNull(parseRestingHr("90", maxHr = 100))
        assertEquals(50, parseRestingHr("50", maxHr = 100))
        assertEquals(90, parseRestingHr("90", maxHr = 190))
    }

    @Test
    fun `the typeable floor under max hr follows the resting hr beside it`() {
        // The mirror of the ceiling above, and for the same reason: without it the reserve rule
        // holds on one door only, and lowering the maximum quietly rewrites the stated resting
        // heart rate instead of saying it will not fit.
        assertEquals(110, lowestStatableMaxHr(60))
        assertNull(parseMaxHr("100", restingHr = 60))
        assertEquals(110, parseMaxHr("110", restingHr = 60))
        assertEquals(100, parseMaxHr("100", restingHr = 30))
    }

    @Test
    fun `an unstated resting hr leaves the max hr floor where it always was`() {
        // Nothing is stated above, so nothing is constrained: every runner who has never typed the
        // second number must still see the range they saw before #172.
        assertEquals(MIN_MAX_HR, lowestStatableMaxHr(RESTING_HR_UNSTATED))
        assertEquals(MIN_MAX_HR, parseMaxHrAlone("$MIN_MAX_HR"))
        assertNull(parseMaxHrAlone("${MIN_MAX_HR - 1}"))
    }

    @Test
    fun `the two typeable ranges name the same reserve from either end`() {
        // One rule, spelled from both doors: a pair either leaves a usable reserve or it does not,
        // and the two fields must never disagree about which.
        for (maxHr in MIN_MAX_HR..MAX_MAX_HR) {
            for (restingHr in MIN_RESTING_HR..MAX_RESTING_HR) {
                assertEquals(
                    "maxHr=$maxHr restingHr=$restingHr",
                    restingHr <= highestStatableRestingHr(maxHr),
                    maxHr >= lowestStatableMaxHr(restingHr)
                )
            }
        }
    }

    @Test
    fun `a resting hr is clamped against the max hr beside it, not on its own`() {
        // The pair bounds one reserve, so a resting heart rate is only unusable relative to a
        // maximum: 100 is fine under a Max HR of 190 and impossible under one of 100.
        assertEquals(100, effectiveRestingHr(100, 190))
        assertEquals(MIN_HR_RESERVE, effectiveMaxHr(100) - effectiveRestingHr(100, 100))
        assertEquals(MAX_RESTING_HR, effectiveRestingHr(150, 230))
    }

    @Test
    fun `every zone stays a non-empty band at every settable pair`() {
        for (maxCandidate in -50..400 step 7) {
            for (restingCandidate in -50..250 step 3) {
                val profile = HrProfile(maxCandidate, restingCandidate)
                for (zone in HrZone.entries) {
                    val low = zoneLowerBpm(zone, profile)
                    val high = zoneUpperBpm(zone, profile)
                    val where = "maxHr=$maxCandidate restingHr=$restingCandidate zone=$zone"
                    assertTrue("$where collapsed ($low..$high)", high >= low)
                    assertEquals(where, zone, hrZoneOf(low, profile))
                    assertEquals(where, zone, hrZoneOf(high, profile))
                }
            }
        }
    }

    @Test
    fun `no pair of numbers leaves any target trapped in a single band`() {
        // The mirror of the Max-HR-only sweep above: the high-HR cues and the safety override only
        // fire on ABOVE, so a target with no outside silences them.
        for (maxCandidate in -50..400 step 11) {
            for (restingCandidate in listOf(-40, RESTING_HR_UNSTATED, 30, 60, 100, 200)) {
                val profile = HrProfile(maxCandidate, restingCandidate)
                for (target in HrZone.entries) {
                    val bands = (1..500).map { zoneBandOf(it, profile, target) }.toSet()
                    val where = "maxHr=$maxCandidate restingHr=$restingCandidate target=$target"
                    assertTrue("$where never reads ABOVE", bands.contains(ZoneBand.ABOVE))
                    assertTrue("$where never reads IN", bands.contains(ZoneBand.IN))
                    assertTrue("$where never reads BELOW", bands.contains(ZoneBand.BELOW))
                }
            }
        }
    }

    @Test
    fun `a heart rate at or below the resting number is still zone 1`() {
        // No real second may vanish from the chart, and the runner's resting heart rate is the one
        // reading guaranteed to sit under every zone edge.
        val reserve = HrProfile(maxHr = 181, restingHr = 60)
        assertEquals(HrZone.ENDURANCE, hrZoneOf(60, reserve))
        assertEquals(HrZone.ENDURANCE, hrZoneOf(45, reserve))
        assertEquals(HrZone.ENDURANCE, hrZoneOf(1, reserve))
        assertNull(hrZoneOf(0, reserve))
    }

    @Test
    fun `a typed resting hr is accepted only inside its own settable range`() {
        assertEquals(30, parseRestingHr("30", maxHr = 190))
        assertEquals(60, parseRestingHr("60", maxHr = 190))
        assertEquals(100, parseRestingHr("100", maxHr = 190))
        assertEquals(58, parseRestingHr(" 58 ", maxHr = 190))
    }

    @Test
    fun `a typed resting hr outside the range is refused rather than clamped`() {
        assertNull(parseRestingHr("29", maxHr = 190))
        assertNull(parseRestingHr("101", maxHr = 190))
        assertNull(parseRestingHr("6", maxHr = 190))      // a pulse counted wrong
        assertNull(parseRestingHr("0", maxHr = 190))      // "unstated" is not something you type
        assertNull(parseRestingHr("-60", maxHr = 190))
        assertNull(parseRestingHr("", maxHr = 190))
        assertNull(parseRestingHr("abc", maxHr = 190))
        assertNull(parseRestingHr("60.5", maxHr = 190))
    }

    @Test
    fun `settings carry both numbers into the profile`() {
        // Half an update is a band nobody's zones ever were, so the pair travels as one value.
        assertEquals(
            HrProfile(181, 60),
            UserSettings(maxHr = 181, restingHr = 60).hrProfile
        )
        assertEquals(RESTING_HR_UNSTATED, UserSettings().hrProfile.restingHr)
    }
}

/**
 * What the Max HR confirmation card is offered to offer, and what it refuses to (#65, #103).
 *
 * The rules and not the drawing: what makes this card right is that it never suggests a number the
 * field beneath it would refuse, and never dresses up a population formula as the runner's own
 * evidence.
 */
class SuggestedMaxHrTest {

    @Test
    fun `the runner's own recorded maximum is what the card offers`() {
        // The whole argument of #103: 181 measured beats 190 assumed, and beats 220 − age too.
        assertEquals(181, suggestedMaxHr(highestRecordedBpm = 181, restingHr = 60))
    }

    @Test
    fun `nothing recorded is nothing to suggest`() {
        // Not a fallback applied here — the card asks for an age instead, which is a different
        // question and a visible one.
        assertNull(suggestedMaxHr(highestRecordedBpm = null, restingHr = 60))
    }

    @Test
    fun `an artefact above the statable range is not offered as anybody's maximum`() {
        // Past the spike guard and still impossible: a strap that reported 240 for three banked
        // seconds. Offering it would put a number in the field the field itself refuses.
        assertNull(suggestedMaxHr(highestRecordedBpm = MAX_MAX_HR + 1, restingHr = RESTING_HR_UNSTATED))
        assertEquals(MAX_MAX_HR, suggestedMaxHr(MAX_MAX_HR, RESTING_HR_UNSTATED))
    }

    @Test
    fun `a peak with no reserve above the resting heart rate is not offered either`() {
        // A history of gentle walking, and a stated resting 60. 100 is inside Max HR's own range
        // but leaves no usable reserve, so the settings screen refuses it — and so does this.
        assertNull(suggestedMaxHr(highestRecordedBpm = MIN_MAX_HR, restingHr = 60))
        // The same peak, with no resting heart rate stated, has nothing to leave room above.
        assertEquals(MIN_MAX_HR, suggestedMaxHr(MIN_MAX_HR, RESTING_HR_UNSTATED))
    }

    @Test
    fun `an age gives the old formula and nothing more`() {
        assertEquals(180, maxHrForAge(40))
        assertEquals(190, maxHrForAge(30))
    }

    @Test
    fun `an age outside the range this app will do arithmetic on is refused`() {
        assertEquals(40, parseAge("40"))
        assertEquals(40, parseAge(" 40 "))
        assertNull(parseAge(""))
        assertNull(parseAge("forty"))
        assertNull(parseAge((MIN_STATABLE_AGE - 1).toString()))
        assertNull(parseAge((MAX_STATABLE_AGE + 1).toString()))
    }

    @Test
    fun `every age this app accepts suggests a heart rate it accepts`() {
        // The two ranges have to agree, or the fallback offers a number its own field refuses.
        (MIN_STATABLE_AGE..MAX_STATABLE_AGE).forEach { age ->
            val suggestion = maxHrForAge(age)
            assertEquals(
                "age $age suggests $suggestion",
                suggestion,
                suggestedMaxHr(suggestion, RESTING_HR_UNSTATED)
            )
        }
    }
}
