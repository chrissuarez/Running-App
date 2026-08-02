package com.example.runningapp.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ACQ_T0 = 1_000_000L
private const val STRAP = "AA:BB:CC:DD:EE:FF"
private const val OTHER = "11:22:33:44:55:66"

private fun ctx(
    now: Long = ACQ_T0,
    runIsLive: Boolean = false,
    canScan: Boolean = true,
    canConnect: Boolean = true,
    bluetoothOn: Boolean = true,
) = AcquisitionContext(now, runIsLive, canScan, canConnect, bluetoothOn)

/** Drive a sequence of events through, so a whole journey reads as one list. */
private fun run(
    start: AcquisitionState = AcquisitionState(),
    context: AcquisitionContext = ctx(),
    vararg events: AcquisitionEvent,
): AcquisitionState {
    var state = start
    events.forEach { state = Acquisition.decide(state, it, context).state }
    return state
}

private fun connectedTo(address: String, name: String = "Polar H10", promote: Boolean = false) =
    AcquisitionState(AcquisitionPhase.Connected(address, name, promote))

class AcquisitionStatusLineTest {

    // "Connected" is the one sentence that reaches hr_samples.connectionState, and it has been
    // written there since the first recorded run — a change to it makes every old row disagree with
    // every new one. The outage sentences never reach a row (a no-reading second emits none, #110
    // and #115); they are pinned here because the screen and the Run read them, not the database.

    @Test
    fun `idle reads as disconnected`() {
        assertEquals("Disconnected", AcquisitionState().statusLine)
    }

    @Test
    fun `scanning reads as it always did`() {
        assertEquals("Scanning...", AcquisitionState(AcquisitionPhase.Scanning(ACQ_T0)).statusLine)
    }

    @Test
    fun `connecting names the strap`() {
        val state = AcquisitionState(
            AcquisitionPhase.Connecting(STRAP, "Polar H10", false, 0, FIRST_RETRY_DELAY_MS),
        )
        assertEquals("Connecting to Polar H10...", state.statusLine)
    }

    @Test
    fun `retrying announces the wait in whole seconds`() {
        val state = AcquisitionState(
            AcquisitionPhase.Retrying(STRAP, "Polar H10", false, 1, ACQ_T0 + 6_000, 6_000, 12_000),
        )
        assertEquals("Reconnecting in 6s...", state.statusLine)
    }

    @Test
    fun `giving up reads as strap not found`() {
        assertEquals("Strap not found", AcquisitionState(AcquisitionPhase.GaveUp).statusLine)
    }

    @Test
    fun `each block has its own sentence`() {
        fun line(reason: AcquisitionBlock) =
            AcquisitionState(AcquisitionPhase.Blocked(reason)).statusLine
        assertEquals("Permission Missing", line(AcquisitionBlock.PermissionMissing))
        assertEquals("Bluetooth Off/Unavailable", line(AcquisitionBlock.BluetoothUnavailable))
        assertEquals("Scan Failed: 2", line(AcquisitionBlock.ScanFailed(2)))
    }
}

class AcquisitionInFlightTest {

    @Test
    fun `scanning connecting and retrying are in flight`() {
        assertTrue(AcquisitionState(AcquisitionPhase.Scanning(ACQ_T0)).inFlight)
        assertTrue(
            AcquisitionState(
                AcquisitionPhase.Connecting(STRAP, "s", false, 0, FIRST_RETRY_DELAY_MS),
            ).inFlight,
        )
        assertTrue(
            AcquisitionState(
                AcquisitionPhase.Retrying(STRAP, "s", false, 1, ACQ_T0, 3_000, 6_000),
            ).inFlight,
        )
    }

    @Test
    fun `the three terminal phases are not in flight`() {
        assertFalse(connectedTo(STRAP).inFlight)
        assertFalse(AcquisitionState(AcquisitionPhase.GaveUp).inFlight)
        assertFalse(
            AcquisitionState(
                AcquisitionPhase.Blocked(AcquisitionBlock.PermissionMissing),
            ).inFlight,
        )
    }

    @Test
    fun `idle is not in flight`() {
        assertFalse(AcquisitionState().inFlight)
    }

    @Test
    fun `giving up is not the same as idle`() {
        // The record screen's auto-connect fires on Idle. If these were one phase, giving up would
        // start the same doomed chase again immediately.
        assertTrue(AcquisitionState(AcquisitionPhase.GaveUp).phase != AcquisitionPhase.Idle)
    }
}

class AcquisitionCoversRunStartTest {

    @Test
    fun `a connected active strap covers the start`() {
        assertTrue(connectedTo(STRAP).coversRunStart(activeAddress = STRAP))
    }

    @Test
    fun `a connected strap that is not the active one does not`() {
        // Set Active writes only the settings and leaves the old GATT up, so START must re-acquire.
        assertFalse(connectedTo(OTHER).coversRunStart(activeAddress = STRAP))
    }

    @Test
    fun `with no active strap saved whatever is connected is the sensor`() {
        assertTrue(connectedTo(OTHER).coversRunStart(activeAddress = null))
    }

    @Test
    fun `connecting and retrying cover the start`() {
        val connecting = AcquisitionState(
            AcquisitionPhase.Connecting(STRAP, "s", false, 0, FIRST_RETRY_DELAY_MS),
        )
        val retrying = AcquisitionState(
            AcquisitionPhase.Retrying(STRAP, "s", false, 1, ACQ_T0, 3_000, 6_000),
        )
        assertTrue(connecting.coversRunStart(null))
        assertTrue(retrying.coversRunStart(null))
    }

    @Test
    fun `a bare scan does not cover the start`() {
        // Nothing ever auto-connects from a scan, so deferring to one leaves the Run strapless.
        assertFalse(AcquisitionState(AcquisitionPhase.Scanning(ACQ_T0)).coversRunStart(null))
    }

    @Test
    fun `giving up does not cover the start`() {
        assertFalse(AcquisitionState(AcquisitionPhase.GaveUp).coversRunStart(null))
    }
}

class AcquisitionScanTest {

    @Test
    fun `a scan starts and is time-boxed`() {
        val outcome = Acquisition.decide(AcquisitionState(), AcquisitionEvent.ScanRequested(), ctx())
        assertEquals(
            AcquisitionPhase.Scanning(endsAt = ACQ_T0 + SCAN_TIMEOUT_MS),
            outcome.state.phase,
        )
        assertEquals(
            listOf(AcquisitionEffect.StopScan, AcquisitionEffect.StartScan),
            outcome.effects,
        )
    }

    @Test
    fun `a scan abandons the strap being chased`() {
        val chasing = AcquisitionState(
            AcquisitionPhase.Connecting(STRAP, "Polar H10", true, 0, FIRST_RETRY_DELAY_MS),
        )
        val outcome = Acquisition.decide(chasing, AcquisitionEvent.ScanRequested(), ctx())
        assertEquals(
            listOf(
                AcquisitionEffect.StopScan,
                AcquisitionEffect.CloseGatt(STRAP),
                AcquisitionEffect.StartScan,
            ),
            outcome.effects,
        )
    }

    @Test
    fun `a scan is declined while connected`() {
        val connected = connectedTo(STRAP)
        val outcome = Acquisition.decide(connected, AcquisitionEvent.ScanRequested(), ctx())
        assertEquals(connected, outcome.state)
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `a forced scan hangs up on the strap instead of declining`() {
        val connected = connectedTo(STRAP)
        val outcome = Acquisition.decide(
            connected,
            AcquisitionEvent.ScanRequested(force = true),
            ctx(),
        )
        assertEquals(
            AcquisitionPhase.Scanning(endsAt = ACQ_T0 + SCAN_TIMEOUT_MS),
            outcome.state.phase,
        )
        assertEquals(
            listOf(
                AcquisitionEffect.StopScan,
                AcquisitionEffect.DisconnectAndCloseGatt(STRAP),
                AcquisitionEffect.TellRunStrapLost(LOST_DISCONNECTED),
                AcquisitionEffect.StartScan,
            ),
            outcome.effects,
        )
    }

    @Test
    fun `a forced scan over a chase still only lets the gatt go`() {
        val chasing = AcquisitionState(
            AcquisitionPhase.Connecting(STRAP, "Polar H10", true, 0, FIRST_RETRY_DELAY_MS),
        )
        val outcome = Acquisition.decide(
            chasing,
            AcquisitionEvent.ScanRequested(force = true),
            ctx(),
        )
        assertEquals(
            listOf(
                AcquisitionEffect.StopScan,
                AcquisitionEffect.CloseGatt(STRAP),
                AcquisitionEffect.StartScan,
            ),
            outcome.effects,
        )
    }

    @Test
    fun `a fresh scan clears what the last one found`() {
        val withResults = AcquisitionState(scanned = listOf(ScannedStrap(STRAP, "Polar H10")))
        val outcome = Acquisition.decide(withResults, AcquisitionEvent.ScanRequested(), ctx())
        assertTrue(outcome.state.scanned.isEmpty())
    }

    @Test
    fun `no scan permission blocks rather than dead-ends`() {
        // A silent return would leave the Acquisition in flight with nothing to end it, and by
        // ADR 0001 that is a Promotion nobody releases.
        val outcome = Acquisition.decide(
            AcquisitionState(),
            AcquisitionEvent.ScanRequested(),
            ctx(canScan = false),
        )
        assertEquals(
            AcquisitionPhase.Blocked(AcquisitionBlock.PermissionMissing),
            outcome.state.phase,
        )
        assertFalse(outcome.state.inFlight)
    }

    @Test
    fun `blocking mid-scan stops the scan on the way out`() {
        // Blocked is terminal, so nothing ticks again to stop a scan the runner can no longer
        // see. A connect tap after the permission was revoked is the way in.
        val scanning = run(events = arrayOf(AcquisitionEvent.ScanRequested()))
        val outcome = Acquisition.decide(
            scanning,
            AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", true),
            ctx(canConnect = false),
        )
        assertEquals(
            AcquisitionPhase.Blocked(AcquisitionBlock.PermissionMissing),
            outcome.state.phase,
        )
        assertEquals(listOf(AcquisitionEffect.StopScan), outcome.effects)
    }

    @Test
    fun `blocking mid-chase lets the gatt go`() {
        // Blocked remembers no address, and Forget and Disconnect both match on one — so a handle
        // left open here is one nothing can ever reach again, still passing the map's identity
        // check for its address.
        val chasing = AcquisitionState(
            AcquisitionPhase.Connecting(STRAP, "Polar H10", true, 0, FIRST_RETRY_DELAY_MS),
        )
        val outcome = Acquisition.decide(
            chasing,
            AcquisitionEvent.ConnectRequested(OTHER, "Garmin", true),
            ctx(canConnect = false),
        )
        assertEquals(
            listOf(AcquisitionEffect.DisconnectAndCloseGatt(STRAP)),
            outcome.effects,
        )
    }

    @Test
    fun `blocking while connected tells the run the strap is gone`() {
        val outcome = Acquisition.decide(
            connectedTo(STRAP),
            AcquisitionEvent.ScanRequested(force = true),
            ctx(canScan = false),
        )
        assertEquals(
            listOf(
                AcquisitionEffect.DisconnectAndCloseGatt(STRAP),
                AcquisitionEffect.TellRunStrapLost(LOST_DISCONNECTED),
            ),
            outcome.effects,
        )
    }

    @Test
    fun `bluetooth off blocks the scan`() {
        val outcome = Acquisition.decide(
            AcquisitionState(),
            AcquisitionEvent.ScanRequested(),
            ctx(bluetoothOn = false),
        )
        assertEquals(
            AcquisitionPhase.Blocked(AcquisitionBlock.BluetoothUnavailable),
            outcome.state.phase,
        )
    }

    @Test
    fun `the scan stops itself when nothing is chosen`() {
        val scanning = run(events = arrayOf(AcquisitionEvent.ScanRequested()))
        val outcome = Acquisition.decide(
            scanning,
            AcquisitionEvent.Tick,
            ctx(now = ACQ_T0 + SCAN_TIMEOUT_MS),
        )
        assertEquals(AcquisitionPhase.Idle, outcome.state.phase)
        assertEquals(listOf(AcquisitionEffect.StopScan), outcome.effects)
    }

    @Test
    fun `the scan keeps running until its deadline`() {
        val scanning = run(events = arrayOf(AcquisitionEvent.ScanRequested()))
        val outcome = Acquisition.decide(
            scanning,
            AcquisitionEvent.Tick,
            ctx(now = ACQ_T0 + SCAN_TIMEOUT_MS - 1),
        )
        assertEquals(scanning, outcome.state)
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `what a timed-out scan found stays on offer`() {
        val scanning = run(
            events = arrayOf(
                AcquisitionEvent.ScanRequested(),
                AcquisitionEvent.StrapSeen(STRAP, "Polar H10"),
            ),
        )
        val after = Acquisition.decide(
            scanning,
            AcquisitionEvent.Tick,
            ctx(now = ACQ_T0 + SCAN_TIMEOUT_MS),
        ).state
        assertEquals(listOf(ScannedStrap(STRAP, "Polar H10")), after.scanned)
    }

    @Test
    fun `a stopped scan cannot still time out`() {
        // scanEpoch existed for exactly this. Leaving the phase takes the deadline with it.
        val connecting = run(
            events = arrayOf(
                AcquisitionEvent.ScanRequested(),
                AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = true),
            ),
        )
        val outcome = Acquisition.decide(
            connecting,
            AcquisitionEvent.Tick,
            ctx(now = ACQ_T0 + SCAN_TIMEOUT_MS * 10),
        )
        assertEquals(connecting, outcome.state)
        assertTrue(outcome.effects.isEmpty())
    }
}

class AcquisitionScanResultsTest {

    private val scanning = run(events = arrayOf(AcquisitionEvent.ScanRequested()))

    @Test
    fun `a named strap is listed`() {
        val after = Acquisition.decide(
            scanning,
            AcquisitionEvent.StrapSeen(STRAP, "Polar H10"),
            ctx(),
        ).state
        assertEquals(listOf(ScannedStrap(STRAP, "Polar H10")), after.scanned)
    }

    @Test
    fun `an unnamed strap is not listed`() {
        val after = Acquisition.decide(
            scanning,
            AcquisitionEvent.StrapSeen(STRAP, null),
            ctx(),
        ).state
        assertTrue(after.scanned.isEmpty())
    }

    @Test
    fun `the same strap is not listed twice`() {
        val after = run(
            start = scanning,
            events = arrayOf(
                AcquisitionEvent.StrapSeen(STRAP, "Polar H10"),
                AcquisitionEvent.StrapSeen(STRAP, "Polar H10"),
                AcquisitionEvent.StrapSeen(OTHER, "Wahoo Tickr"),
            ),
        )
        assertEquals(
            listOf(ScannedStrap(STRAP, "Polar H10"), ScannedStrap(OTHER, "Wahoo Tickr")),
            after.scanned,
        )
    }

    @Test
    fun `a result arriving after the scan ended is ignored`() {
        val after = Acquisition.decide(
            AcquisitionState(),
            AcquisitionEvent.StrapSeen(STRAP, "Polar H10"),
            ctx(),
        ).state
        assertTrue(after.scanned.isEmpty())
    }

    @Test
    fun `a scan failure while scanning is reported`() {
        val outcome = Acquisition.decide(scanning, AcquisitionEvent.ScanFailed(2), ctx())
        assertEquals(
            AcquisitionPhase.Blocked(AcquisitionBlock.ScanFailed(2)),
            outcome.state.phase,
        )
    }

    @Test
    fun `a late scan failure does not mask an in-flight connect`() {
        val connecting = Acquisition.decide(
            scanning,
            AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = true),
            ctx(),
        ).state
        val outcome = Acquisition.decide(connecting, AcquisitionEvent.ScanFailed(2), ctx())
        assertEquals(connecting, outcome.state)
    }
}

class AcquisitionConnectTest {

    @Test
    fun `connecting stops the scan and chases the strap`() {
        val scanning = run(events = arrayOf(AcquisitionEvent.ScanRequested()))
        val outcome = Acquisition.decide(
            scanning,
            AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = true),
            ctx(),
        )
        assertEquals(
            AcquisitionPhase.Connecting(STRAP, "Polar H10", true, 0, FIRST_RETRY_DELAY_MS),
            outcome.state.phase,
        )
        assertEquals(
            listOf(AcquisitionEffect.StopScan, AcquisitionEffect.ConnectGatt(STRAP)),
            outcome.effects,
        )
    }

    @Test
    fun `an unknown name falls back to the address`() {
        val outcome = Acquisition.decide(
            AcquisitionState(),
            AcquisitionEvent.ConnectRequested(STRAP, null, makeActive = true),
            ctx(),
        )
        assertEquals("Connecting to $STRAP...", outcome.state.statusLine)
    }

    @Test
    fun `the last connect wins and closes the one before it`() {
        // connectRequestSeq and gattConnectLock existed for this. On one thread it is just the
        // last event.
        val first = Acquisition.decide(
            AcquisitionState(),
            AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = true),
            ctx(),
        ).state
        val outcome = Acquisition.decide(
            first,
            AcquisitionEvent.ConnectRequested(OTHER, "Wahoo Tickr", makeActive = true),
            ctx(),
        )
        assertEquals(OTHER, outcome.state.address)
        assertTrue(outcome.effects.contains(AcquisitionEffect.CloseGatt(STRAP)))
        assertTrue(outcome.effects.contains(AcquisitionEffect.ConnectGatt(OTHER)))
    }

    @Test
    fun `no connect permission blocks rather than dead-ends`() {
        val outcome = Acquisition.decide(
            AcquisitionState(),
            AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = true),
            ctx(canConnect = false),
        )
        assertEquals(
            AcquisitionPhase.Blocked(AcquisitionBlock.PermissionMissing),
            outcome.state.phase,
        )
        assertFalse(outcome.state.inFlight)
    }

    @Test
    fun `no adapter blocks rather than dead-ends`() {
        val outcome = Acquisition.decide(
            AcquisitionState(),
            AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = true),
            ctx(bluetoothOn = false),
        )
        assertEquals(
            AcquisitionPhase.Blocked(AcquisitionBlock.BluetoothUnavailable),
            outcome.state.phase,
        )
    }

    @Test
    fun `a connected strap discovers its services`() {
        val connecting = run(
            events = arrayOf(AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", true)),
        )
        val outcome = Acquisition.decide(connecting, AcquisitionEvent.GattConnected(STRAP), ctx())
        assertEquals(
            AcquisitionPhase.Connected(STRAP, "Polar H10", promoteOnVerify = true),
            outcome.state.phase,
        )
        assertEquals(listOf(AcquisitionEffect.DiscoverServices(STRAP)), outcome.effects)
    }
}

class AcquisitionStaleCallbackTest {

    // A real GATT can report itself long after we stopped caring. That is Android, not our
    // threading, so no amount of serialising removes it.

    private val chasing = run(
        events = arrayOf(AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", true)),
    )

    @Test
    fun `a stale connect is closed and changes nothing`() {
        val outcome = Acquisition.decide(chasing, AcquisitionEvent.GattConnected(OTHER), ctx())
        assertEquals(chasing, outcome.state)
        assertEquals(listOf(AcquisitionEffect.CloseGatt(OTHER)), outcome.effects)
    }

    @Test
    fun `a stale disconnect does not schedule a retry`() {
        val outcome = Acquisition.decide(chasing, AcquisitionEvent.GattDisconnected(OTHER), ctx())
        assertEquals(chasing, outcome.state)
        assertEquals(listOf(AcquisitionEffect.CloseGatt(OTHER)), outcome.effects)
    }

    @Test
    fun `a stale discovery saves nothing`() {
        // Even a save that does not promote would re-add a Strap the runner just forgot.
        val connected = Acquisition.decide(
            chasing,
            AcquisitionEvent.GattConnected(STRAP),
            ctx(),
        ).state
        val outcome = Acquisition.decide(
            connected,
            AcquisitionEvent.ServicesDiscovered(OTHER, "Wahoo Tickr", hasHeartRateService = true),
            ctx(),
        )
        assertEquals(connected, outcome.state)
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `a discovery after forgetting saves nothing`() {
        val connected = Acquisition.decide(
            chasing,
            AcquisitionEvent.GattConnected(STRAP),
            ctx(),
        ).state
        val forgotten = Acquisition.decide(
            connected,
            AcquisitionEvent.ForgetRequested(STRAP),
            ctx(),
        ).state
        val outcome = Acquisition.decide(
            forgotten,
            AcquisitionEvent.ServicesDiscovered(STRAP, "Polar H10", hasHeartRateService = true),
            ctx(),
        )
        assertTrue(outcome.effects.isEmpty())
    }
}

class AcquisitionPromoteOnVerifyTest {

    private fun verified(makeActive: Boolean): List<AcquisitionEffect> {
        val connected = run(
            events = arrayOf(
                AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive),
                AcquisitionEvent.GattConnected(STRAP),
            ),
        )
        return Acquisition.decide(
            connected,
            AcquisitionEvent.ServicesDiscovered(STRAP, "Polar H10", hasHeartRateService = true),
            ctx(),
        ).effects
    }

    @Test
    fun `an explicit tap promotes the strap to active`() {
        assertTrue(
            verified(makeActive = true)
                .contains(AcquisitionEffect.SaveStrap(STRAP, "Polar H10", makeActive = true)),
        )
    }

    @Test
    fun `a background connect saves without promoting`() {
        // Auto-connecting Strap A while the runner makes Strap B active must not steal the slot.
        assertTrue(
            verified(makeActive = false)
                .contains(AcquisitionEffect.SaveStrap(STRAP, "Polar H10", makeActive = false)),
        )
    }

    @Test
    fun `one tap promotes once`() {
        val connected = run(
            events = arrayOf(
                AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = true),
                AcquisitionEvent.GattConnected(STRAP),
                AcquisitionEvent.ServicesDiscovered(STRAP, "Polar H10", true),
            ),
        )
        val again = Acquisition.decide(
            connected,
            AcquisitionEvent.ServicesDiscovered(STRAP, "Polar H10", true),
            ctx(),
        )
        assertTrue(
            again.effects.contains(AcquisitionEffect.SaveStrap(STRAP, "Polar H10", false)),
        )
    }

    @Test
    fun `a tap that drops and reconnects still promotes`() {
        val state = run(
            context = ctx(runIsLive = true),
            events = arrayOf(
                AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = true),
                AcquisitionEvent.GattDisconnected(STRAP),
            ),
        )
        val reconnected = run(
            start = state,
            context = ctx(now = ACQ_T0 + FIRST_RETRY_DELAY_MS, runIsLive = true),
            events = arrayOf(AcquisitionEvent.Tick, AcquisitionEvent.GattConnected(STRAP)),
        )
        val outcome = Acquisition.decide(
            reconnected,
            AcquisitionEvent.ServicesDiscovered(STRAP, "Polar H10", true),
            ctx(runIsLive = true),
        )
        assertTrue(
            outcome.effects.contains(AcquisitionEffect.SaveStrap(STRAP, "Polar H10", true)),
        )
    }

    @Test
    fun `a strap with no heart-rate service is not saved`() {
        val connected = run(
            events = arrayOf(
                AcquisitionEvent.ConnectRequested(STRAP, "Some Speaker", makeActive = true),
                AcquisitionEvent.GattConnected(STRAP),
            ),
        )
        val outcome = Acquisition.decide(
            connected,
            AcquisitionEvent.ServicesDiscovered(STRAP, "Some Speaker", hasHeartRateService = false),
            ctx(),
        )
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `a verified strap is subscribed to`() {
        val connected = run(
            events = arrayOf(
                AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = true),
                AcquisitionEvent.GattConnected(STRAP),
            ),
        )
        val outcome = Acquisition.decide(
            connected,
            AcquisitionEvent.ServicesDiscovered(STRAP, "Polar H10", true),
            ctx(),
        )
        assertTrue(
            outcome.effects.contains(AcquisitionEffect.SubscribeToHeartRate(STRAP)),
        )
    }
}

class AcquisitionRetryTest {

    private fun chaseAndDrop(
        runIsLive: Boolean,
        drops: Int,
    ): AcquisitionState {
        var state = Acquisition.decide(
            AcquisitionState(),
            AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = false),
            ctx(runIsLive = runIsLive),
        ).state
        var now = ACQ_T0
        repeat(drops) {
            state = Acquisition.decide(
                state,
                AcquisitionEvent.GattDisconnected(STRAP),
                ctx(now = now, runIsLive = runIsLive),
            ).state
            val due = (state.phase as? AcquisitionPhase.Retrying)?.dueAt ?: return state
            now = due
            state = Acquisition.decide(
                state,
                AcquisitionEvent.Tick,
                ctx(now = now, runIsLive = runIsLive),
            ).state
        }
        return state
    }

    @Test
    fun `the backoff doubles from three seconds`() {
        var state = Acquisition.decide(
            AcquisitionState(),
            AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = false),
            ctx(),
        ).state
        val announced = mutableListOf<Long>()
        var now = ACQ_T0
        repeat(3) {
            state = Acquisition.decide(
                state,
                AcquisitionEvent.GattDisconnected(STRAP),
                ctx(now = now),
            ).state
            val retrying = state.phase as AcquisitionPhase.Retrying
            announced += retrying.announcedDelayMs
            now = retrying.dueAt
            state = Acquisition.decide(state, AcquisitionEvent.Tick, ctx(now = now)).state
        }
        assertEquals(listOf(3_000L, 6_000L, 12_000L), announced)
    }

    @Test
    fun `pre-run it gives up after three retries`() {
        assertEquals(AcquisitionPhase.GaveUp, chaseAndDrop(runIsLive = false, drops = 4).phase)
    }

    @Test
    fun `pre-run it is still chasing after three`() {
        assertTrue(chaseAndDrop(runIsLive = false, drops = 3).inFlight)
    }

    @Test
    fun `mid-run it never gives up`() {
        // A dropout never ends or freezes a Run (#110).
        assertTrue(chaseAndDrop(runIsLive = true, drops = 12).inFlight)
    }

    @Test
    fun `the backoff stops doubling at thirty seconds`() {
        val state = chaseAndDrop(runIsLive = true, drops = 12)
        val phase = state.phase as AcquisitionPhase.Connecting
        assertEquals(MAX_RETRY_DELAY_MS, phase.nextDelayMs)
    }

    @Test
    fun `a run starting mid-chase lifts the cap`() {
        // runIsLive is read fresh on every decision, so this needs nothing of its own.
        var state = chaseAndDrop(runIsLive = false, drops = 3)
        state = Acquisition.decide(
            state,
            AcquisitionEvent.GattDisconnected(STRAP),
            ctx(runIsLive = true),
        ).state
        assertTrue(state.inFlight)
    }

    @Test
    fun `a connection that landed starts the backoff over`() {
        // This is the counter that used to reset every cycle, so the cap could never trip. It
        // resets on a connection actually made, and only then.
        val reconnected = run(
            context = ctx(runIsLive = true),
            events = arrayOf(
                AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = false),
                AcquisitionEvent.GattDisconnected(STRAP),
            ),
        )
        val landed = run(
            start = reconnected,
            context = ctx(now = ACQ_T0 + FIRST_RETRY_DELAY_MS, runIsLive = true),
            events = arrayOf(AcquisitionEvent.Tick, AcquisitionEvent.GattConnected(STRAP)),
        )
        val dropped = Acquisition.decide(
            landed,
            AcquisitionEvent.GattDisconnected(STRAP),
            ctx(now = ACQ_T0 + 99_999, runIsLive = true),
        ).state
        assertEquals(FIRST_RETRY_DELAY_MS, (dropped.phase as AcquisitionPhase.Retrying).announcedDelayMs)
    }

    @Test
    fun `a dropout tells the run the reading is gone`() {
        val outcome = Acquisition.decide(
            run(events = arrayOf(AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", false))),
            AcquisitionEvent.GattDisconnected(STRAP),
            ctx(runIsLive = true),
        )
        assertTrue(
            outcome.effects.contains(AcquisitionEffect.TellRunStrapLost(LOST_RETRYING)),
        )
    }

    @Test
    fun `a retry that comes due connects again`() {
        val retrying = run(
            context = ctx(runIsLive = true),
            events = arrayOf(
                AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = false),
                AcquisitionEvent.GattDisconnected(STRAP),
            ),
        )
        val outcome = Acquisition.decide(
            retrying,
            AcquisitionEvent.Tick,
            ctx(now = ACQ_T0 + FIRST_RETRY_DELAY_MS, runIsLive = true),
        )
        assertEquals(listOf(AcquisitionEffect.ConnectGatt(STRAP)), outcome.effects)
    }

    @Test
    fun `a retry waits until it is due`() {
        val retrying = run(
            context = ctx(runIsLive = true),
            events = arrayOf(
                AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = false),
                AcquisitionEvent.GattDisconnected(STRAP),
            ),
        )
        val outcome = Acquisition.decide(
            retrying,
            AcquisitionEvent.Tick,
            ctx(now = ACQ_T0 + FIRST_RETRY_DELAY_MS - 1, runIsLive = true),
        )
        assertEquals(retrying, outcome.state)
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `bluetooth going off mid-backoff ends the chase`() {
        // The one branch that used to reach nothing at all: getRemoteDevice returned null, the
        // retry silently failed to fire, and the status sat in flight forever — which by ADR 0001
        // is a wake lock nobody releases.
        val retrying = run(
            context = ctx(runIsLive = true),
            events = arrayOf(
                AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = false),
                AcquisitionEvent.GattDisconnected(STRAP),
            ),
        )
        val outcome = Acquisition.decide(
            retrying,
            AcquisitionEvent.Tick,
            ctx(now = ACQ_T0 + FIRST_RETRY_DELAY_MS, runIsLive = true, bluetoothOn = false),
        )
        assertEquals(
            AcquisitionPhase.Blocked(AcquisitionBlock.BluetoothUnavailable),
            outcome.state.phase,
        )
        assertFalse(outcome.state.inFlight)
    }

    @Test
    fun `permission revoked mid-backoff ends the chase`() {
        val retrying = run(
            context = ctx(runIsLive = true),
            events = arrayOf(
                AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = false),
                AcquisitionEvent.GattDisconnected(STRAP),
            ),
        )
        val outcome = Acquisition.decide(
            retrying,
            AcquisitionEvent.Tick,
            ctx(now = ACQ_T0 + FIRST_RETRY_DELAY_MS, runIsLive = true, canConnect = false),
        )
        assertFalse(outcome.state.inFlight)
    }
}

class AcquisitionReleaseTest {

    private val connected = run(
        events = arrayOf(
            AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = true),
            AcquisitionEvent.GattConnected(STRAP),
        ),
    )

    @Test
    fun `forgetting the chased strap releases it`() {
        val outcome = Acquisition.decide(
            connected,
            AcquisitionEvent.ForgetRequested(STRAP),
            ctx(),
        )
        assertEquals(AcquisitionPhase.Idle, outcome.state.phase)
        assertEquals(
            listOf(
                AcquisitionEffect.DisconnectAndCloseGatt(STRAP),
                AcquisitionEffect.TellRunStrapLost(LOST_DISCONNECTED),
            ),
            outcome.effects,
        )
    }

    @Test
    fun `forgetting some other strap changes nothing`() {
        val outcome = Acquisition.decide(
            connected,
            AcquisitionEvent.ForgetRequested(OTHER),
            ctx(),
        )
        assertEquals(connected, outcome.state)
        assertTrue(outcome.effects.isEmpty())
    }

    @Test
    fun `forgetting stops the retry loop`() {
        // Otherwise the loop keeps reconnecting a Strap the runner just removed, and the verify
        // path re-saves it.
        val retrying = Acquisition.decide(
            connected,
            AcquisitionEvent.GattDisconnected(STRAP),
            ctx(runIsLive = true),
        ).state
        val forgotten = Acquisition.decide(
            retrying,
            AcquisitionEvent.ForgetRequested(STRAP),
            ctx(runIsLive = true),
        ).state
        assertFalse(forgotten.inFlight)

        val later = Acquisition.decide(
            forgotten,
            AcquisitionEvent.Tick,
            ctx(now = ACQ_T0 + 60_000, runIsLive = true),
        )
        assertTrue(later.effects.isEmpty())
    }

    @Test
    fun `disconnecting releases the strap and tells the run`() {
        val outcome = Acquisition.decide(
            connected,
            AcquisitionEvent.DisconnectRequested,
            ctx(),
        )
        assertEquals(AcquisitionPhase.Idle, outcome.state.phase)
        assertEquals(
            listOf(
                AcquisitionEffect.DisconnectAndCloseGatt(STRAP),
                AcquisitionEffect.TellRunStrapLost(LOST_DISCONNECTED),
            ),
            outcome.effects,
        )
    }

    @Test
    fun `disconnecting stops a scan that was running`() {
        // Stop Workout goes through here. A scan left running would keep the Acquisition in flight
        // and the Promotion with it.
        val scanning = run(events = arrayOf(AcquisitionEvent.ScanRequested()))
        val outcome = Acquisition.decide(
            scanning,
            AcquisitionEvent.DisconnectRequested,
            ctx(),
        )
        assertTrue(outcome.effects.contains(AcquisitionEffect.StopScan))
        assertFalse(outcome.state.inFlight)
    }

    @Test
    fun `the disconnect that follows does not start a retry`() {
        val released = Acquisition.decide(
            connected,
            AcquisitionEvent.DisconnectRequested,
            ctx(),
        ).state
        val outcome = Acquisition.decide(
            released,
            AcquisitionEvent.GattDisconnected(STRAP),
            ctx(),
        )
        assertEquals(AcquisitionPhase.Idle, outcome.state.phase)
        assertFalse(outcome.state.inFlight)
    }
}

/**
 * Being in flight is what earns Promotion, so an Acquisition that can be in flight with no way out
 * is a wake lock nobody releases — the failure ADR 0001 exists to prevent. Every branch of every
 * in-flight phase must reach one that is not.
 */
class AcquisitionAlwaysTerminatesTest {

    @Test
    fun `a scan reaches a terminal phase`() {
        val scanning = run(events = arrayOf(AcquisitionEvent.ScanRequested()))
        assertTrue(scanning.inFlight)
        val after = Acquisition.decide(
            scanning,
            AcquisitionEvent.Tick,
            ctx(now = ACQ_T0 + SCAN_TIMEOUT_MS),
        ).state
        assertFalse(after.inFlight)
    }

    @Test
    fun `a pre-run chase reaches a terminal phase`() {
        var state = Acquisition.decide(
            AcquisitionState(),
            AcquisitionEvent.ConnectRequested(STRAP, "Polar H10", makeActive = false),
            ctx(),
        ).state
        var now = ACQ_T0
        // Four failures is the most a pre-run chase can survive.
        repeat(4) {
            state = Acquisition.decide(
                state,
                AcquisitionEvent.GattDisconnected(STRAP),
                ctx(now = now),
            ).state
            (state.phase as? AcquisitionPhase.Retrying)?.let {
                now = it.dueAt
                state = Acquisition.decide(state, AcquisitionEvent.Tick, ctx(now = now)).state
            }
        }
        assertFalse(state.inFlight)
        assertEquals(AcquisitionPhase.GaveUp, state.phase)
    }

    @Test
    fun `every block ends the acquisition`() {
        listOf(
            AcquisitionBlock.PermissionMissing,
            AcquisitionBlock.BluetoothUnavailable,
            AcquisitionBlock.ScanFailed(2),
        ).forEach {
            assertFalse(AcquisitionState(AcquisitionPhase.Blocked(it)).inFlight)
        }
    }
}
