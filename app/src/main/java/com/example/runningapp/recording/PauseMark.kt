package com.example.runningapp.recording

/**
 * Which stored fix is the far side of a Pause.
 *
 * A Pause leaves nothing else behind: GPS is torn down for the length of one, so the fixes that
 * would have described it either never arrive or are never stored. The only record is a mark on the
 * fix that resumed the Run ([com.example.runningapp.data.TrackPoint.startsAfterPause]), and this is
 * where that mark is decided — one bit, four rules, and no Android in sight so all four can be
 * tested (#195).
 *
 * The rule that is easy to miss is [runBegan]. The recorder is reused between Runs and every Run
 * ends by tearing it down, which sets the mark exactly as a Pause would; without a Run saying where
 * it begins, every Run's opening fix would claim a Pause preceded it. Saying so at the Run's
 * beginning — rather than second-guessing the mark at the moment a fix is written — is what leaves
 * room for the one case that used to be thrown away with it: a Pause taken *before* the first fix
 * lands, which is a real Pause and belongs on that opening fix.
 *
 * An opening fix carrying the mark is inert for every reader that measures between fixes: they
 * reach it through consecutive pairs starting at the second point, so none of them reads the first.
 * The rescue of an interrupted Run
 * ([com.example.runningapp.data.measureTrackRecordedSeconds]) is the one place it is deliberately
 * read, and refusing to credit the wait from START as running is the whole observable effect of it.
 *
 * Holds its own lock rather than borrowing its caller's. A Run's beginning is announced on the
 * thread performing the Run's effects and the mark is read on the location thread, so the two need a
 * barrier between them whoever is calling; a mark that is safe on its own is one nobody has to hold
 * the right lock to use.
 */
class PauseMark {

    private var broken = false

    /** A Run is beginning. Nothing precedes its track, whatever the last Run's teardown left. */
    @Synchronized
    fun runBegan() {
        broken = false
    }

    /**
     * The recording has stopped keeping up with the runner — a held-down Pause tearing GPS down, or
     * an auto-pause holding fixes back while the runner stands still.
     */
    @Synchronized
    fun recordingBroke() {
        broken = true
    }

    /**
     * Whether the fix being written now is the far side of a Pause — and, having said so once,
     * forgets it. Only the fix that resumed the Run carries it; the ones after it are ordinary.
     */
    @Synchronized
    fun takeForFix(): Boolean {
        val wasBroken = broken
        broken = false
        return wasBroken
    }
}
