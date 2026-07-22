package com.example.runningapp

/**
 * The one place heart rate becomes a zone.
 *
 * All five zones are fixed slices of Max HR on the Polar convention, carrying Strava's five
 * names (our choice — Strava publishes no percentages). Nothing here is user-typed, so no
 * configuration can invert a zone or collapse it to nothing.
 */
enum class HrZone(val number: Int, val zoneName: String, val lowerPercentOfMaxHr: Int) {
    ENDURANCE(1, "Endurance", 50),
    MODERATE(2, "Moderate", 60),
    TEMPO(3, "Tempo", 70),
    THRESHOLD(4, "Threshold", 80),
    ANAEROBIC(5, "Anaerobic", 90);

    companion object {
        val DEFAULT_TARGET = MODERATE

        /**
         * The zones a whole run may aim at (#117).
         *
         * Zones 1 and 5 are the two where the chart bucket and the target band diverge — the
         * bucket is open-ended in one direction, the band is not — so a run targeting either
         * would have its "In Target" total overstated, that total being derived on read as the
         * target zone's own seconds (#106). Sustained coaching to recovery or to maximal isn't a
         * meaningful whole-run target anyway, so excluding them costs nothing and keeps the
         * derivation exact.
         */
        val COACHING_TARGETS = listOf(MODERATE, TEMPO, THRESHOLD)

        fun ofNumber(number: Int): HrZone? = entries.firstOrNull { it.number == number }

        /** For stored values, which may predate this zone model or be absent entirely. */
        fun ofNumberOrDefault(number: Int?): HrZone =
            number?.let { ofNumber(it) } ?: DEFAULT_TARGET

        /**
         * The same, for a value that has to be a coaching target: a stored edge zone — settable
         * before #117 closed the picker — lands on the nearest zone that is one, since the reason
         * it is excluded is that it is not a meaningful *whole-run* target, not that the runner
         * aimed nowhere near it.
         */
        fun coachingTargetOfNumberOrDefault(number: Int?): HrZone {
            val zone = number?.let { ofNumber(it) } ?: return DEFAULT_TARGET
            return when {
                zone.number < MODERATE.number -> MODERATE
                zone.number > THRESHOLD.number -> THRESHOLD
                else -> zone
            }
        }
    }
}

/** Where you are relative to your target zone. */
enum class ZoneBand {
    BELOW,
    IN,
    ABOVE,
    UNKNOWN
}

/**
 * Max HR is clamped to this range before any zone edge is computed, which is what keeps every
 * zone — Zone 3 included — a non-empty band at every possible setting.
 */
const val MIN_MAX_HR = 100
const val MAX_MAX_HR = 230

/**
 * The Max HR the app assumes until someone states theirs.
 *
 * Load-bearing rather than cosmetic: it is the value #112 treats as "nobody has chosen yet", so
 * history sitting on it is stranded and a stored number *differing* from it is evidence of a
 * deliberate set. Both readings break if this drifts from [UserSettings.maxHr]'s default.
 */
const val DEFAULT_MAX_HR = 190

fun effectiveMaxHr(maxHr: Int): Int = maxHr.coerceIn(MIN_MAX_HR, MAX_MAX_HR)

/**
 * A typed Max HR, or null if it is not a whole number inside the settable range.
 *
 * Deliberately not [effectiveMaxHr]: storage clamps because it must never hold an unusable
 * number, but a *typed* value out of range is a mistake, and silently keeping some other number
 * is the failure this replaces. Null is the caller's cue to refuse visibly.
 */
fun parseMaxHr(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in MIN_MAX_HR..MAX_MAX_HR }

/** Lowest BPM that counts as [zone]. Zone 1 also swallows everything below it — see [hrZoneOf]. */
fun zoneLowerBpm(zone: HrZone, maxHr: Int): Int {
    val max = effectiveMaxHr(maxHr)
    // Ceiling division: the edge belongs to the zone above it.
    return (max * zone.lowerPercentOfMaxHr + 99) / 100
}

/** Highest BPM that counts as [zone]. Zone 5 is open-ended; Max HR stands in as its top. */
fun zoneUpperBpm(zone: HrZone, maxHr: Int): Int {
    val next = HrZone.entries.getOrNull(zone.ordinal + 1) ?: return effectiveMaxHr(maxHr)
    return zoneLowerBpm(next, maxHr) - 1
}

/**
 * The zone for [bpm], or null when there is no heart rate to classify.
 *
 * Anything below 50% of Max HR counts as Zone 1: no real second may vanish from the chart.
 * (This deliberately differs from TRIMP, which zero-weights sub-50% — see #99.)
 */
fun hrZoneOf(bpm: Int, maxHr: Int): HrZone? {
    if (bpm <= 0) return null
    return HrZone.entries.lastOrNull { bpm >= zoneLowerBpm(it, maxHr) } ?: HrZone.ENDURANCE
}

/**
 * Below, on, or above target — the only definition of those words in the app.
 *
 * Banding compares [bpm] to the target zone's edges rather than to the zone it charts as, so
 * every target has an outside. Zone 5's chart slice is open-ended and Zone 1's swallows
 * everything beneath it, but a target must be escapable in both directions: the high-HR cues,
 * including the safety override, only fire on [ZoneBand.ABOVE].
 */
fun zoneBandOf(bpm: Int, maxHr: Int, targetZone: HrZone): ZoneBand {
    if (bpm <= 0) return ZoneBand.UNKNOWN
    return when {
        bpm < zoneLowerBpm(targetZone, maxHr) -> ZoneBand.BELOW
        bpm > zoneUpperBpm(targetZone, maxHr) -> ZoneBand.ABOVE
        else -> ZoneBand.IN
    }
}

/**
 * The target band with symmetric midpoint hysteresis (#108): you leave target the instant you
 * cross an edge, but only count as back IN once you reach the zone's midpoint. That gap is what
 * makes "the ladder resets on re-entry" safe — a heart rate parked on the boundary can't flip
 * IN/OUT every sample and farm return cues. The 30-second wait applies equally above and below.
 *
 * The midpoint only holds you OUT until you have genuinely re-entered; it never counts an
 * overshoot to the far side of the zone as IN. Falling from ABOVE clean through to below the
 * lower edge lands you in [ZoneBand.BELOW], not a false "recovered", and vice versa.
 */
fun bandWithHysteresis(previous: ZoneBand, avgBpm: Int, maxHr: Int, targetZone: HrZone): ZoneBand {
    if (avgBpm <= 0) return ZoneBand.UNKNOWN
    val low = zoneLowerBpm(targetZone, maxHr)
    val high = zoneUpperBpm(targetZone, maxHr)
    val midpoint = low + (high - low) / 2
    return when (previous) {
        ZoneBand.ABOVE -> when {
            avgBpm > midpoint -> ZoneBand.ABOVE
            avgBpm >= low -> ZoneBand.IN
            else -> ZoneBand.BELOW
        }
        ZoneBand.BELOW -> when {
            avgBpm < midpoint -> ZoneBand.BELOW
            avgBpm <= high -> ZoneBand.IN
            else -> ZoneBand.ABOVE
        }
        else -> zoneBandOf(avgBpm, maxHr, targetZone)
    }
}

/** Seconds a run spent in each zone. */
data class ZoneSeconds(
    val zone1: Long = 0,
    val zone2: Long = 0,
    val zone3: Long = 0,
    val zone4: Long = 0,
    val zone5: Long = 0
)

/**
 * Re-tallies zone seconds from a run's stored heart-rate samples.
 *
 * Exact rather than an estimate: the recorder writes exactly one sample per second of the run and
 * only when BPM > 0 — the same condition under which it banked a second of zone time. So counting
 * samples per zone reproduces what the run would have recorded under [maxHr]. Seconds with no
 * signal have no sample and gain no zone time, leaving `noDataSeconds` meaningful and unfabricated.
 *
 * The v12 → v13 migration does this same tally in SQL against a raw database, where none of this
 * is reachable; the two must stay in step.
 */
fun tallyZoneSeconds(bpms: Iterable<Int>, maxHr: Int): ZoneSeconds {
    val clamped = effectiveMaxHr(maxHr)
    val seconds = LongArray(HrZone.entries.size)
    bpms.forEach { bpm ->
        hrZoneOf(bpm, clamped)?.let { seconds[it.number - 1]++ }
    }
    return ZoneSeconds(seconds[0], seconds[1], seconds[2], seconds[3], seconds[4])
}

/**
 * The zone an open run aims at.
 *
 * Always a coaching target, whatever is stored: a settings target that isn't one overstates time
 * on target (#117), and there must be exactly one answer to "what is this run aiming at" — not
 * one for storage and another for whoever reads the settings object. A run's *recorded* target is
 * a different question and stays as recorded; see [com.example.runningapp.data.inTargetZoneSeconds].
 */
val UserSettings.targetHrZone: HrZone get() = HrZone.coachingTargetOfNumberOrDefault(targetZone)

fun hrZoneOf(bpm: Int, settings: UserSettings): HrZone? = hrZoneOf(bpm, settings.maxHr)

fun zoneBandOf(bpm: Int, settings: UserSettings): ZoneBand =
    zoneBandOf(bpm, settings.maxHr, settings.targetHrZone)

/**
 * How [zone] reads as a target: e.g. "114-132", "95-113", "171-190" — always closed.
 *
 * Every target has an outside (see [zoneBandOf]), so a target range must not advertise BPM the
 * band would call BELOW or ABOVE. Zone 1 and Zone 5 chart open-ended, but they don't *aim*
 * open-ended: every BPM range this app shows is a target, so a closed range is the only kind.
 */
fun targetRangeLabel(zone: HrZone, maxHr: Int): String =
    "${zoneLowerBpm(zone, maxHr)}-${zoneUpperBpm(zone, maxHr)}"
