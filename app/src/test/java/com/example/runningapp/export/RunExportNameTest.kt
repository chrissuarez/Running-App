package com.example.runningapp.export

import com.example.runningapp.data.RunnerSession
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What an exported Run is called (#84, #218).
 *
 * One place, so the `.gpx` and the `.fit` of one Run cannot disagree about which evening it was — the
 * tests here are written against [RunExportName] rather than against either format for that reason.
 */
class RunExportNameTest {

    private val startTime = 1_753_500_000_000L // 2025-07-26T03:20:00Z
    private val utc = ZoneId.of("UTC")

    private fun session(id: Long = 1L, start: Long = startTime) = RunnerSession(
        id = id,
        startTime = start,
        endTime = start + 600_000,
        durationSeconds = 600,
    )

    @Test
    fun `builds a file name that is safe on every platform`() {
        assertEquals("run-2025-07-26-0320-1.gpx", RunExportName.fileName(session(), "gpx", utc))
    }

    @Test
    fun `two runs started in the same minute get different file names`() {
        assertNotEquals(
            RunExportName.fileName(session(id = 7L, start = startTime), "gpx", utc),
            RunExportName.fileName(session(id = 8L, start = startTime + 20_000), "gpx", utc),
        )
    }

    @Test
    fun `one run in two formats is two files, differing only in the extension`() {
        // Otherwise the second export written would overwrite the first in the share cache, and a
        // share still in flight would be handed the wrong file.
        val run = session()

        assertEquals("run-2025-07-26-0320-1.gpx", RunExportName.fileName(run, "gpx", utc))
        assertEquals("run-2025-07-26-0320-1.fit", RunExportName.fileName(run, "fit", utc))
    }

    @Test
    fun `a Run is named for the evening it was run, not for where the phone is now`() {
        // #304: 03:20 UTC is 13:20 in Sydney and 04:20 in London on the same date, but the Run was
        // recorded five hours behind UTC — 22:20 the evening before.
        val ranInNewYork = session().copy(ranAtUtcOffsetSeconds = -5 * 3600)

        assertEquals(
            "Run 25 Jul 2025, 22:20",
            RunExportName.runName(ranInNewYork, ZoneId.of("Australia/Sydney")),
        )
        assertEquals(
            "run-2025-07-25-2220-1.gpx",
            RunExportName.fileName(ranInNewYork, "gpx", ZoneId.of("Australia/Sydney")),
        )
    }

    @Test
    fun `a Run that wrote down no offset is still named in the phone's zone`() {
        assertEquals("Run 26 Jul 2025, 03:20", RunExportName.runName(session(), utc))
        assertEquals("run-2025-07-26-0320-1.gpx", RunExportName.fileName(session(), "gpx", utc))
    }
}
