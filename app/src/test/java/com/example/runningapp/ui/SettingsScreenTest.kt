package com.example.runningapp.ui

import com.example.runningapp.RESTING_HR_UNSTATED
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
    fun `the max hr refusal names the resting number when that is what raised the floor`() {
        // A range that had silently tightened would read as the app changing its mind, and leaves
        // the runner with nothing to act on. Naming the resting number points at what to change.
        assertEquals(
            "Enter a heart rate between 110 and 230 — anything lower leaves no room above " +
                "your resting 60",
            maxHrRefusalText(restingHr = 60)
        )
    }

    @Test
    fun `the max hr refusal stays the plain range when nothing is stated above it`() {
        assertEquals("Enter a heart rate between 100 and 230", maxHrRefusalText(RESTING_HR_UNSTATED))
        // A resting heart rate low enough to constrain nothing reads the same way.
        assertEquals("Enter a heart rate between 100 and 230", maxHrRefusalText(restingHr = 30))
    }

    @Test
    fun `an active strap missing from the saved list still reads as a strap`() {
        // Shouldn't happen, but an address with no matching name must not render as blank.
        val settings = UserSettings(activeDeviceAddress = "AA:BB")

        assertEquals("Saved strap · Connecting", strapRowSummary(settings, "Connecting"))
    }
}
