package com.example.runningapp.ui

import com.example.runningapp.MAX_MAX_HR
import com.example.runningapp.RESTING_HR_UNSTATED
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the one-time Max HR card says about the runner's own evidence when it has nothing to offer
 * them (#65, #103, #280).
 *
 * The card offers the highest heart rate ever recorded, and only where the field beneath it would
 * accept that number. There are two quite different reasons it has nothing to offer, and a sentence
 * that tells the truth about one of them tells a lie about the other — a runner whose history is
 * full of beats being told none were ever recorded.
 */
class MaxHrCardTest {

    @Test
    fun `a phone that has recorded nothing is told exactly that`() {
        val text = maxHrEvidenceText(highestRecordedBpm = null, restingHr = 60)

        assertTrue(text, text.contains("not recorded a heart rate from you yet"))
        assertTrue(text, text.contains("Your age"))
    }

    @Test
    fun `a peak with too little room above a stated resting rate says so, with both numbers`() {
        // Reachable without staging anything: a resting heart rate of 100 — the highest the app
        // accepts — rules out every recorded peak under 150.
        val text = maxHrEvidenceText(highestRecordedBpm = 145, restingHr = 100)

        assertFalse(text, text.contains("not recorded"))
        assertTrue(text, text.contains("145"))
        // "No room" would be a lie about this very case: 145 over 100 leaves 45 BPM, and the
        // rule wants 50. The sentence has to be true of the number printed beside it.
        assertFalse(text, text.contains("no room"))
        assertTrue(text, text.contains("too little room above your resting 100"))
        // And the age question survives, because it is still the right question here.
        assertTrue(text, text.contains("Your age"))
    }

    @Test
    fun `a peak below a stated resting rate is not called close to it`() {
        // A strap that only ever recorded a resting reading. "Too close to your resting 100" would
        // be false of 60 — it is under that number, not near it — so what is said is the thing that
        // is true of every peak this branch catches: the room left above is too little.
        val text = maxHrEvidenceText(highestRecordedBpm = 60, restingHr = 100)

        assertFalse(text, text.contains("too close"))
        assertTrue(text, text.contains("60"))
        assertTrue(text, text.contains("too little room above your resting 100"))
    }

    @Test
    fun `a peak above the settable range is called unsettable, not a misread`() {
        // 235 is a believable beat: HIGHEST_BELIEVABLE_BPM accepts up to 250 on purpose, because
        // a real heart can pass its owner's stated maximum, and this peak survived the spike
        // guard. Calling it a strap misreading states a fault the app has not established.
        val text = maxHrEvidenceText(highestRecordedBpm = MAX_MAX_HR + 5, restingHr = 60)

        assertFalse(text, text.contains("not recorded"))
        assertTrue(text, text.contains("${MAX_MAX_HR + 5}"))
        assertFalse(text, text.contains("misread"))
        assertTrue(text, text.contains("above the highest number this app takes as a maximum"))
        assertTrue(text, text.contains("Your age"))
    }

    @Test
    fun `a peak under the settable floor does not blame a resting rate nobody stated`() {
        // The floor is 100 on its own account here. Naming a resting heart rate of 0 would be the
        // card explaining itself with a number the runner has never seen.
        val text = maxHrEvidenceText(highestRecordedBpm = 95, restingHr = RESTING_HR_UNSTATED)

        assertTrue(text, text.contains("95"))
        assertTrue(text, text.contains("too low"))
        assertFalse(text, text.contains("resting"))
    }

    @Test
    fun `a peak the field would accept is never explained away as unusable`() {
        // Unreachable from the card, which offers such a peak rather than explaining it. Pinned
        // because the sentences above are all about why a number cannot be used, and printing one
        // of them over a perfectly good peak is the failure this test would catch.
        val text = maxHrEvidenceText(highestRecordedBpm = 181, restingHr = 60)

        assertTrue(text, text.contains("181"))
        assertFalse(text, text.contains("too low"))
        assertFalse(text, text.contains("above the highest"))
        assertFalse(text, text.contains("too little room"))
    }
}
