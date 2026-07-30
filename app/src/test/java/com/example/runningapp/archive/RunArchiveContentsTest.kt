package com.example.runningapp.archive

import com.example.runningapp.data.RunnerSession
import org.junit.Assert.assertEquals
import org.junit.Test

class RunArchiveContentsTest {

    private fun run(id: Long, finished: Boolean) =
        RunnerSession(id = id, startTime = id * 1000, endTime = if (finished) id * 2000 else 0)

    @Test
    fun `a finished run with a route gets a GPX of its own`() {
        val sessions = listOf(run(1, finished = true))

        assertEquals(sessions, runsWorthAGpx(sessions, listOf(1)))
    }

    @Test
    fun `a treadmill run has no route and so no GPX`() {
        val sessions = listOf(run(1, finished = true), run(2, finished = true))

        assertEquals(listOf(run(1, finished = true)), runsWorthAGpx(sessions, listOf(1)))
    }

    @Test
    fun `a run still being recorded waits for the next backup`() {
        val sessions = listOf(run(1, finished = true), run(2, finished = false))

        assertEquals(listOf(run(1, finished = true)), runsWorthAGpx(sessions, listOf(1, 2)))
    }

    @Test
    fun `an empty history produces no activity files at all`() {
        assertEquals(emptyList<RunnerSession>(), runsWorthAGpx(emptyList(), emptyList()))
    }
}
