package com.example.runningapp

import com.example.runningapp.run.CueTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * The tickets a Run's cues are held by, and what the end of a Run hands back (#220).
 *
 * Nothing here speaks: a ticket is a number the queue gave out, and this only remembers which ones
 * belong to the Run that is on.
 */
class OutstandingCuesTest {

    private val cues = OutstandingCues()

    @Test
    fun `the end of a Run hands back every cue it enqueued, in the order they were enqueued`() {
        cues.record { 1L }
        cues.record(CueTag.TURNAROUND) { 2L }
        cues.record { 3L }

        assertEquals(listOf(1L, 2L, 3L), cues.takeBackAll())
    }

    @Test
    fun `a cue taken back by name is not handed back a second time at the end of the Run`() {
        cues.record { 1L }
        cues.record(CueTag.TURNAROUND) { 2L }

        assertEquals(2L, cues.takeBack(CueTag.TURNAROUND))
        assertEquals(listOf(1L), cues.takeBackAll())
    }

    @Test
    fun `taking back a name that is not outstanding hands back nothing`() {
        assertNull(cues.takeBack(CueTag.TURNAROUND))

        cues.record(CueTag.TURNAROUND) { 1L }
        cues.takeBack(CueTag.TURNAROUND)

        assertNull(cues.takeBack(CueTag.TURNAROUND))
    }

    @Test
    fun `the bookkeeping is per Run - a later Run's cues are not the earlier Run's to take back`() {
        cues.record { 1L }
        cues.record(CueTag.TURNAROUND) { 2L }
        cues.takeBackAll()

        // The next Run starts with nothing outstanding, and its own cues are all it can hand back.
        cues.record { 3L }
        assertEquals(listOf(3L), cues.takeBackAll())
    }

    @Test
    fun `the same name issued twice keeps both cues, and the end of the Run hands back both`() {
        cues.record(CueTag.TURNAROUND) { 1L }
        cues.record(CueTag.TURNAROUND) { 2L }

        // By name only the last one can be found — but the first was never spoken either, so it is
        // still the Run's to take back.
        assertEquals(2L, cues.takeBack(CueTag.TURNAROUND))
        assertEquals(listOf(1L), cues.takeBackAll())
    }

    @Test
    fun `a cue the queue would not take leaves nothing outstanding`() {
        assertNull(cues.record(CueTag.TURNAROUND) { null })

        assertNull(cues.takeBack(CueTag.TURNAROUND))
        assertEquals(emptyList<Long>(), cues.takeBackAll())
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
        val ending = Thread { handedBack.set(cues.takeBackAll()) }
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
        assertEquals(emptyList<Long>(), cues.takeBackAll())
    }

    @Test
    fun `the same cue is never handed back twice at the end of a Run`() {
        cues.record { 1L }

        assertEquals(listOf(1L), cues.takeBackAll())
        assertEquals(emptyList<Long>(), cues.takeBackAll())
    }
}
