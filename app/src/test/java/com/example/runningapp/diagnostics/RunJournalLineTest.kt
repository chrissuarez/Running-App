package com.example.runningapp.diagnostics

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class RunJournalLineTest {

    private val london = ZoneId.of("Europe/London")

    // 2026-08-16 07:50:58.123 in London (BST, +01:00)
    private val incident = 1786863058123L

    @Test
    fun `a line is a local wall clock, the run, and the event`() {
        val line = RunJournalLine.format(
            RunJournalEntry(RunJournalEvent.DEMOTED, runRowId = 41),
            atMillis = incident,
            zone = london,
        )

        assertEquals("2026-08-16 07:50:58.123+01:00 run=41 demoted", line)
    }

    @Test
    fun `a detail follows the event after a colon`() {
        val line = RunJournalLine.format(
            RunJournalEntry(RunJournalEvent.PROMOTION_REFUSED, runRowId = 41, detail = "startForegroundService() not allowed"),
            atMillis = incident,
            zone = london,
        )

        assertEquals(
            "2026-08-16 07:50:58.123+01:00 run=41 promotion-refused: startForegroundService() not allowed",
            line
        )
    }

    @Test
    fun `an event with no run to name says so rather than naming none`() {
        val line = RunJournalLine.format(
            RunJournalEntry(RunJournalEvent.SERVICE_CREATED),
            atMillis = incident,
            zone = london,
        )

        assertEquals("2026-08-16 07:50:58.123+01:00 run=- service-created", line)
    }

    @Test
    fun `a detail carrying newlines still occupies one line`() {
        val line = RunJournalLine.format(
            RunJournalEntry(RunJournalEvent.SERVICE_DESTROYED, detail = "first\nsecond\r\nthird"),
            atMillis = incident,
            zone = london,
        )

        assertEquals(
            "2026-08-16 07:50:58.123+01:00 run=- service-destroyed: first second third",
            line
        )
    }

    @Test
    fun `every event has a token of its own`() {
        val tokens = RunJournalEvent.values().map { it.token }
        assertEquals(tokens.size, tokens.toSet().size)
    }
}
