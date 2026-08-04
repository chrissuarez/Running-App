package com.example.runningapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the number off a treadmill console into the app (#231).
 *
 * What is being tested is the one rule the field applies before the repository ever sees it: this is
 * a distance, or it is nothing.
 */
class StatedDistanceTest {

    @Test
    fun `a typed number is kilometres`() {
        assertEquals(5.2, statedDistanceKmOf("5.2")!!, 0.0001)
        assertEquals(12.0, statedDistanceKmOf(" 12 ")!!, 0.0001)
    }

    @Test
    fun `a comma is a decimal point`() {
        // Half the world's keyboards put one there, and a runner typing 5,2 means five point two.
        assertEquals(5.2, statedDistanceKmOf("5,2")!!, 0.0001)
    }

    @Test
    fun `nothing typed is not a distance`() {
        assertNull(statedDistanceKmOf(""))
        assertNull(statedDistanceKmOf("   "))
    }

    @Test
    fun `a number that is not a distance is not one`() {
        // Zero is what a Run nobody stated a distance for already carries, so typing it says
        // nothing; the rest are not numbers at all.
        assertNull(statedDistanceKmOf("0"))
        assertNull(statedDistanceKmOf("-4"))
        assertNull(statedDistanceKmOf("five"))
        assertNull(statedDistanceKmOf("5.2.1"))
    }

    @Test
    fun `something typed that is not a distance is rejected rather than ignored`() {
        // What holds Save shut at the finish, so an unreadable number is never dropped silently.
        assertTrue(statedDistanceIsRejected("0"))
        assertTrue(statedDistanceIsRejected("-4"))
        assertTrue(statedDistanceIsRejected("five"))
        assertTrue(statedDistanceIsRejected("5.2.1"))
    }

    @Test
    fun `saying nothing is not a rejection`() {
        // A blank field is a runner who did not state a distance, which is allowed everywhere.
        assertFalse(statedDistanceIsRejected(""))
        assertFalse(statedDistanceIsRejected("   "))
        assertFalse(statedDistanceIsRejected("5,2"))
    }

    @Test
    fun `a distance already stated is shown back to be corrected`() {
        assertEquals("5.20", statedDistanceFieldText(5.2))
        // A Run with none starts the field empty rather than at a zero to be deleted first.
        assertEquals("", statedDistanceFieldText(0.0))
    }
}
