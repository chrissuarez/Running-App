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
 *
 * Each rung is timed from the previous cue, not from the moment you left target. Under a steady
 * 1 Hz sample stream that is the same thing, but it matters when samples pause mid-run (a BLE
 * dropout that keeps the run active): when packets resume after a long gap the ladder speaks one
 * catch-up cue and then spaces out again, instead of firing every overdue rung back-to-back.
 */
class CueLadder(
    firstCueMs: Long = CueLadderRungs.DEFAULT.firstCueMs,
    secondCueMs: Long = CueLadderRungs.DEFAULT.secondCueMs,
    repeatMs: Long = CueLadderRungs.DEFAULT.repeatMs
) {
    private val rungs = CueLadderRungs(firstCueMs, secondCueMs, repeatMs)
    private var state = CueLadderState()

    fun reset() {
        state = CueLadderState()
    }

    fun onSample(now: Long, band: ZoneBand, awake: Boolean): CueAction {
        val step = state.onSample(now, band, awake, rungs)
        state = step.ladder
        return step.action
    }

    /** For the debug overlay only: seconds continuously out of target, 0 when in. */
    fun secondsOutOfTarget(now: Long): Long = state.secondsOutOfTarget(now)

    /** For the debug overlay only: seconds until the next cue is due, 0 when in target. */
    fun secondsUntilNextCue(now: Long): Long = state.secondsUntilNextCue(now, rungs)
}

/**
 * How far apart the rungs are: 30s to the first cue, 60s to the second, then one every 5 minutes.
 *
 * Separate from the ladder's position on them so the position can be a value the caller holds.
 */
data class CueLadderRungs(
    val firstCueMs: Long = 30_000L,
    val secondCueMs: Long = 60_000L,
    val repeatMs: Long = 5 * 60_000L
) {
    /**
     * ms that must elapse since the anchor — the last spoken cue, or leaving target before the
     * first cue — for the next rung to fall due.
     */
    fun nextIntervalMs(cuesSpoken: Int): Long = when (cuesSpoken) {
        0 -> firstCueMs
        1 -> secondCueMs - firstCueMs
        else -> repeatMs
    }

    companion object {
        val DEFAULT = CueLadderRungs()
    }
}

/** Where the ladder currently stands, and what one sample does to it. */
data class CueLadderStep(val ladder: CueLadderState, val action: CueAction)

/**
 * The ladder's whole position, as a value.
 *
 * [CueLadder] is a mutable holder of one of these, kept for the service, which owns the ladder as a
 * field. The Run cannot: it is a rulebook with no mutable field of its own (ADR 0002), so it holds
 * this in its state and takes the [CueLadderStep] back. The rules are here, once, and both callers
 * get exactly the same ones.
 */
data class CueLadderState(
    val outSince: Long? = null,
    val lastCueTime: Long? = null,
    val cuesSpoken: Int = 0
) {
    fun onSample(
        now: Long,
        band: ZoneBand,
        awake: Boolean,
        rungs: CueLadderRungs = CueLadderRungs.DEFAULT
    ): CueLadderStep {
        if (!awake) return CueLadderStep(CueLadderState(), CueAction.SILENT)
        return when (band) {
            ZoneBand.ABOVE, ZoneBand.BELOW -> {
                val out = outSince ?: now
                val anchor = lastCueTime ?: out
                if (now - anchor >= rungs.nextIntervalMs(cuesSpoken)) {
                    CueLadderStep(
                        copy(outSince = out, lastCueTime = now, cuesSpoken = cuesSpoken + 1),
                        CueAction.SPEAK
                    )
                } else {
                    CueLadderStep(copy(outSince = out), CueAction.SILENT)
                }
            }
            ZoneBand.IN -> CueLadderStep(
                CueLadderState(),
                if (cuesSpoken > 0) CueAction.RETURN else CueAction.SILENT
            )
            ZoneBand.UNKNOWN -> CueLadderStep(this, CueAction.SILENT)
        }
    }

    fun secondsOutOfTarget(now: Long): Long =
        outSince?.let { (now - it).coerceAtLeast(0) / 1000 } ?: 0

    fun secondsUntilNextCue(now: Long, rungs: CueLadderRungs = CueLadderRungs.DEFAULT): Long {
        val anchor = lastCueTime ?: outSince ?: return 0
        return ((anchor + rungs.nextIntervalMs(cuesSpoken)) - now).coerceAtLeast(0) / 1000
    }
}
