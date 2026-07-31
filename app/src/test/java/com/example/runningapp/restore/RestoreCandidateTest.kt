package com.example.runningapp.restore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreFileKindTest {

    private fun head(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    private fun head(text: String) = text.toByteArray(Charsets.US_ASCII)

    @Test
    fun `a SQLite header is a database`() {
        assertEquals(RestoreFileKind.DATABASE, RestoreFileKind.detect(head("SQLite format 3\u0000")))
    }

    @Test
    fun `a zip header is an archive`() {
        assertEquals(RestoreFileKind.ARCHIVE, RestoreFileKind.detect(head(0x50, 0x4B, 0x03, 0x04)))
    }

    @Test
    fun `the name is never consulted — only the first bytes`() {
        // A backup that came back through Drive or a chat app can arrive renamed or with its
        // extension stripped. Refusing it over a name would refuse a perfectly good backup at the
        // one moment the runner needs it.
        val renamed = head("SQLite format 3\u0000") + "anything at all".toByteArray()
        assertEquals(RestoreFileKind.DATABASE, RestoreFileKind.detect(renamed))
    }

    @Test
    fun `the zero byte that ends the SQLite header is part of it`() {
        // "SQLite format 3" and then something else is not a database. Matching only the letters
        // would accept a text file that happens to start by naming the format.
        assertNull(RestoreFileKind.detect(head("SQLite format 3X")))
    }

    @Test
    fun `something that is neither is refused rather than guessed at`() {
        assertNull(RestoreFileKind.detect(head("just some text")))
        assertNull(RestoreFileKind.detect(ByteArray(0)))
    }

    @Test
    fun `a file too short to carry the header is not a backup`() {
        // A truncated download: the first bytes are right as far as they go, and there aren't
        // enough of them to be the header. Prefix-matching would say yes; it has to say no.
        assertNull(RestoreFileKind.detect(head("SQLite")))
    }
}

class RestoreEligibilityTest {

    private fun summary(databaseVersion: Int) = RestoreSummary(
        kind = RestoreFileKind.DATABASE,
        runCount = 12,
        newestRunStartedAtEpochMillis = 1_000L,
        databaseVersion = databaseVersion,
    )

    @Test
    fun `a backup from this version is allowed`() {
        assertTrue(RestoreEligibility.of(summary(19), 19) is RestoreEligibility.Allowed)
    }

    @Test
    fun `an older backup is allowed — that is what a backup from before an update is`() {
        assertTrue(RestoreEligibility.of(summary(11), 19) is RestoreEligibility.Allowed)
    }

    @Test
    fun `a backup from a newer app is refused`() {
        // Room migrates forward and has no path back. Opened, it either kills the app on launch
        // with the previous history already replaced, or reads columns that have changed meaning.
        val eligibility = RestoreEligibility.of(summary(20), 19)
        assertEquals(
            RestoreRefusal.FROM_A_NEWER_APP,
            (eligibility as RestoreEligibility.Refused).reason,
        )
    }
}

class RestorePlanTest {

    private val may = 1_714_000_000_000L
    private val june = 1_717_000_000_000L

    private fun plan(
        incomingNewest: Long?,
        incomingRuns: Int = 40,
        currentNewest: Long?,
        currentRuns: Int,
    ) = RestorePlan(
        summary = RestoreSummary(
            kind = RestoreFileKind.ARCHIVE,
            runCount = incomingRuns,
            newestRunStartedAtEpochMillis = incomingNewest,
            databaseVersion = 19,
        ),
        current = CurrentHistory(
            runCount = currentRuns,
            newestRunStartedAtEpochMillis = currentNewest,
        ),
    )

    @Test
    fun `an older backup is going back in time`() {
        assertTrue(plan(incomingNewest = may, currentNewest = june, currentRuns = 3).goingBackInTime)
    }

    @Test
    fun `a newer backup is not`() {
        assertFalse(plan(incomingNewest = june, currentNewest = may, currentRuns = 3).goingBackInTime)
    }

    @Test
    fun `restoring onto an empty phone is never going back in time`() {
        // The Clear-storage case: nothing here to be older than, whatever the file's date.
        assertFalse(plan(incomingNewest = may, currentNewest = null, currentRuns = 0).goingBackInTime)
    }

    @Test
    fun `an empty backup over real history is going back in time`() {
        // A backup taken from a phone whose history was already gone. Restorable, and the runner
        // needs to be told they are trading runs for none.
        assertTrue(
            plan(incomingNewest = null, incomingRuns = 0, currentNewest = june, currentRuns = 13)
                .goingBackInTime
        )
    }

    @Test
    fun `an empty phone has no history to replace`() {
        assertFalse(
            plan(incomingNewest = may, currentNewest = null, currentRuns = 0)
                .replacesExistingHistory
        )
    }

    @Test
    fun `an archive carries settings and a bare database does not`() {
        assertTrue(plan(incomingNewest = may, currentNewest = null, currentRuns = 0).summary.carriesSettings)
        val database = RestoreSummary(RestoreFileKind.DATABASE, 1, may, 19)
        assertFalse(database.carriesSettings)
    }
}
