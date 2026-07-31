package com.example.runningapp.ui

import com.example.runningapp.restore.CurrentHistory
import com.example.runningapp.restore.RestoreFileKind
import com.example.runningapp.restore.RestorePlan
import com.example.runningapp.restore.RestoreRefusal
import com.example.runningapp.restore.RestoreSummary
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The confirmation text is the whole safety mechanism for a manual restore — there is no undo and
 * no "never overwrite" guard behind it — so what it promises is worth pinning down.
 */
class RestoreModelsTest {

    private val london = ZoneId.of("Europe/London")

    // 12 July 2024, 10:00 UTC, and 3 June 2024.
    private val july = 1_720_778_400_000L
    private val june = 1_717_408_800_000L

    private fun plan(
        kind: RestoreFileKind = RestoreFileKind.ARCHIVE,
        incomingRuns: Int = 47,
        incomingNewest: Long? = june,
        currentRuns: Int = 13,
        currentNewest: Long? = july,
        carriesSettings: Boolean = kind == RestoreFileKind.ARCHIVE,
    ) = RestorePlan(
        summary = RestoreSummary(
            kind,
            incomingRuns,
            incomingNewest,
            databaseVersion = 19,
            carriesSettings = carriesSettings,
        ),
        current = CurrentHistory(currentRuns, currentNewest),
    )

    @Test
    fun `both sides of the trade are stated, with their dates`() {
        val body = restoreConfirmationBody(plan(), london)
        assertTrue(body, body.contains("holds 47 runs"))
        assertTrue(body, body.contains("3 Jun 2024"))
        assertTrue(body, body.contains("You have 13 runs"))
        assertTrue(body, body.contains("12 Jul 2024"))
    }

    @Test
    fun `an older file says so rather than being refused`() {
        // Someone who has just lost their history and picks a month-old archive is doing the right
        // thing. The app's job is to put both dates on screen, not to overrule them.
        val body = restoreConfirmationBody(plan(), london)
        assertTrue(body, body.contains("replaces it with older history"))
    }

    @Test
    fun `a newer file does not read as a warning about going backwards`() {
        val body = restoreConfirmationBody(plan(incomingNewest = july, currentNewest = june), london)
        assertFalse(body, body.contains("older history"))
        assertTrue(body, body.contains("Restoring replaces it."))
    }

    @Test
    fun `an empty phone is told it has nothing to lose`() {
        // The Clear-storage case this feature exists for. A screen that reads like a warning when
        // there is nothing at stake teaches the runner to ignore the warning that matters.
        val body = restoreConfirmationBody(plan(currentRuns = 0, currentNewest = null), london)
        assertTrue(body, body.contains("no run history on this phone"))
        assertFalse(body, body.contains("You have 0 runs"))
    }

    @Test
    fun `an archive promises settings back and a database says it cannot`() {
        assertTrue(restoreConfirmationBody(plan(), london).contains("will be restored too"))
        val database = restoreConfirmationBody(plan(kind = RestoreFileKind.DATABASE), london)
        assertTrue(database, database.contains("stay as they are"))
    }

    @Test
    fun `an archive that could not be read for settings does not promise them back`() {
        // Its history restores fine; its `archive.json` is damaged or from a future version. The
        // confirmation has to describe the restore that will actually happen.
        val body = restoreConfirmationBody(plan(carriesSettings = false), london)
        assertTrue(body, body.contains("stay as they are"))
    }

    @Test
    fun `the words archive and backup follow what was actually picked`() {
        assertTrue(restoreConfirmationBody(plan(), london).startsWith("This archive holds"))
        assertTrue(
            restoreConfirmationBody(plan(kind = RestoreFileKind.DATABASE), london)
                .startsWith("This backup holds")
        )
    }

    @Test
    fun `one run is not "1 runs"`() {
        val body = restoreConfirmationBody(plan(incomingRuns = 1, currentRuns = 1), london)
        assertTrue(body, body.contains("holds 1 run,"))
        assertTrue(body, body.contains("You have 1 run,"))
    }

    @Test
    fun `a backup with no runs still describes itself`() {
        val body = restoreConfirmationBody(plan(incomingRuns = 0, incomingNewest = null), london)
        assertTrue(body, body.contains("holds 0 runs."))
    }

    @Test
    fun `the confirmation always says it cannot be undone and the app will restart`() {
        val body = restoreConfirmationBody(plan(), london)
        assertTrue(body, body.contains("cannot be undone"))
        assertTrue(body, body.contains("close and reopen"))
    }

    @Test
    fun `every refusal says what to do next, and none of them are error codes`() {
        RestoreRefusal.entries.forEach { reason ->
            val message = restoreRefusalMessage(reason)
            assertTrue(reason.name, message.length > 40)
            assertFalse(reason.name, message.contains(reason.name))
        }
    }

    @Test
    fun `a run in progress explains itself rather than just being off`() {
        assertTrue(restoreRowSubtitle(runInProgress = true).contains("Finish your run"))
        assertTrue(restoreRowSubtitle(runInProgress = false).contains("backup"))
    }
}
