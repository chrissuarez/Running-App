package com.example.runningapp.ui

import com.example.runningapp.MAX_MAX_HR
import com.example.runningapp.MIN_MAX_HR
import com.example.runningapp.RESTING_HR_UNSTATED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the Max HR confirmation card offers, and what it refuses to offer (#65, #103).
 *
 * The rules and not the drawing: what makes this card right is that it never suggests a number the
 * field beneath it would refuse, and never dresses up a population formula as the runner's own
 * evidence.
 */
class MaxHrCardTest {

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
