package com.example.runningapp.navigation

import com.example.runningapp.analysis.RecordType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The closing rules (#476), against a plain list standing in for the NavController. What is being
 * proved is which pages the stack is left holding — where Back takes the runner next.
 */
class PageStackTest {

    /** The stack as a list of filled addresses, oldest first, with every pop written down. */
    private class ListPageStack(vararg pages: String) : PageStack {
        private val pages = pages.toMutableList()
        val log = mutableListOf<String>()

        override val addresses: List<String> get() = pages.toList()

        override fun takeOff(count: Int) {
            repeat(count) { pages.removeAt(pages.lastIndex) }
            log += "took $count"
        }
    }

    private val history = Routes.HISTORY
    private val main = Routes.MAIN

    // History -> Run 1 -> the Runs matched to Run 1 -> Run 1 again: the stack every #414 fix was
    // about. `launchSingleTop` does not fold the second copy in, because the page on top when it is
    // asked for is the group, not the Run.
    private fun runOpenedTwice() = ListPageStack(
        main,
        history,
        Routes.sessionDetail(1L),
        Routes.matchedRuns(1L),
        Routes.sessionDetail(1L),
    )

    @Test
    fun `back while the Run is going leaves every page of that Run`() {
        val stack = runOpenedTwice()

        stack.leaveRunPage(runId = 1L, runIsGoing = true)

        // Where the landing would have put the runner anyway, with nothing live left above a page
        // the landing will sweep.
        assertEquals(listOf(main, history), stack.addresses)
    }

    @Test
    fun `back while the Run stays leaves only the page the runner is on`() {
        val stack = runOpenedTwice()

        stack.leaveRunPage(runId = 1L, runIsGoing = false)

        assertEquals(
            listOf(main, history, Routes.sessionDetail(1L), Routes.matchedRuns(1L)),
            stack.addresses
        )
    }

    @Test
    fun `back from a page that names no Run is one step back`() {
        val stack = runOpenedTwice()

        stack.leaveRunPage(runId = null, runIsGoing = true)

        assertEquals(4, stack.addresses.size)
    }

    @Test
    fun `a landed delete takes every page of that Run and lands where it was first opened from`() {
        // Opened from a Record, not from History: the runner goes back to the Record.
        val record = Routes.recordDetail(RecordType.entries.first())
        val stack = ListPageStack(
            main,
            record,
            Routes.sessionDetail(1L),
            Routes.matchedRuns(1L),
            Routes.sessionDetail(1L),
        )

        stack.landDeletedRuns(setOf(1L)) {}

        assertEquals(listOf(main, record), stack.addresses)
    }

    @Test
    fun `a landed delete is acknowledged only after that Run's pages are off`() {
        val stack = runOpenedTwice()

        stack.landDeletedRuns(setOf(1L)) { runId ->
            // Acknowledging is what clears the landing for good, so by now nothing about the Run
            // can be left for a second landing to take.
            assertEquals(listOf(main, history), stack.addresses)
            stack.log += "acknowledged $runId"
        }

        assertEquals(listOf("took 3", "acknowledged 1"), stack.log)
    }

    @Test
    fun `two deletes landing together each take their own pages`() {
        val stack = ListPageStack(
            main,
            history,
            Routes.sessionDetail(1L),
            Routes.segmentDetail(5L),
            Routes.sessionDetail(2L),
        )
        val acknowledged = mutableListOf<Long>()

        stack.landDeletedRuns(setOf(2L, 1L)) { acknowledged += it }

        assertEquals(listOf(main, history), stack.addresses)
        assertEquals(listOf(2L, 1L), acknowledged)
    }

    @Test
    fun `a landed delete for a Run with no page left takes nothing and is still acknowledged`() {
        // The runner walked away during the wait.
        val stack = ListPageStack(main, history)
        val acknowledged = mutableListOf<Long>()

        stack.landDeletedRuns(setOf(1L)) { acknowledged += it }

        assertEquals(listOf(main, history), stack.addresses)
        assertEquals(emptyList<String>(), stack.log)
        assertEquals(listOf(1L), acknowledged)
    }

    @Test
    fun `closing one Run's pages leaves a Run whose id only starts the same`() {
        // "session_detail/1" is not "session_detail/12": only the exact address names the page.
        val stack = ListPageStack(main, history, Routes.sessionDetail(12L))

        stack.closeEveryPageOf(Routes.sessionDetail(1L))

        assertEquals(listOf(main, history, Routes.sessionDetail(12L)), stack.addresses)
    }

    @Test
    fun `a deleted Segment takes every copy of its page`() {
        // Segments -> X -> one of its Runs -> that Run's card for X.
        val stack = ListPageStack(
            main,
            Routes.SEGMENTS,
            Routes.segmentDetail(7L),
            Routes.sessionDetail(3L),
            Routes.segmentDetail(7L),
        )

        stack.closeEveryPageOf(Routes.segmentDetail(7L))

        assertEquals(listOf(main, Routes.SEGMENTS), stack.addresses)
    }

    @Test
    fun `a group gone closes its page and leaves the Run it was opened from`() {
        val stack = ListPageStack(main, history, Routes.sessionDetail(1L), Routes.matchedRuns(1L))

        stack.closeEveryPageOf(Routes.matchedRuns(1L))
        // "There is no such group" can be read more than once. The second time takes nothing.
        stack.closeEveryPageOf(Routes.matchedRuns(1L))

        assertEquals(listOf(main, history, Routes.sessionDetail(1L)), stack.addresses)
    }

    @Test
    fun `a Record nobody can name closes its page by the name that opened it`() {
        val stack = ListPageStack(main, Routes.recordDetail("GONE_RECORD"))

        stack.closeEveryPageOf(Routes.recordDetail("GONE_RECORD"))

        assertEquals(listOf(main), stack.addresses)
    }

    @Test
    fun `the start page is never taken`() {
        val stack = ListPageStack(main)

        stack.closeEveryPageOf(main)

        assertEquals(listOf(main), stack.addresses)
    }

    // The NavController adapter reads each page's address back from its pattern and arguments. A
    // spelling that drifted from the builders' would still read correctly and would still compile,
    // but it would match no filled address, so no page would ever close. These lock the two
    // spellings together for every page that closes itself.

    @Test
    fun `a Run's page reads back as the address the builder spells`() {
        assertEquals(
            Routes.sessionDetail(9L),
            fillAddress(Routes.SESSION_DETAIL, mapOf(Routes.ARG_SESSION_ID to "9"))
        )
    }

    @Test
    fun `a group's page reads back as the address the builder spells`() {
        assertEquals(
            Routes.matchedRuns(9L),
            fillAddress(Routes.MATCHED_RUNS, mapOf(Routes.ARG_SESSION_ID to "9"))
        )
    }

    @Test
    fun `a Segment's page reads back as the address the builder spells`() {
        assertEquals(
            Routes.segmentDetail(7L),
            fillAddress(Routes.SEGMENT_DETAIL, mapOf(Routes.ARG_SEGMENT_ID to "7"))
        )
    }

    @Test
    fun `a Record's page reads back as the address the builder spells`() {
        val type = RecordType.entries.first()
        assertEquals(
            Routes.recordDetail(type),
            fillAddress(Routes.RECORD_DETAIL, mapOf(Routes.ARG_RECORD_TYPE to type.name))
        )
    }

    @Test
    fun `a page with no argument to fill it matches no filled address`() {
        assertEquals(Routes.SESSION_DETAIL, fillAddress(Routes.SESSION_DETAIL, emptyMap()))
        assertEquals(Routes.HISTORY, fillAddress(Routes.HISTORY, emptyMap()))
    }
}
