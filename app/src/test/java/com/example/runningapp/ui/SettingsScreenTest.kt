package com.example.runningapp.ui

import com.example.runningapp.RESTING_HR_UNSTATED
import com.example.runningapp.parseMaxHr
import com.example.runningapp.parseMaxHrAlone
import com.example.runningapp.parseRestingHr
import com.example.runningapp.parseRestingHrAlone
import com.example.runningapp.SavedDevice
import com.example.runningapp.UserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // --- What leaving states (#172) ---

    private fun maxHrField(stored: Int = 190) =
        HrFieldState(stored, parse = { parseMaxHr(it, restingHr = 60) })

    private fun restingHrField(stored: Int = 60) = HrFieldState(
        stored,
        parse = { parseRestingHr(it, maxHr = 190) },
        blankMeans = RESTING_HR_UNSTATED
    )

    @Test
    fun `both pending numbers leave as one statement`() {
        // Two commits were two coroutines racing for the repository's lock, and the same two edits
        // left different history depending on which won.
        val maxHr = maxHrField().apply { onTyped("181") }
        val resting = restingHrField().apply { onTyped("55") }

        val statement = hrStatementOnLeave(maxHr, resting)

        assertEquals(HrStatement(181, 55, withdrawingRestingHr = false, mayLeave = true), statement)
    }

    @Test
    fun `a refused entry in one field does not drop what was typed in the other`() {
        // An `&&` would short-circuit past the second field and lose it — the silent discard this
        // screen exists to delete, moved one gesture along.
        val maxHr = maxHrField().apply { onTyped("60") } // impossible as a maximum
        val resting = restingHrField().apply { onTyped("55") }

        val statement = hrStatementOnLeave(maxHr, resting)

        assertEquals(55, statement.restingHr)
        assertNull(statement.maxHr)
        assertEquals(false, statement.mayLeave)
        assertEquals(true, maxHr.refused)
    }

    @Test
    fun `an emptied resting field is a question, and takes any max hr with it`() {
        // Withdrawing a measurement moves every zone edge and re-bands all of history, so it is
        // asked about — and the Max HR typed beside it belongs to the same statement.
        val maxHr = maxHrField().apply { onTyped("181") }
        val resting = restingHrField().apply { onTyped("") }

        val statement = hrStatementOnLeave(maxHr, resting)

        assertEquals(true, statement.withdrawingRestingHr)
        assertEquals(181, statement.maxHr)
        assertNull(statement.restingHr)
    }

    @Test
    fun `leaving without touching either field states nothing`() {
        val statement = hrStatementOnLeave(maxHrField(), restingHrField())

        assertEquals(false, statement.statesSomething)
        assertEquals(true, statement.mayLeave)
    }

    // --- What each field puts in force for the other (#172) ---

    @Test
    fun `a resting hr already blurred still blocks a max hr with no room above it`() {
        // Blur commits, but the commit is asynchronous and waits on a re-tally of the whole
        // history before it publishes — so storage still reads the old number when the maximum is
        // typed a moment later. Judged against disk this pair is accepted and one of the two
        // numbers is then quietly rewritten. What the field is holding is the honest answer.
        val restingInForce = hrInForce("60", stored = RESTING_HR_UNSTATED, ::parseRestingHrAlone)

        assertEquals(60, restingInForce)
        assertNull(parseMaxHr("100", restingInForce))
    }

    @Test
    fun `a half-typed entry leaves the number still stored in force`() {
        // "1" on the way to "100" is not a maximum of 1, and the field beside it must not be
        // re-judged against one.
        assertEquals(190, hrInForce("1", stored = 190, ::parseMaxHrAlone))
        assertEquals(100, hrInForce("100", stored = 190, ::parseMaxHrAlone))
    }

    @Test
    fun `a blank resting field holds the stated number in force until the clear is answered`() {
        // Emptying it is a parked question, not an answer. Reading the blank as "unstated" would
        // let a lower Max HR through on the strength of a clear the runner may still decline —
        // and declining would then find the number already rewritten underneath them.
        assertEquals(60, hrInForce("", stored = 60, ::parseRestingHrAlone))
        assertNull(parseMaxHr("100", hrInForce("", stored = 60, ::parseRestingHrAlone)))
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
    fun `the resting hr refusal names the maximum when that is what lowered the ceiling`() {
        // The mirror of the Max HR message, and it has to stay one: a refusal built by a named
        // rule on one door and spelled out inline on the other is how the two drift apart.
        assertEquals(
            "Enter a heart rate between 30 and 50 — anything higher leaves no room under your " +
                "Max HR of 100",
            restingHrRefusalText(maxHr = 100)
        )
        // A maximum high enough to constrain nothing reads as the plain range.
        assertEquals("Enter a heart rate between 30 and 100", restingHrRefusalText(maxHr = 190))
    }

    @Test
    fun `an active strap missing from the saved list still reads as a strap`() {
        // Shouldn't happen, but an address with no matching name must not render as blank.
        val settings = UserSettings(activeDeviceAddress = "AA:BB")

        assertEquals("Saved strap · Connecting", strapRowSummary(settings, "Connecting"))
    }
}
