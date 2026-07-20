package com.example.runningapp

/** What the cue ladder decides on a single heart-rate sample. */
enum class CueAction {
    /** Say nothing. */
    SILENT,

    /** An out-of-target cue is due; the caller picks the words from the band. */
    SPEAK,

    /** Back in target after having spoken while out — the closing bracket of a spoken cue. */
    RETURN
}

/**
 * The one clock for sustained zone cues (#108).
 *
 * Out of target it speaks on a fixed ladder — first at 30s, again at 60s, then at most once every
 * 5 minutes for as long as you stay out — and never faster, however long that is. This replaces an
 * app that, once you had been out for 30 seconds, nagged every 75 seconds forever with no ceiling.
 *
 * Back in target it speaks once — but only if it had actually spoken while you were out (never
 * announce a return from somewhere you were never told you had gone) — and resets to the top.
 *
 * The ladder holds no notion of zones or heart rate. The caller resolves those into a [ZoneBand]
 * with midpoint hysteresis (see [bandWithHysteresis], so a heart rate hovering on the edge can't
 * farm return cues) and an [awake] flag — false during warm-up, walk and cool-down steps, and for
 * an unplanned run's first five minutes. When not awake the ladder resets, so every run step, and
 * the moment the grace lifts, starts silent rather than firing a burst of overdue cues.
 */
class CueLadder(
    private val firstCueMs: Long = 30_000L,
    private val secondCueMs: Long = 60_000L,
    private val repeatMs: Long = 5 * 60_000L
) {
    private var outSince: Long? = null
    private var cuesSpoken = 0

    fun reset() {
        outSince = null
        cuesSpoken = 0
    }

    /** ms after leaving target at which the next (the [cuesSpoken]-th) cue falls due. */
    private fun nextCueOffsetMs(): Long = when (cuesSpoken) {
        0 -> firstCueMs
        1 -> secondCueMs
        else -> secondCueMs + (cuesSpoken - 1) * repeatMs
    }

    fun onSample(now: Long, band: ZoneBand, awake: Boolean): CueAction {
        if (!awake) {
            reset()
            return CueAction.SILENT
        }
        return when (band) {
            ZoneBand.ABOVE, ZoneBand.BELOW -> {
                val since = outSince ?: now.also { outSince = it }
                if (now - since >= nextCueOffsetMs()) {
                    cuesSpoken += 1
                    CueAction.SPEAK
                } else {
                    CueAction.SILENT
                }
            }
            ZoneBand.IN -> {
                val spoke = cuesSpoken > 0
                reset()
                if (spoke) CueAction.RETURN else CueAction.SILENT
            }
            ZoneBand.UNKNOWN -> CueAction.SILENT
        }
    }

    /** For the debug overlay only: seconds continuously out of target, 0 when in. */
    fun secondsOutOfTarget(now: Long): Long =
        outSince?.let { (now - it).coerceAtLeast(0) / 1000 } ?: 0

    /** For the debug overlay only: seconds until the next cue is due, 0 when in target. */
    fun secondsUntilNextCue(now: Long): Long =
        outSince?.let { ((it + nextCueOffsetMs()) - now).coerceAtLeast(0) / 1000 } ?: 0
}
