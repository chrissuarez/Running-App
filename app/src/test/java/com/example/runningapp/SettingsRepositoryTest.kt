package com.example.runningapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun `testing mode blocks turning AI sharing back on`() {
        // The failure this prevents is delayed, not immediate: recording already ignores sharing
        // while testing mode is on, so a stored `true` does nothing until testing mode goes off —
        // at which point sharing resumes off a tap made while it was suppressed.
        assertFalse(aiSharingChangeAllowed(enabled = true, testingModeEnabled = true))
    }

    @Test
    fun `withdrawing consent is allowed in every state`() {
        assertTrue(aiSharingChangeAllowed(enabled = false, testingModeEnabled = true))
        assertTrue(aiSharingChangeAllowed(enabled = false, testingModeEnabled = false))
    }

    @Test
    fun `with testing mode off the setting is the runner's to make`() {
        assertTrue(aiSharingChangeAllowed(enabled = true, testingModeEnabled = false))
    }

    @Test
    fun `a Max HR chosen before the flag existed still counts as deliberately set`() {
        // Upgrading from a build with a Save button: they typed their number, the flag didn't
        // exist to record it. Reading that as "never set" would let their next edit rewrite
        // history already recorded against the number they chose.
        assertTrue(maxHrEverSet(flag = null, storedMaxHr = 180))
    }

    @Test
    fun `the stored placeholder is not evidence of anything`() {
        // The old Save wrote Max HR on every save, touched or not, so the key's presence means
        // nothing — only a value differing from the placeholder does.
        assertFalse(maxHrEverSet(flag = null, storedMaxHr = DEFAULT_MAX_HR))
        assertFalse(maxHrEverSet(flag = null, storedMaxHr = null))
    }

    @Test
    fun `the recorded flag outranks the inference in both directions`() {
        assertTrue(maxHrEverSet(flag = true, storedMaxHr = DEFAULT_MAX_HR))
        assertFalse(maxHrEverSet(flag = false, storedMaxHr = 180))
    }
}
