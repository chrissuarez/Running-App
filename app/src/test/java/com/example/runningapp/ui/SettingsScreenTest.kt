package com.example.runningapp.ui

import com.example.runningapp.SavedDevice
import com.example.runningapp.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsScreenTest {

    @Test
    fun `the strap row names the active strap and what it is doing`() {
        val settings = UserSettings(
            savedDevices = listOf(
                SavedDevice(address = "AA:BB", name = "Polar H10"),
                SavedDevice(address = "CC:DD", name = "Old strap")
            ),
            activeDeviceAddress = "AA:BB"
        )

        assertEquals("Polar H10 · Connected", strapRowSummary(settings, "Connected"))
        assertEquals("Polar H10 · Disconnected", strapRowSummary(settings, "Disconnected"))
    }

    @Test
    fun `with no strap chosen the row says so rather than naming a status`() {
        assertEquals("No strap paired", strapRowSummary(UserSettings(), "Disconnected"))
    }

    @Test
    fun `an active strap missing from the saved list still reads as a strap`() {
        // Shouldn't happen, but an address with no matching name must not render as blank.
        val settings = UserSettings(activeDeviceAddress = "AA:BB")

        assertEquals("Saved strap · Connecting", strapRowSummary(settings, "Connecting"))
    }
}
