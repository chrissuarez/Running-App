package com.example.runningapp.archive

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiverTest {

    private val london = ZoneId.of("Europe/London")

    // 30 July 2026, 07:12 local.
    private val julyMorning = 1_785_391_920_000L
    private val expectedName = "running-app-archive-2026-07-30-0712.zip"
    private val expectedInProgressName = "$expectedName.part"

    /** A folder in memory, remembering what was done to it and in what order. */
    private class FakeFolder(initial: List<String> = emptyList()) : ArchiveFolder {
        val files = linkedMapOf<String, ByteArray>().apply {
            initial.forEach { put(it, ByteArray(0)) }
        }
        val log = mutableListOf<String>()
        var failWriteOf: String? = null
        var failRename = false
        var failList = false

        override suspend fun list(): List<String> {
            if (failList) throw IOException("folder gone")
            log += "list"
            return files.keys.toList()
        }

        override suspend fun write(fileName: String, contents: suspend (OutputStream) -> Unit) {
            log += "write $fileName"
            val out = ByteArrayOutputStream()
            // Half-written on failure, exactly as a real folder would leave it — the file exists
            // and holds whatever landed before the stream gave out.
            try {
                contents(out)
                if (fileName == failWriteOf) throw IOException("no space left")
            } finally {
                files[fileName] = out.toByteArray()
            }
        }

        override suspend fun rename(fileName: String, newName: String) {
            if (failRename) throw IOException("read-only folder")
            log += "rename $fileName -> $newName"
            files.remove(fileName)?.let { files[newName] = it }
        }

        override suspend fun delete(fileName: String) {
            log += "delete $fileName"
            files.remove(fileName)
        }
    }

    private fun archiver(
        folder: ArchiveFolder?,
        contents: suspend (Long) -> List<ArchiveEntry> = {
            listOf(ArchiveEntry.ofText(ArchiveJson.FILE_NAME, "{}"))
        },
        onArchived: suspend (Long) -> Unit = {}
    ) = Archiver(
        folder = { folder },
        contents = contents,
        onArchived = onArchived,
        now = { julyMorning },
        zoneId = london
    )

    @Test
    fun `a backup writes one archive named for now and records the time`() = runTest {
        val folder = FakeFolder()
        var recordedAt: Long? = null

        val outcome = archiver(folder, onArchived = { recordedAt = it }).archiveNow()

        assertEquals(ArchiveOutcome.Archived(expectedName, julyMorning), outcome)
        assertEquals(listOf(expectedName), folder.files.keys.toList())
        assertEquals(julyMorning, recordedAt)
    }

    @Test
    fun `the archive is written under a part name and only then promoted`() = runTest {
        val folder = FakeFolder()

        archiver(folder).archiveNow()

        assertEquals(
            listOf(
                "write $expectedInProgressName",
                "delete $expectedName",
                "rename $expectedInProgressName -> $expectedName"
            ),
            folder.log.filterNot { it == "list" }.take(3)
        )
    }

    @Test
    fun `nothing is deleted until the replacement has landed`() = runTest {
        val folder = FakeFolder(
            listOf(
                "running-app-archive-2026-04-01-0800.zip",
                "running-app-archive-2026-05-01-0800.zip",
                "running-app-archive-2026-06-01-0800.zip"
            )
        )

        archiver(folder).archiveNow()

        val promotion = folder.log.indexOf("rename $expectedInProgressName -> $expectedName")
        val retirement = folder.log.indexOf("delete running-app-archive-2026-04-01-0800.zip")
        assertTrue(promotion in 0 until retirement)
        assertEquals(
            listOf(
                "running-app-archive-2026-05-01-0800.zip",
                "running-app-archive-2026-06-01-0800.zip",
                expectedName
            ),
            folder.files.keys.sorted()
        )
    }

    @Test
    fun `no folder chosen writes nothing and says so`() = runTest {
        var recordedAt: Long? = null

        val outcome = archiver(folder = null, onArchived = { recordedAt = it }).archiveNow()

        assertEquals(ArchiveOutcome.NoFolderChosen, outcome)
        assertNull(recordedAt)
    }

    @Test
    fun `a failed write leaves the previous archives standing and no backup time`() = runTest {
        val folder = FakeFolder(listOf("running-app-archive-2026-06-01-0800.zip"))
        folder.failWriteOf = expectedInProgressName
        var recordedAt: Long? = null

        val outcome = archiver(folder, onArchived = { recordedAt = it }).archiveNow()

        assertTrue(outcome is ArchiveOutcome.Failed)
        assertEquals(listOf("running-app-archive-2026-06-01-0800.zip"), folder.files.keys.toList())
        assertNull(recordedAt)
    }

    @Test
    fun `an archive that could not be promoted is kept, but is not counted as a backup`() = runTest {
        val folder = FakeFolder()
        folder.failRename = true
        var recordedAt: Long? = null

        val outcome = archiver(folder, onArchived = { recordedAt = it }).archiveNow()

        assertTrue(outcome is ArchiveOutcome.Failed)
        // Complete, and the newest copy of the history there is — so it stays. It just doesn't
        // wear an archive's name, which is what stops anything treating it as a backup.
        assertEquals(listOf(expectedInProgressName), folder.files.keys.toList())
        assertEquals(emptyList<String>(), folder.files.keys.filter(ArchiveNames::isArchive))
        assertNull(recordedAt)
    }

    @Test
    fun `a folder that keeps refusing does not collect a copy of the database per attempt`() = runTest {
        val folder = FakeFolder()
        folder.failRename = true
        val archiver = archiver(folder)

        repeat(3) { archiver.archiveNow() }

        assertEquals(listOf(expectedInProgressName), folder.files.keys.toList())
    }

    @Test
    fun `a folder that refuses to retire the old ones still counts as backed up`() = runTest {
        val folder = FakeFolder()
        var recordedAt: Long? = null

        val outcome = archiver(folder, onArchived = { recordedAt = it }).let {
            folder.failList = true
            it.archiveNow()
        }

        assertEquals(ArchiveOutcome.Archived(expectedName, julyMorning), outcome)
        assertEquals(julyMorning, recordedAt)
    }

    @Test
    fun `backing up twice in the same minute replaces rather than accumulates`() = runTest {
        val folder = FakeFolder()
        val archiver = archiver(folder)

        archiver.archiveNow()
        val outcome = archiver.archiveNow()

        assertEquals(ArchiveOutcome.Archived(expectedName, julyMorning), outcome)
        assertEquals(listOf(expectedName), folder.files.keys.toList())
    }

    @Test
    fun `wreckage left by an earlier failure is swept up by the next backup`() = runTest {
        val folder = FakeFolder(listOf("running-app-archive-2026-06-01-0800.zip.part"))

        archiver(folder).archiveNow()

        assertEquals(listOf(expectedName), folder.files.keys.toList())
    }

    @Test
    fun `the contents are assembled for the moment the archive is stamped with`() = runTest {
        val folder = FakeFolder()
        var assembledFor: Long? = null

        archiver(folder, contents = { at ->
            assembledFor = at
            listOf(ArchiveEntry.ofText(ArchiveJson.FILE_NAME, "{}"))
        }).archiveNow()

        assertEquals(julyMorning, assembledFor)
    }
}
