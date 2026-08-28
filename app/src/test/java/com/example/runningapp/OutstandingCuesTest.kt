package com.example.runningapp

import com.example.runningapp.run.CueTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The tickets a Run's cues are held by, and what the end of a Run hands back (#220).
 *
 * Nothing here speaks: a ticket is a number the queue gave out, and this only remembers which ones
 * belong to the Run that is on.
 */
class OutstandingCuesTest {

    private val cues = OutstandingCues()

    /** Everything the end of a Run hands back, which the Run gets by being handed it. */
    private fun takeBackAll(): List<Long> {
        var handedBack: List<Long> = emptyList()
        cues.takeBackAll { handedBack = it }
        return handedBack
    }

    @Test
    fun `the end of a Run hands back every cue it enqueued, in the order they were enqueued`() {
        cues.record { 1L }
        cues.record(CueTag.TURNAROUND) { 2L }
        cues.record { 3L }

        assertEquals(listOf(1L, 2L, 3L), takeBackAll())
    }

    @Test
    fun `a cue taken back by name is not handed back a second time at the end of the Run`() {
        cues.record { 1L }
        cues.record(CueTag.TURNAROUND) { 2L }

        assertEquals(listOf(2L), cues.takeBack(CueTag.TURNAROUND))
        assertEquals(listOf(1L), takeBackAll())
    }

    @Test
    fun `taking back a name that is not outstanding hands back nothing`() {
        assertEquals(emptyList<Long>(), cues.takeBack(CueTag.TURNAROUND))

        cues.record(CueTag.TURNAROUND) { 1L }
        cues.takeBack(CueTag.TURNAROUND)

        assertEquals(emptyList<Long>(), cues.takeBack(CueTag.TURNAROUND))
    }

    @Test
    fun `the bookkeeping is per Run - a later Run's cues are not the earlier Run's to take back`() {
        cues.record { 1L }
        cues.record(CueTag.TURNAROUND) { 2L }
        takeBackAll()

        // The next Run starts with nothing outstanding, and its own cues are all it can hand back.
        cues.record { 3L }
        assertEquals(listOf(3L), takeBackAll())
    }

    /**
     * The case #377 turns on: the two course alerts are a pair, and a long sentence in front of them
     * can leave both waiting. Handing back only the newer would speak the older about a course that
     * has gone.
     */
    @Test
    fun `a name issued twice hands back both cues, oldest first`() {
        cues.record(CueTag.COURSE) { 1L }
        cues.record(CueTag.COURSE) { 2L }

        assertEquals(listOf(1L, 2L), cues.takeBack(CueTag.COURSE))
        // And neither is handed back a second time at the end of the Run.
        assertEquals(emptyList<Long>(), takeBackAll())
    }

    @Test
    fun `one name taken back leaves the cues under every other name outstanding`() {
        cues.record(CueTag.COURSE) { 1L }
        cues.record(CueTag.TURNAROUND) { 2L }

        assertEquals(listOf(1L), cues.takeBack(CueTag.COURSE))
        assertEquals(listOf(2L), takeBackAll())
    }

    @Test
    fun `a cue the queue would not take leaves nothing outstanding`() {
        assertNull(cues.record(CueTag.TURNAROUND) { null })

        assertEquals(emptyList<Long>(), cues.takeBack(CueTag.TURNAROUND))
        assertEquals(emptyList<Long>(), takeBackAll())
    }

    /**
     * The race #220 turns on: a cue enqueued on the UI thread while a Run ends on the session
     * thread. If the end of the Run could land between the enqueueing and the recording, the cue
     * would be outstanding for a Run that is over with no later pass to take it back — and it would
     * be spoken after the Run ended.
     */
    @Test(timeout = 5_000)
    fun `the end of a Run cannot land between a cue being enqueued and being recorded`() {
        val insideEnqueue = CountDownLatch(1)
        val releaseEnqueue = CountDownLatch(1)

        val enqueueing = Thread {
            cues.record {
                insideEnqueue.countDown()
                releaseEnqueue.await()
                1L
            }
        }
        enqueueing.start()
        insideEnqueue.await()

        val handedBack = AtomicReference<List<Long>>()
        val ending = Thread { handedBack.set(takeBackAll()) }
        ending.start()
        // Wait for the ending Run to be up against the lock rather than merely started, so that
        // releasing the enqueue really is the later of the two.
        while (ending.state != Thread.State.BLOCKED) Thread.yield()

        releaseEnqueue.countDown()
        enqueueing.join()
        ending.join()

        // Either order is correct; what is not is the cue slipping past the end of the Run. Here
        // the enqueue finished first, so the end of the Run takes it back.
        assertEquals(listOf(1L), handedBack.get())
        assertEquals(emptyList<Long>(), takeBackAll())
    }

    /**
     * The other half of the same race: a cue enqueued while the end of the Run is taking cues back.
     * If a cue could be enqueued between the list being taken and the cues being withdrawn, it
     * would be in neither — not in the list handed back, and no longer outstanding.
     */
    @Test(timeout = 5_000)
    fun `a cue cannot be enqueued while the end of a Run is taking its cues back`() {
        cues.record { 1L }

        val insideWithdrawal = CountDownLatch(1)
        val releaseWithdrawal = CountDownLatch(1)
        val handedBack = AtomicReference<List<Long>>()

        val ending = Thread {
            cues.takeBackAll {
                handedBack.set(it)
                insideWithdrawal.countDown()
                releaseWithdrawal.await()
            }
        }
        ending.start()
        insideWithdrawal.await()

        val enqueued = AtomicBoolean(false)
        val enqueueing = Thread { cues.record { enqueued.set(true); 2L } }
        enqueueing.start()
        while (enqueueing.state != Thread.State.BLOCKED) Thread.yield()

        // The queue has not been asked for a ticket at all while the withdrawal is in progress.
        assertFalse(enqueued.get())

        releaseWithdrawal.countDown()
        ending.join()
        enqueueing.join()

        assertEquals(listOf(1L), handedBack.get())
        // The second cue was enqueued after the Run ended, so it is not this Run's to take back —
        // the app speaks outside a Run too. What matters is that it was not lost in the gap.
        assertEquals(listOf(2L), takeBackAll())
    }

    @Test
    fun `the same cue is never handed back twice at the end of a Run`() {
        cues.record { 1L }

        assertEquals(listOf(1L), takeBackAll())
        assertEquals(emptyList<Long>(), takeBackAll())
    }
}
