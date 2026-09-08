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
    fun `a peak too close to a stated resting rate says so, with both numbers`() {
        // Reachable without staging anything: a resting heart rate of 100 — the highest the app
        // accepts — rules out every recorded peak under 150.
        val text = maxHrEvidenceText(highestRecordedBpm = 145, restingHr = 100)

        assertFalse(text, text.contains("not recorded"))
        assertTrue(text, text.contains("145"))
        assertTrue(text, text.contains("100"))
        // And the age question survives, because it is still the right question here.
        assertTrue(text, text.contains("Your age"))
    }

    @Test
    fun `a strap artefact above the settable range is called too high, not missing`() {
        val text = maxHrEvidenceText(highestRecordedBpm = MAX_MAX_HR + 5, restingHr = 60)

        assertFalse(text, text.contains("not recorded"))
        assertTrue(text, text.contains("${MAX_MAX_HR + 5}"))
        assertTrue(text, text.contains("too high"))
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

        assertFalse(text, text.contains("too low"))
        assertFalse(text, text.contains("too high"))
        assertFalse(text, text.contains("too close"))
    }
}
