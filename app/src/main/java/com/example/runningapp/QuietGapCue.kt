package com.example.runningapp

/**
 * A cue that waits for the app to stop talking before it is spoken (#208).
 *
 * Speech in this app flushes: every cue cuts off whatever is mid-sentence. Most cues want that —
 * "Start running, interval 4 of 6" is worth more than the tail of the sentence before it. The
 * halfway turnaround is the exception. It lands wherever the arithmetic puts it, possibly on top of
 * an Interval instruction or a Split announcement, and it is the one cue that can afford to be
 * late: a few seconds of drift on a turnaround point is nothing, while losing the cue — or
 * truncating the instruction the runner needed — is the feature failing.
 *
 * So the cue is held until nothing has been said for [quietGapMillis], and spoken regardless once
 * [ceilingMillis] have passed. The ceiling is what makes this safe rather than hopeful: a run that
 * never falls quiet still gets its turnaround.
 *
 * This lives with the cue player rather than in the Run because it is a question about the speech
 * layer's state, and the Run is a rulebook that cannot ask (ADR 0002). It holds no clock of its own
 * either — every time is passed in — so the whole of it is testable on a fake one.
 *
 * Unlike the Run, it is genuinely touched by several threads: the Run's thread holds a cue, the
 * text-to-speech engine's callback thread reports speech ending, and the poll asking whether it may
 * go runs on another again. Every method is therefore synchronized on the instance — the whole
 * class is four fields and no work, so there is nothing to contend over.
 *
 * A contained stand-in. #53 replaces it with a real priority queue over every cue; delete this then.
 */
class QuietGapCue(
    private val quietGapMillis: Long = QUIET_GAP_MILLIS,
    private val ceilingMillis: Long = CEILING_MILLIS,
) {
    private var pendingText: String? = null
    private var speakByMillis: Long = 0

    /**
     * Whether a cue is being spoken right now, and when speech was last heard — both fed from the
     * cue player, so they see *every* cue, including the Split announcements that reach the speaker
     * without passing through the Run.
     */
    private var speaking = false
    private var lastHeardMillis: Long? = null

    /** Speech started or finished. Anything that reaches the speaker reports here. */
    @Synchronized
    fun speechChanged(speaking: Boolean, nowMillis: Long) {
        this.speaking = speaking
        lastHeardMillis = nowMillis
    }

    /** Hold [text] for the next gap, and start its ceiling running. */
    @Synchronized
    fun hold(text: String, nowMillis: Long) {
        pendingText = text
        speakByMillis = nowMillis + ceilingMillis
    }

    /**
     * The cue to speak now, or null to keep waiting. Asked repeatedly — by a poll, in the service —
     * and it answers at most once per [hold].
     */
    @Synchronized
    fun release(nowMillis: Long): String? {
        val text = pendingText ?: return null
        if (!isQuiet(nowMillis) && nowMillis < speakByMillis) return null
        pendingText = null
        return text
    }

    /** Drop whatever is held: the Run it belonged to is over. */
    @Synchronized
    fun forget() {
        pendingText = null
    }

    private fun isQuiet(nowMillis: Long): Boolean {
        if (speaking) return false
        val lastHeard = lastHeardMillis ?: return true
        return nowMillis - lastHeard >= quietGapMillis
    }

    companion object {
        /** Long enough to read as a gap between cues rather than a pause inside one. */
        const val QUIET_GAP_MILLIS = 3_000L

        /** Speak anyway after this long. Roughly fifteen seconds, as #208 asks for. */
        const val CEILING_MILLIS = 15_000L

        /** How often the held cue asks whether it may go. Fine enough not to add audible delay. */
        const val POLL_MILLIS = 250L
    }
}
