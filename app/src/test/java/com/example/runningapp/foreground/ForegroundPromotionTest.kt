package com.example.runningapp.foreground

import com.example.runningapp.SessionStatus
import com.example.runningapp.run.AcquisitionBlock
import com.example.runningapp.run.AcquisitionPhase
import com.example.runningapp.run.AcquisitionState
import com.example.runningapp.run.FIRST_RETRY_DELAY_MS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Records what the host was asked to do, so the decision can be checked without a device.
 */
private class RecordingHost(var platformGrantsIt: Boolean = true) : PromotionHost {
    val calls = mutableListOf<String>()
    override fun promote(): Boolean { calls += "promote"; return platformGrantsIt }
    override fun demote() { calls += "demote" }
    override fun showNotification(text: String) { calls += "show:$text" }
}

// The situations this file exercises, as Acquisition phases. They used to be status strings, and
// Promotion decided by searching them for four words (ADR 0007).
private const val TEST_STRAP = "AA:BB:CC:DD:EE:FF"
private val CONNECTED = AcquisitionPhase.Connected(TEST_STRAP, "Venu 2S")
private val NOT_FOUND = AcquisitionPhase.GaveUp
private val NO_PERMISSION = AcquisitionPhase.Blocked(AcquisitionBlock.PermissionMissing)
private val NO_BLUETOOTH = AcquisitionPhase.Blocked(AcquisitionBlock.BluetoothUnavailable)
private val SCANNING = AcquisitionPhase.Scanning(endsAt = 0L)
private val CONNECTING =
    AcquisitionPhase.Connecting(TEST_STRAP, "Venu 2S", false, 0, FIRST_RETRY_DELAY_MS)
private val RETRYING =
    AcquisitionPhase.Retrying(TEST_STRAP, "Venu 2S", false, 1, 0L, 3_000L, 6_000L)

private fun inFlight(phase: AcquisitionPhase) = AcquisitionState(phase).inFlight

// The statuses this file used to assert on are now phases, and which of them are in flight is
// AcquisitionState.inFlight — asserted in AcquisitionTest against the phase rather than against
// its sentence (ADR 0007).

class PromotionEarnedTest {

    @Test
    fun `a running run earns promotion`() {
        assertTrue(ForegroundPromotion.isEarned(SessionStatus.RUNNING, acquiringStrap = false))
    }

    @Test
    fun `a paused run earns promotion`() {
        assertTrue(ForegroundPromotion.isEarned(SessionStatus.PAUSED, acquiringStrap = false))
    }

    @Test
    fun `a stopping run still earns promotion`() {
        // STOPPING is a Run stopped before its row id arrived, and holds until the id lands and
        // the Run publishes STOPPED. Demoting here would stopSelf() mid-teardown.
        assertTrue(ForegroundPromotion.isEarned(SessionStatus.STOPPING, acquiringStrap = false))
    }

    @Test
    fun `an idle app earns nothing`() {
        assertFalse(ForegroundPromotion.isEarned(SessionStatus.IDLE, acquiringStrap = false))
    }

    @Test
    fun `an acquisition earns promotion with no run`() {
        assertTrue(ForegroundPromotion.isEarned(SessionStatus.IDLE, acquiringStrap = true))
    }
}

/**
 * The four leaks cited in #129, as the states they actually occurred in. Each was a
 * caller forgetting to release; none of them can recur, because no caller releases.
 */
class HistoricalLeakTest {

    private fun promotedAfter(status: SessionStatus, phase: AcquisitionPhase): Boolean {
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        promotion.promoteForStartCommand() // what onStartCommand always does
        promotion.reconcile(status, inFlight(phase))
        return promotion.isPromoted
    }

    @Test
    fun `4fe74cd - a bare sensor connecting with no run does not hold the foreground`() {
        assertFalse(promotedAfter(SessionStatus.IDLE, CONNECTED))
    }

    @Test
    fun `4fe74cd - a pre-run reconnect that gives up does not hold the foreground`() {
        assertFalse(promotedAfter(SessionStatus.IDLE, NOT_FOUND))
    }

    @Test
    fun `4fe74cd - an abandoned scan does not hold the foreground`() {
        assertFalse(promotedAfter(SessionStatus.IDLE, AcquisitionPhase.Idle))
    }

    @Test
    fun `0beef0f - a connect dead-ending on permissions does not hold the foreground`() {
        assertFalse(promotedAfter(SessionStatus.IDLE, NO_PERMISSION))
    }

    @Test
    fun `0beef0f - a connect with no bluetooth adapter does not hold the foreground`() {
        assertFalse(promotedAfter(SessionStatus.IDLE, NO_BLUETOOTH))
    }

    @Test
    fun `3bd4d3e - a strap connecting while the finished run finalizes does not hold it`() {
        // Status is already STOPPED while the finalize coroutine drains its writes. The old
        // guard counted the lingering session id as "in flight" and held forever.
        assertFalse(promotedAfter(SessionStatus.STOPPED, CONNECTED))
    }

    @Test
    fun `3bd4d3e - a live run still holds it while a strap connects`() {
        assertTrue(promotedAfter(SessionStatus.RUNNING, CONNECTED))
    }

    @Test
    fun `d335ef3 - a START ignored because a run is finalizing does not leak the promotion`() {
        // onStartCommand promoted for Android's deadline, then dispatch ignored the intent.
        assertFalse(promotedAfter(SessionStatus.STOPPED, AcquisitionPhase.Idle))
    }

    @Test
    fun `d335ef3 - a START that does begin a run keeps the promotion`() {
        assertTrue(promotedAfter(SessionStatus.RUNNING, SCANNING))
    }
}

class EdgeTriggeringTest {

    @Test
    fun `an unchanged answer does nothing`() {
        // Level-triggering would call demote — and so stopSelf — on every heartbeat while a
        // bare strap is connected.
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        repeat(5) { promotion.reconcile(SessionStatus.IDLE, acquiringStrap = false) }
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun `a run's heartbeat does not re-promote`() {
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = false)
        repeat(5) { promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = false) }
        assertEquals(listOf("promote"), host.calls)
    }

    @Test
    fun `the eager start-command promote is taken back exactly once`() {
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        promotion.promoteForStartCommand()
        promotion.reconcile(SessionStatus.IDLE, acquiringStrap = false)
        promotion.reconcile(SessionStatus.IDLE, acquiringStrap = false)
        assertEquals(listOf("promote", "demote"), host.calls)
    }

    @Test
    fun `every start command promotes, even when already promoted`() {
        // Android tracks the five-second startForeground deadline per startForegroundService()
        // call, so "already promoted" is not an excuse to skip one. This is the one call that is
        // deliberately not edge-triggered.
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = false)
        promotion.promoteForStartCommand()
        assertEquals(listOf("promote", "promote"), host.calls)
    }

    @Test
    fun `a full run promotes once and demotes once`() {
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        promotion.promoteForStartCommand()
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = true) // no second promote

        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = false) // strap connected
        promotion.reconcile(SessionStatus.PAUSED, acquiringStrap = false)
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = false)
        promotion.reconcile(SessionStatus.STOPPING, acquiringStrap = false)
        promotion.reconcile(SessionStatus.STOPPED, acquiringStrap = false)
        assertEquals(listOf("promote", "demote"), host.calls)
    }

    @Test
    fun `an acquisition handing over to a run never drops the promotion`() {
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        promotion.reconcile(SessionStatus.IDLE, acquiringStrap = true)
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = true)
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = false)
        assertEquals(listOf("promote"), host.calls)
    }
}

/**
 * Android 12+ refuses a foreground start from the background. Promotion is derived now, so it
 * can be asked for at moments the old caller-driven code never reached — this is the path that
 * only exists because of that.
 */
class RefusedPromotionTest {

    @Test
    fun `a refused promotion is not recorded as promoted`() {
        val host = RecordingHost(platformGrantsIt = false)
        val promotion = ForegroundPromotion(host)
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = false)
        assertFalse(promotion.isPromoted)
    }

    @Test
    fun `a refused promotion posts no notification`() {
        // Believing in a notification the platform never created would post run updates into
        // nothing — and stopForeground(REMOVE) would not clear them.
        val host = RecordingHost(platformGrantsIt = false)
        val promotion = ForegroundPromotion(host)
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = false)
        promotion.showNotification("Zone 3")
        assertEquals(listOf("promote"), host.calls)
    }

    @Test
    fun `a refused promotion is retried on the next state change`() {
        val host = RecordingHost(platformGrantsIt = false)
        val promotion = ForegroundPromotion(host)
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = false)
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = true)
        assertEquals(listOf("promote", "promote"), host.calls)
    }

    @Test
    fun `a refused promotion with no start to unwind is never demoted`() {
        // Mid-life refusal: no startForegroundService() is waiting on an answer, so there is
        // nothing to tear down — and demote() ends in stopSelf().
        val host = RecordingHost(platformGrantsIt = false)
        val promotion = ForegroundPromotion(host)
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = false)
        promotion.reconcile(SessionStatus.STOPPED, acquiringStrap = false)
        assertEquals(listOf("promote"), host.calls)
    }

    @Test
    fun `a refused start-command promotion does not latch`() {
        val host = RecordingHost(platformGrantsIt = false)
        val promotion = ForegroundPromotion(host)
        promotion.promoteForStartCommand()
        assertFalse(promotion.isPromoted)
    }
}

/**
 * A startForegroundService() the platform then refuses to promote. The service is started
 * either way, and only [ForegroundPromotion] ever stops it — so a refusal that is simply
 * dropped leaves a started service with no notification and no way out.
 */
class UnpromotedStartTest {

    @Test
    fun `a refused start-command promotion stops the service once nothing earns it`() {
        // Also onStartCommand's null-intent path: promote for the deadline, find nothing to
        // resume, and stop. Before the unwind existed, a refusal there logged "stopping" and
        // left the service running forever.
        val host = RecordingHost(platformGrantsIt = false)
        val promotion = ForegroundPromotion(host)
        promotion.promoteForStartCommand()
        promotion.reconcile(SessionStatus.IDLE, acquiringStrap = false)
        assertEquals(listOf("promote", "demote"), host.calls)
    }

    @Test
    fun `the unwind happens once`() {
        // demote() ends in stopSelf(); the heartbeat must not keep calling it.
        val host = RecordingHost(platformGrantsIt = false)
        val promotion = ForegroundPromotion(host)
        promotion.promoteForStartCommand()
        repeat(5) { promotion.reconcile(SessionStatus.IDLE, acquiringStrap = false) }
        assertEquals(listOf("promote", "demote"), host.calls)
    }

    @Test
    fun `a live run is not stopped just because its promotion was refused`() {
        // A degraded run — no notification — still beats a run whose service is killed.
        val host = RecordingHost(platformGrantsIt = false)
        val promotion = ForegroundPromotion(host)
        promotion.promoteForStartCommand()
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = true)
        assertEquals(listOf("promote", "promote"), host.calls)
    }

    @Test
    fun `a run that was never promoted still unwinds its start when it ends`() {
        val host = RecordingHost(platformGrantsIt = false)
        val promotion = ForegroundPromotion(host)
        promotion.promoteForStartCommand()
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = false) // retried, refused again
        promotion.reconcile(SessionStatus.STOPPED, acquiringStrap = false)
        assertEquals(listOf("promote", "promote", "demote"), host.calls)
    }

    @Test
    fun `a promotion granted on the retry leaves nothing owing`() {
        // The granted promote satisfies the start, so the eventual demote is the ordinary one —
        // not a second stop on top of it.
        val host = RecordingHost(platformGrantsIt = false)
        val promotion = ForegroundPromotion(host)
        promotion.promoteForStartCommand()
        host.platformGrantsIt = true
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = false)
        promotion.reconcile(SessionStatus.STOPPED, acquiringStrap = false)
        assertEquals(listOf("promote", "promote", "demote"), host.calls)
        assertFalse(promotion.isPromoted)
    }

    @Test
    fun `a granted start-command promotion owes no unwind of its own`() {
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        promotion.promoteForStartCommand()
        promotion.reconcile(SessionStatus.IDLE, acquiringStrap = false)
        promotion.reconcile(SessionStatus.IDLE, acquiringStrap = false)
        assertEquals(listOf("promote", "demote"), host.calls)
    }
}

/**
 * The subscription itself — what [ForegroundPromotion.follow] chooses to act on.
 *
 * These run on runTest's StandardTestDispatcher on purpose: nothing collects until the scheduler
 * is advanced, which is what lets a StateFlow conflate two publishes into one the way the real
 * service does. Collecting eagerly would hide the leak these exist for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowTest {

    /** The service publishes a session status and a connection status; Promotion reads the pair. */
    private fun MutableStateFlow<Pair<SessionStatus, AcquisitionPhase>>.asPromotionState() =
        map { (status, phase) -> status to inFlight(phase) }

    @Test
    fun `a conflated acquisition does not strand the eager start-command promote`() = runTest {
        // #144, found on a Pixel 8a: opening the app with a saved strap left a wake lock and an
        // ongoing notification held for minutes with no Run.
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        val published = MutableStateFlow<Pair<SessionStatus, AcquisitionPhase>>(SessionStatus.IDLE to AcquisitionPhase.Idle)
        val job = launch { promotion.follow(published.asPromotionState()) }
        runCurrent() // the collector takes up the idle state it starts from

        // onStartCommand: promote for Android's five-second deadline, publish "Connecting..."
        // inline, then reconcile at the tail while the acquisition is genuinely in flight.
        promotion.promoteForStartCommand()
        published.value = SessionStatus.IDLE to CONNECTING
        promotion.reconcile(SessionStatus.IDLE, acquiringStrap = true)

        // The connect to a bonded device lands ~10ms later, before the collector has run at all,
        // so StateFlow conflates "Connecting..." away and the pair reads unchanged.
        published.value = SessionStatus.IDLE to CONNECTED
        runCurrent()

        assertEquals(listOf("promote", "demote"), host.calls)
        assertFalse(promotion.isPromoted)
        job.cancel()
    }

    @Test
    fun `an unchanged state against an unchanged promotion still costs nothing`() = runTest {
        // What the old dedupe was protecting, and what keying on the Promotion must not lose:
        // demote() ends in stopSelf(), and this sees every per-second heartbeat.
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        val published = MutableStateFlow<Pair<SessionStatus, AcquisitionPhase>>(SessionStatus.IDLE to CONNECTED)
        val job = launch { promotion.follow(published.asPromotionState()) }
        runCurrent()

        repeat(5) {
            published.value = SessionStatus.IDLE to CONNECTED
            runCurrent()
        }

        assertEquals(emptyList<String>(), host.calls)
        job.cancel()
    }

    @Test
    fun `a run's heartbeat does not re-promote through the subscription`() = runTest {
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        val published = MutableStateFlow<Pair<SessionStatus, AcquisitionPhase>>(SessionStatus.IDLE to AcquisitionPhase.Idle)
        val job = launch { promotion.follow(published.asPromotionState()) }
        runCurrent()

        published.value = SessionStatus.RUNNING to CONNECTED
        runCurrent()
        repeat(5) {
            published.value = SessionStatus.RUNNING to CONNECTED
            runCurrent()
        }

        assertEquals(listOf("promote"), host.calls)
        job.cancel()
    }

    @Test
    fun `a full run promotes once and demotes once through the subscription`() = runTest {
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        val published = MutableStateFlow<Pair<SessionStatus, AcquisitionPhase>>(SessionStatus.IDLE to AcquisitionPhase.Idle)
        val job = launch { promotion.follow(published.asPromotionState()) }
        runCurrent()

        listOf(
            SessionStatus.IDLE to CONNECTING,
            SessionStatus.RUNNING to CONNECTED,
            SessionStatus.PAUSED to CONNECTED,
            SessionStatus.RUNNING to CONNECTED,
            SessionStatus.STOPPING to CONNECTED,
            SessionStatus.STOPPED to CONNECTED,
        ).forEach {
            published.value = it
            runCurrent()
        }

        assertEquals(listOf("promote", "demote"), host.calls)
        assertFalse(promotion.isPromoted)
        job.cancel()
    }

    @Test
    fun `a refused promotion is retried when the state moves, not on every heartbeat`() = runTest {
        val host = RecordingHost(platformGrantsIt = false)
        val promotion = ForegroundPromotion(host)
        val published = MutableStateFlow<Pair<SessionStatus, AcquisitionPhase>>(SessionStatus.IDLE to AcquisitionPhase.Idle)
        val job = launch { promotion.follow(published.asPromotionState()) }
        runCurrent()

        published.value = SessionStatus.RUNNING to CONNECTED
        runCurrent()
        repeat(5) { // refused, and isPromoted stayed false — the key must not churn
            published.value = SessionStatus.RUNNING to CONNECTED
            runCurrent()
        }
        published.value = SessionStatus.RUNNING to RETRYING
        runCurrent()

        assertEquals(listOf("promote", "promote"), host.calls)
        job.cancel()
    }
}

class NotificationOwnershipTest {

    @Test
    fun `a promoted service shows the text`() {
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = false)
        promotion.showNotification("Zone 3")
        assertEquals(listOf("promote", "show:Zone 3"), host.calls)
    }

    @Test
    fun `a demoted service posts nothing`() {
        // Otherwise a pause or resume landing just after the rule demotes posts a
        // notification that nothing owns and nothing removes.
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        promotion.showNotification("Zone 3")
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun `text posted after a stop is dropped`() {
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        promotion.reconcile(SessionStatus.RUNNING, acquiringStrap = false)
        promotion.reconcile(SessionStatus.STOPPED, acquiringStrap = false)
        promotion.showNotification("Run complete")
        assertEquals(listOf("promote", "demote"), host.calls)
    }
}
