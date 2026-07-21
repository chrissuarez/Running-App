package com.example.runningapp.foreground

import com.example.runningapp.SessionStatus
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

class IsAcquiringStrapTest {

    @Test
    fun `scanning is an acquisition`() {
        assertTrue(isAcquiringStrap("Scanning..."))
    }

    @Test
    fun `connecting is an acquisition`() {
        assertTrue(isAcquiringStrap("Connecting to Polar H10..."))
    }

    @Test
    fun `reconnecting is an acquisition`() {
        assertTrue(isAcquiringStrap("Reconnecting in 3s..."))
    }

    @Test
    fun `retrying is an acquisition`() {
        assertTrue(isAcquiringStrap("Disconnected (Retrying)"))
    }

    // Every terminal state. Each one is a place the old code had to remember to demote.

    @Test
    fun `connected ends the acquisition`() {
        assertFalse(isAcquiringStrap("Connected"))
    }

    @Test
    fun `strap not found ends the acquisition`() {
        assertFalse(isAcquiringStrap("Strap not found"))
    }

    @Test
    fun `missing permission ends the acquisition`() {
        assertFalse(isAcquiringStrap("Permission Missing"))
    }

    @Test
    fun `no bluetooth adapter ends the acquisition`() {
        assertFalse(isAcquiringStrap("Bluetooth Off/Unavailable"))
    }

    @Test
    fun `a failed scan ends the acquisition`() {
        // "Scan Failed: 2" contains "Scan" but is not "Scanning" — the distinction the
        // wake lock now depends on.
        assertFalse(isAcquiringStrap("Scan Failed: 2"))
    }

    @Test
    fun `disconnected is not an acquisition`() {
        assertFalse(isAcquiringStrap("Disconnected"))
    }

    @Test
    fun `idle is not an acquisition`() {
        assertFalse(isAcquiringStrap(""))
    }
}

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
        // STOPPING is published synchronously by stopSession() and holds until the finalize
        // coroutine publishes STOPPED. Demoting here would stopSelf() mid-teardown.
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

    @Test
    fun `an errored run earns nothing`() {
        assertFalse(ForegroundPromotion.isEarned(SessionStatus.ERROR, acquiringStrap = false))
    }
}

/**
 * The four leaks cited in #129, as the states they actually occurred in. Each was a
 * caller forgetting to release; none of them can recur, because no caller releases.
 */
class HistoricalLeakTest {

    private fun promotedAfter(status: SessionStatus, connectionStatus: String): Boolean {
        val host = RecordingHost()
        val promotion = ForegroundPromotion(host)
        promotion.promoteForStartCommand() // what onStartCommand always does
        promotion.reconcile(status, isAcquiringStrap(connectionStatus))
        return promotion.isPromoted
    }

    @Test
    fun `4fe74cd - a bare sensor connecting with no run does not hold the foreground`() {
        assertFalse(promotedAfter(SessionStatus.IDLE, "Connected"))
    }

    @Test
    fun `4fe74cd - a pre-run reconnect that gives up does not hold the foreground`() {
        assertFalse(promotedAfter(SessionStatus.IDLE, "Strap not found"))
    }

    @Test
    fun `4fe74cd - an abandoned scan does not hold the foreground`() {
        assertFalse(promotedAfter(SessionStatus.IDLE, "Disconnected"))
    }

    @Test
    fun `0beef0f - a connect dead-ending on permissions does not hold the foreground`() {
        assertFalse(promotedAfter(SessionStatus.IDLE, "Permission Missing"))
    }

    @Test
    fun `0beef0f - a connect with no bluetooth adapter does not hold the foreground`() {
        assertFalse(promotedAfter(SessionStatus.IDLE, "Bluetooth Off/Unavailable"))
    }

    @Test
    fun `3bd4d3e - a strap connecting while the finished run finalizes does not hold it`() {
        // Status is already STOPPED while the finalize coroutine drains its writes. The old
        // guard counted the lingering session id as "in flight" and held forever.
        assertFalse(promotedAfter(SessionStatus.STOPPED, "Connected"))
    }

    @Test
    fun `3bd4d3e - a live run still holds it while a strap connects`() {
        assertTrue(promotedAfter(SessionStatus.RUNNING, "Connected"))
    }

    @Test
    fun `d335ef3 - a START ignored because a run is finalizing does not leak the promotion`() {
        // onStartCommand promoted for Android's deadline, then dispatch ignored the intent.
        assertFalse(promotedAfter(SessionStatus.STOPPED, "Disconnected"))
    }

    @Test
    fun `d335ef3 - a START that does begin a run keeps the promotion`() {
        assertTrue(promotedAfter(SessionStatus.RUNNING, "Scanning..."))
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
