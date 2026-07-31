package com.example.runningapp.archive

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveNamesTest {

    private val london = ZoneId.of("Europe/London")

    // 30 July 2026, 07:12 local (British Summer Time).
    private val julyMorning = 1_785_391_920_000L

    @Test
    fun `an archive is named for the local time it was made`() {
        assertEquals(
            "running-app-archive-2026-07-30-071200.zip",
            ArchiveNames.archiveName(julyMorning, london)
        )
    }

    @Test
    fun `an unfinished archive wears a name nothing counts as a backup`() {
        val inProgress = ArchiveNames.inProgressName(julyMorning, london)

        assertEquals("running-app-archive-2026-07-30-071200.zip.part", inProgress)
        assertFalse(ArchiveNames.isArchive(inProgress))
        assertTrue(ArchiveNames.isAbandoned(inProgress))
    }

    @Test
    fun `names sort oldest first as text`() {
        val names = listOf(
            ArchiveNames.archiveName(julyMorning, london),
            ArchiveNames.archiveName(julyMorning - 86_400_000, london),
            ArchiveNames.archiveName(julyMorning + 86_400_000, london)
        )

        assertEquals(names.sortedBy { it }, names.sorted())
        assertEquals(
            listOf(
                "running-app-archive-2026-07-29-071200.zip",
                "running-app-archive-2026-07-30-071200.zip",
                "running-app-archive-2026-07-31-071200.zip"
            ),
            names.sorted()
        )
    }

    @Test
    fun `three archives are kept and the oldest go`() {
        val names = listOf(
            "running-app-archive-2026-05-01-080000.zip",
            "running-app-archive-2026-06-01-080000.zip",
            "running-app-archive-2026-07-01-080000.zip",
            "running-app-archive-2026-08-01-080000.zip",
            "running-app-archive-2026-09-01-080000.zip"
        )

        assertEquals(
            listOf(
                "running-app-archive-2026-05-01-080000.zip",
                "running-app-archive-2026-06-01-080000.zip"
            ),
            ArchiveNames.retire(names)
        )
    }

    @Test
    fun `fewer than three archives retires nothing`() {
        val names = listOf(
            "running-app-archive-2026-08-01-080000.zip",
            "running-app-archive-2026-09-01-080000.zip"
        )

        assertEquals(emptyList<String>(), ArchiveNames.retire(names))
    }

    @Test
    fun `wreckage from a failed backup is swept up whatever its date`() {
        val names = listOf(
            "running-app-archive-2026-09-01-080000.zip",
            "running-app-archive-2026-09-02-080000.zip.part"
        )

        assertEquals(listOf("running-app-archive-2026-09-02-080000.zip.part"), ArchiveNames.retire(names))
    }

    @Test
    fun `nothing else in the runner's folder is touched`() {
        val names = listOf(
            "running-app-archive-2026-05-01-080000.zip",
            "running-app-archive-2026-06-01-080000.zip",
            "running-app-archive-2026-07-01-080000.zip",
            "running-app-archive-2026-08-01-080000.zip",
            "tax-return-2025.pdf",
            "holiday.jpg",
            "some-other-app-archive-2020-01-01.zip"
        )

        assertEquals(listOf("running-app-archive-2026-05-01-080000.zip"), ArchiveNames.retire(names))
    }

    @Test
    fun `the archive just written is never the one retired`() {
        // The phone moved west, or the clocks went back: the newest archive is named for an hour
        // that sorts before the three already there.
        val justWritten = "running-app-archive-2026-07-30-011200.zip"
        val names = listOf(
            justWritten,
            "running-app-archive-2026-07-30-021200.zip",
            "running-app-archive-2026-07-30-031200.zip",
            "running-app-archive-2026-07-30-041200.zip"
        )

        val retired = ArchiveNames.retire(names, justWritten = justWritten)

        assertEquals(listOf("running-app-archive-2026-07-30-021200.zip"), retired)
        assertFalse(retired.contains(justWritten))
    }

    @Test
    fun `protecting the newest still leaves exactly three archives`() {
        val justWritten = "running-app-archive-2026-01-01-000000.zip"
        val names = listOf(
            justWritten,
            "running-app-archive-2026-05-01-080000.zip",
            "running-app-archive-2026-06-01-080000.zip",
            "running-app-archive-2026-07-01-080000.zip",
            "running-app-archive-2026-08-01-080000.zip"
        )

        val kept = names - ArchiveNames.retire(names, justWritten = justWritten).toSet()

        assertEquals(3, kept.size)
        assertTrue(kept.contains(justWritten))
    }

    @Test
    fun `two backups a second apart are two archives, not one name twice`() {
        // The reason the name carries seconds: a name two backups can share is a name one of them
        // has to clear before it can be promoted, and a folder that accepts the deletion and then
        // refuses the rename has taken the last good archive away.
        assertEquals(
            "running-app-archive-2026-07-30-071201.zip",
            ArchiveNames.archiveName(julyMorning + 1_000, london)
        )
    }

    @Test
    fun `archives written before the name carried seconds are still this app's own`() {
        // The runner's folder is not empty when the format changes. Left unrecognised, these would
        // never be retired and would pile up beside the three that rotate.
        val old = "running-app-archive-2026-05-01-0800.zip"
        assertTrue(ArchiveNames.isArchive(old))
        assertTrue(ArchiveNames.isAbandoned("running-app-archive-2026-05-01-0800.zip.part"))

        val names = listOf(
            old,
            "running-app-archive-2026-06-01-080000.zip",
            "running-app-archive-2026-07-01-080000.zip",
            "running-app-archive-2026-08-01-080000.zip",
            "running-app-archive-2026-06-01-0800.zip.part"
        )

        assertEquals(
            listOf(old, "running-app-archive-2026-06-01-0800.zip.part"),
            ArchiveNames.retire(names)
        )
    }

    @Test
    fun `a runner's own file that merely starts the same way is not an archive`() {
        listOf(
            "running-app-archive-family.zip",
            "running-app-archive-.zip",
            "running-app-archive-2026-07-30-071200-copy.zip",
            "running-app-archive-2026-07-30.zip",
            "running-app-archive-2026-07-30-071200.zip.bak"
        ).forEach { name ->
            assertFalse(name, ArchiveNames.isArchive(name))
            assertFalse(name, ArchiveNames.isAbandoned(name))
        }
    }

    @Test
    fun `a lookalike is never retired, however many real archives there are`() {
        val names = listOf(
            "running-app-archive-2026-05-01-080000.zip",
            "running-app-archive-2026-06-01-080000.zip",
            "running-app-archive-2026-07-01-080000.zip",
            "running-app-archive-2026-08-01-080000.zip",
            "running-app-archive-family.zip",
            "running-app-archive-holiday.zip.part"
        )

        assertEquals(listOf("running-app-archive-2026-05-01-080000.zip"), ArchiveNames.retire(names))
    }
}
