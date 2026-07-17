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

        fun ofNumber(number: Int): HrZone? = entries.firstOrNull { it.number == number }

        /** For stored values, which may predate this zone model or be absent entirely. */
        fun ofNumberOrDefault(number: Int?): HrZone =
            number?.let { ofNumber(it) } ?: DEFAULT_TARGET
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

fun effectiveMaxHr(maxHr: Int): Int = maxHr.coerceIn(MIN_MAX_HR, MAX_MAX_HR)

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

val UserSettings.targetHrZone: HrZone get() = HrZone.ofNumberOrDefault(targetZone)

fun hrZoneOf(bpm: Int, settings: UserSettings): HrZone? = hrZoneOf(bpm, settings.maxHr)

fun zoneBandOf(bpm: Int, settings: UserSettings): ZoneBand =
    zoneBandOf(bpm, settings.maxHr, settings.targetHrZone)

/** e.g. "114-132", "up to 113", "171+". */
fun zoneRangeLabel(zone: HrZone, maxHr: Int): String = when (zone) {
    HrZone.ENDURANCE -> "up to ${zoneUpperBpm(zone, maxHr)}"
    HrZone.ANAEROBIC -> "${zoneLowerBpm(zone, maxHr)}+"
    else -> "${zoneLowerBpm(zone, maxHr)}-${zoneUpperBpm(zone, maxHr)}"
}
