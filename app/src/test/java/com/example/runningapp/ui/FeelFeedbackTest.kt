package com.example.runningapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saying afterwards how a Run felt, from the Run's own page (#80).
 *
 * What is tested here is the rule the dialog applies before the repository sees anything: what a
 * typed note amounts to, and whether there is a change worth saving at all.
 */
class FeelFeedbackTest {

    @Test
    fun `a typed note is what is left after the spaces`() {
        assertEquals("Felt strong", feelNoteOf("  Felt strong  "))
    }

    @Test
    fun `a note of nothing but spaces is no note`() {
        // Not a blank string stored on the Run: a note nobody wrote and a note emptied are the
        // same absence, and the page has one way of showing it.
        assertNull(feelNoteOf(""))
        assertNull(feelNoteOf("   \n "))
    }

    @Test
    fun `nothing changed is nothing to save`() {
        assertFalse(
            feelEditHasChanges(
                storedEffort = 7,
                storedNote = "Felt strong",
                effort = 7,
                typedNote = "Felt strong",
                storedIsWalk = false,
                isWalk = false,
            )
        )
    }

    @Test
    fun `re-typing the same note around different spaces is not a change`() {
        assertFalse(
            feelEditHasChanges(
                storedEffort = null,
                storedNote = "Felt strong",
                effort = null,
                typedNote = "  Felt strong ",
                storedIsWalk = false,
                isWalk = false,
            )
        )
    }

    @Test
    fun `emptying a note is a change`() {
        // The one that matters: clearing has to reach the repository, or a note can be written and
        // never taken back.
        assertTrue(
            feelEditHasChanges(
                storedEffort = 7,
                storedNote = "Felt strong",
                effort = 7,
                typedNote = "",
                storedIsWalk = false,
                isWalk = false,
            )
        )
    }

    @Test
    fun `a first effort on a Run that had none is a change`() {
        assertTrue(
            feelEditHasChanges(
                storedEffort = null,
                storedNote = null,
                effort = 5,
                typedNote = "",
                storedIsWalk = false,
                isWalk = false,
            )
        )
    }

    @Test
    fun `a Run with nothing said about it and an untouched dialog has nothing to save`() {
        assertFalse(
            feelEditHasChanges(
                storedEffort = null,
                storedNote = null,
                effort = null,
                typedNote = "",
                storedIsWalk = false,
                isWalk = false,
            )
        )
    }

    @Test
    fun `the way in says whether there is anything there yet`() {
        assertEquals("Add effort / note", feelEditLabel(effort = null, note = null, isWalk = false))
        assertEquals("Edit effort / note", feelEditLabel(effort = 6, note = null, isWalk = false))
        assertEquals("Edit effort / note", feelEditLabel(effort = null, note = "Felt strong", isWalk = false))
        // A note of nothing but spaces is nothing said, so the way in still says "Add".
        assertEquals("Add effort / note", feelEditLabel(effort = null, note = "   ", isWalk = false))
        // A Walk with nothing else said about it has still had something said about it (#275).
        assertEquals("Edit effort / note", feelEditLabel(effort = null, note = null, isWalk = true))
    }

    @Test
    fun `marking a Run a Walk, or unmarking it, is a change`() {
        assertTrue(
            feelEditHasChanges(
                storedEffort = null,
                storedNote = null,
                effort = null,
                typedNote = "",
                storedIsWalk = false,
                isWalk = true,
            )
        )
        assertTrue(
            feelEditHasChanges(
                storedEffort = 7,
                storedNote = "Felt strong",
                effort = 7,
                typedNote = "Felt strong",
                storedIsWalk = true,
                isWalk = false,
            )
        )
    }
}
