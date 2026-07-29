package com.example.runningapp

/**
 * The one place heart rate becomes a zone.
 *
 * All five zones are fixed slices of *heart-rate reserve* on the Polar convention, carrying
 * Strava's five names (our choice — Strava publishes no percentages). The percentages are not
 * user-typed, so no configuration can invert a zone or collapse it to nothing.
 *
 * The percentages themselves did not move when the range they slice did (ADR 0004, #172): what
 * changed is that they are read against `max − resting` rather than against Max HR alone.
 */
enum class HrZone(val number: Int, val zoneName: String, val lowerPercentOfReserve: Int) {
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
 * The range a resting heart rate may be *typed* in: elite (low 30s) through untrained (high 90s).
 * Wide enough that no real runner is refused, narrow enough that a misread pulse is.
 */
const val MIN_RESTING_HR = 30
const val MAX_RESTING_HR = 100

/**
 * The narrowest reserve any pair of numbers may produce.
 *
 * Reserve is what the five percentages slice, so it plays the role Max HR alone used to: this is
 * the [MIN_MAX_HR] guarantee restated. Ten percent of 50 is 5, so every zone stays several BPM
 * wide however the two numbers are set — including a Max HR clamped to its floor of 100 with a
 * resting heart rate typed at its ceiling.
 */
const val MIN_HR_RESERVE = 50

/**
 * No resting heart rate stated.
 *
 * Load-bearing rather than a placeholder to be filled: `0 + (max − 0) × pct` is `max × pct`, so
 * until someone states their resting heart rate the reserve model *is* the Max HR model, edge for
 * edge. That is what lets #172 land with no migration and no history moving on upgrade.
 */
const val RESTING_HR_UNSTATED = 0

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
 * The resting heart rate a zone edge is actually sliced from, given the Max HR beside it.
 *
 * The ceiling depends on the other number, which is the whole reason this is a function of both:
 * a resting heart rate is only unusable *relative* to a maximum. Holding the reserve at
 * [MIN_HR_RESERVE] or wider is what keeps every zone non-empty, exactly as [effectiveMaxHr] did
 * when Max HR alone was the range.
 *
 * [RESTING_HR_UNSTATED] is the floor rather than [MIN_RESTING_HR]: "not stated" has to survive the
 * clamp, or every runner who has never typed a number would be silently given a 30 they did not
 * choose and their whole history would move under them.
 */
fun effectiveRestingHr(restingHr: Int, maxHr: Int): Int =
    restingHr.coerceIn(RESTING_HR_UNSTATED, highestStatableRestingHr(maxHr))

/**
 * The highest resting heart rate that still leaves a usable reserve under [maxHr].
 *
 * Both the clamp and the refusal read this, so the number the field refuses past is the same
 * number storage would have corrected to. Two spellings of one ceiling is how a runner ends up
 * typing an accepted value and being shown a different one back.
 */
fun highestStatableRestingHr(maxHr: Int): Int =
    minOf(MAX_RESTING_HR, effectiveMaxHr(maxHr) - MIN_HR_RESERVE)

/**
 * The lowest Max HR that still leaves a usable reserve above [restingHr] — the mirror of
 * [highestStatableRestingHr], and the reason the Max HR field can refuse.
 *
 * A maximum is only unusable *relative* to a resting heart rate, exactly as the reverse is true.
 * Without this the reserve rule would hold on one door only: the resting field refuses a number
 * the maximum cannot hold, while lowering the maximum quietly rewrote the resting number instead
 * — the silent replacement this pair of functions exists to delete.
 *
 * An unstated resting heart rate constrains nothing, so the floor stays [MIN_MAX_HR].
 */
fun lowestStatableMaxHr(restingHr: Int): Int = maxOf(MIN_MAX_HR, restingHr + MIN_HR_RESERVE)

/**
 * The heart rates a runner's zones are sliced from — one value, passed as one thing.
 *
 * Both numbers travel together because a zone edge is meaningless without the pair: they bound
 * the reserve the percentages slice, and half of an update is a band nobody's zones ever were.
 * The Run pins one of these at START exactly as it pinned the bare Max HR (ADR 0002, #131).
 *
 * An unstated resting heart rate is the default, and gives back the pre-#172 model unchanged —
 * see [RESTING_HR_UNSTATED].
 *
 * Clamping stays in the zone functions rather than here: [effectiveMaxHr] and [effectiveRestingHr]
 * are what guarantee every zone is a non-empty band, and a profile that silently corrected its own
 * input would give the settings screen a different number to show than the one it was handed.
 */
data class HrProfile(val maxHr: Int, val restingHr: Int = RESTING_HR_UNSTATED)

/**
 * A typed Max HR, or null if it is not a whole number inside the settable range.
 *
 * Deliberately not [effectiveMaxHr]: storage clamps because it must never hold an unusable
 * number, but a *typed* value out of range is a mistake, and silently keeping some other number
 * is the failure this replaces. Null is the caller's cue to refuse visibly.
 *
 * Judged against [restingHr] for the reason [parseRestingHr] is judged against the maximum: the
 * pair bounds one reserve, and a maximum too low to leave room above a stated resting heart rate
 * would otherwise be accepted and then quietly rewrite that resting number instead. Refusing here
 * is what keeps "no accepted number is ever silently replaced" true through *both* doors.
 */
fun parseMaxHr(text: String, restingHr: Int): Int? =
    parseMaxHrAlone(text)?.takeIf { it >= lowestStatableMaxHr(restingHr) }

/**
 * A typed Max HR judged by its own limits, with no resting heart rate held up beside it.
 *
 * The mirror of [parseRestingHrAlone], and half of what stops the two fields' rules chasing each
 * other: each asks what the other is *holding* through the alone-reading, then applies the pair
 * rule itself. Named rather than spelled as a default argument on [parseMaxHr] — they are two
 * different rules, and one name for both is how a call site ends up applying the weaker one by
 * accident.
 */
fun parseMaxHrAlone(text: String): Int? =
    text.trim().toIntOrNull()?.takeIf { it in MIN_MAX_HR..MAX_MAX_HR }

/**
 * A typed resting heart rate, or null if it is not a whole number inside the settable range.
 *
 * Same standing as [parseMaxHr], for the same reason: this is a number the runner measured, so a
 * value outside the range is a mistake to show them rather than one to quietly round away.
 *
 * Judged against [maxHr] because the ceiling depends on it — the pair has to leave a usable
 * reserve. Refusing at exactly the point [effectiveRestingHr] would have clamped is what keeps
 * "never silently kept some other number" true: without it, a runner with a low Max HR could type
 * an accepted 90, have storage quietly hold 50, and be shown the 50 back with nothing said.
 */
fun parseRestingHr(text: String, maxHr: Int): Int? =
    parseRestingHrAlone(text)?.takeIf { it <= highestStatableRestingHr(maxHr) }

/**
 * A typed resting heart rate judged by its own limits, with no maximum held up beside it.
 *
 * What the Max HR field reads when it asks what the resting field is holding, and the rung that
 * stops the two rules recursing: each field judges the *other's* entry by that entry's own range,
 * then applies the pair rule itself. Reading a number that is inside its own range but not yet
 * checked against its partner is exactly right here — the partner is the one about to check it.
 */
fun parseRestingHrAlone(text: String): Int? =
    text.trim().toIntOrNull()?.takeIf { it in MIN_RESTING_HR..MAX_RESTING_HR }

/**
 * Lowest BPM that counts as [zone]. Zone 1 also swallows everything below it — see [hrZoneOf].
 *
 * The one place a percentage becomes a BPM, and so the one place the reserve model lives: every
 * edge, band, label and tally in the app reads through here (ADR 0004).
 */
fun zoneLowerBpm(zone: HrZone, profile: HrProfile): Int {
    val max = effectiveMaxHr(profile.maxHr)
    val resting = effectiveRestingHr(profile.restingHr, profile.maxHr)
    // Ceiling division: the edge belongs to the zone above it.
    return resting + ((max - resting) * zone.lowerPercentOfReserve + 99) / 100
}

/** Highest BPM that counts as [zone]. Zone 5 is open-ended; Max HR stands in as its top. */
fun zoneUpperBpm(zone: HrZone, profile: HrProfile): Int {
    val next = HrZone.entries.getOrNull(zone.ordinal + 1) ?: return effectiveMaxHr(profile.maxHr)
    return zoneLowerBpm(next, profile) - 1
}

/**
 * The zone for [bpm], or null when there is no heart rate to classify.
 *
 * Anything below Zone 1's lower edge — 50% of reserve, and so the resting heart rate itself —
 * counts as Zone 1: no real second may vanish from the chart.
 * (This deliberately differs from TRIMP, which zero-weights sub-50% — see #99.)
 */
fun hrZoneOf(bpm: Int, profile: HrProfile): HrZone? {
    if (bpm <= 0) return null
    return HrZone.entries.lastOrNull { bpm >= zoneLowerBpm(it, profile) } ?: HrZone.ENDURANCE
}

/**
 * Below, on, or above target — the only definition of those words in the app.
 *
 * Banding compares [bpm] to the target zone's edges rather than to the zone it charts as, so
 * every target has an outside. Zone 5's chart slice is open-ended and Zone 1's swallows
 * everything beneath it, but a target must be escapable in both directions: the high-HR cues,
 * including the safety override, only fire on [ZoneBand.ABOVE].
 */
fun zoneBandOf(bpm: Int, profile: HrProfile, targetZone: HrZone): ZoneBand {
    if (bpm <= 0) return ZoneBand.UNKNOWN
    return when {
        bpm < zoneLowerBpm(targetZone, profile) -> ZoneBand.BELOW
        bpm > zoneUpperBpm(targetZone, profile) -> ZoneBand.ABOVE
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
fun bandWithHysteresis(
    previous: ZoneBand,
    avgBpm: Int,
    profile: HrProfile,
    targetZone: HrZone,
): ZoneBand {
    if (avgBpm <= 0) return ZoneBand.UNKNOWN
    val low = zoneLowerBpm(targetZone, profile)
    val high = zoneUpperBpm(targetZone, profile)
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
        else -> zoneBandOf(avgBpm, profile, targetZone)
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
 * One more second in [zone].
 *
 * Banking a second is a `when` over five fields wherever it happens; having it in one place is what
 * stops a sixth zone, or a typo, from being spelled twice.
 */
fun ZoneSeconds.plusSecondIn(zone: HrZone): ZoneSeconds = when (zone) {
    HrZone.ENDURANCE -> copy(zone1 = zone1 + 1)
    HrZone.MODERATE -> copy(zone2 = zone2 + 1)
    HrZone.TEMPO -> copy(zone3 = zone3 + 1)
    HrZone.THRESHOLD -> copy(zone4 = zone4 + 1)
    HrZone.ANAEROBIC -> copy(zone5 = zone5 + 1)
}

/**
 * Re-tallies zone seconds from a run's stored heart-rate samples.
 *
 * Exact rather than an estimate: the recorder writes exactly one sample per second of the run and
 * only when BPM > 0 — the same condition under which it banked a second of zone time. So counting
 * samples per zone reproduces what the run would have recorded under [profile]. Seconds with no
 * signal have no sample and gain no zone time, leaving `noDataSeconds` meaningful and unfabricated.
 *
 * The v12 → v13 migration does this same tally in SQL against a raw database, where none of this
 * is reachable; the two must stay in step.
 */
fun tallyZoneSeconds(bpms: Iterable<Int>, profile: HrProfile): ZoneSeconds {
    val seconds = LongArray(HrZone.entries.size)
    bpms.forEach { bpm ->
        hrZoneOf(bpm, profile)?.let { seconds[it.number - 1]++ }
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

/** The runner's stated heart rates, as the zone functions take them. */
val UserSettings.hrProfile: HrProfile get() = HrProfile(maxHr, restingHr)

fun hrZoneOf(bpm: Int, settings: UserSettings): HrZone? = hrZoneOf(bpm, settings.hrProfile)

fun zoneBandOf(bpm: Int, settings: UserSettings): ZoneBand =
    zoneBandOf(bpm, settings.hrProfile, settings.targetHrZone)

/**
 * How [zone] reads as a target: e.g. "114-132", "95-113", "171-190" — always closed.
 *
 * Every target has an outside (see [zoneBandOf]), so a target range must not advertise BPM the
 * band would call BELOW or ABOVE. Zone 1 and Zone 5 chart open-ended, but they don't *aim*
 * open-ended: every BPM range this app shows is a target, so a closed range is the only kind.
 */
fun targetRangeLabel(zone: HrZone, profile: HrProfile): String =
    "${zoneLowerBpm(zone, profile)}-${zoneUpperBpm(zone, profile)}"
