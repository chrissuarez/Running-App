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
}
