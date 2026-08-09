package com.example.runningapp

import com.example.runningapp.run.CueTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
        cues.record(1L)
        cues.record(2L, CueTag.TURNAROUND)
        cues.record(3L)

        assertEquals(listOf(1L, 2L, 3L), cues.takeBackAll())
    }

    @Test
    fun `a cue taken back by name is not handed back a second time at the end of the Run`() {
        cues.record(1L)
        cues.record(2L, CueTag.TURNAROUND)

        assertEquals(2L, cues.takeBack(CueTag.TURNAROUND))
        assertEquals(listOf(1L), cues.takeBackAll())
    }

    @Test
    fun `taking back a name that is not outstanding hands back nothing`() {
        assertNull(cues.takeBack(CueTag.TURNAROUND))

        cues.record(1L, CueTag.TURNAROUND)
        cues.takeBack(CueTag.TURNAROUND)

        assertNull(cues.takeBack(CueTag.TURNAROUND))
    }

    @Test
    fun `the bookkeeping is per Run - a later Run's cues are not the earlier Run's to take back`() {
        cues.record(1L)
        cues.record(2L, CueTag.TURNAROUND)
        cues.takeBackAll()

        // The next Run starts with nothing outstanding, and its own cues are all it can hand back.
        cues.record(3L)
        assertEquals(listOf(3L), cues.takeBackAll())
    }

    @Test
    fun `the same name issued twice keeps both cues, and the end of the Run hands back both`() {
        cues.record(1L, CueTag.TURNAROUND)
        cues.record(2L, CueTag.TURNAROUND)

        // By name only the last one can be found — but the first was never spoken either, so it is
        // still the Run's to take back.
        assertEquals(2L, cues.takeBack(CueTag.TURNAROUND))
        assertEquals(listOf(1L), cues.takeBackAll())
    }

    @Test
    fun `the same cue is never handed back twice at the end of a Run`() {
        cues.record(1L)

        assertEquals(listOf(1L), cues.takeBackAll())
        assertEquals(emptyList<Long>(), cues.takeBackAll())
    }
}
