package com.example.runningapp.archive

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiverTest {

    private val london = ZoneId.of("Europe/London")

    // 30 July 2026, 07:12 local.
    private val julyMorning = 1_785_391_920_000L
    private val expectedName = "running-app-archive-2026-07-30-071200.zip"
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

        /**
         * A folder that keeps a file it was asked to remove. Real ones say so by returning false
         * from `deleteDocument` as readily as by throwing; [SafArchiveFolder] turns both into this.
         */
        var failDeleteOf: String? = null

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
            if (fileName == failDeleteOf) throw IOException("could not remove $fileName")
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
        zoneId = { london }
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
                "rename $expectedInProgressName -> $expectedName"
            ),
            folder.log.filterNot { it == "list" }.take(2)
        )
    }

    @Test
    fun `nothing is deleted until the replacement has landed`() = runTest {
        val folder = FakeFolder(
            listOf(
                "running-app-archive-2026-04-01-080000.zip",
                "running-app-archive-2026-05-01-080000.zip",
                "running-app-archive-2026-06-01-080000.zip"
            )
        )

        archiver(folder).archiveNow()

        val promotion = folder.log.indexOf("rename $expectedInProgressName -> $expectedName")
        val retirement = folder.log.indexOf("delete running-app-archive-2026-04-01-080000.zip")
        assertTrue(promotion in 0 until retirement)
        assertEquals(
            listOf(
                "running-app-archive-2026-05-01-080000.zip",
                "running-app-archive-2026-06-01-080000.zip",
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
        val folder = FakeFolder(listOf("running-app-archive-2026-06-01-080000.zip"))
        folder.failWriteOf = expectedInProgressName
        var recordedAt: Long? = null

        val outcome = archiver(folder, onArchived = { recordedAt = it }).archiveNow()

        assertTrue(outcome is ArchiveOutcome.Failed)
        assertEquals(listOf("running-app-archive-2026-06-01-080000.zip"), folder.files.keys.toList())
        assertNull(recordedAt)
    }

    @Test
    fun `a database snapshot that cannot be taken fails the whole archive`() = runTest {
        // #191: the database entry either holds a complete snapshot or there is no archive. An
        // archive whose database half lagged its own archive.json is the thing being prevented, so
        // a snapshot that throws has to carry the backup down with it rather than be logged past.
        val folder = FakeFolder(listOf("running-app-archive-2026-06-01-080000.zip"))
        var recordedAt: Long? = null
        val contents: suspend (Long) -> List<ArchiveEntry> = {
            listOf(
                ArchiveEntry.ofText(ArchiveJson.FILE_NAME, "{}"),
                ArchiveEntry("${ArchiveZip.DATABASE_DIRECTORY}/running_app_db") {
                    throw IllegalStateException("database is locked")
                }
            )
        }

        val outcome = archiver(folder, contents = contents, onArchived = { recordedAt = it })
            .archiveNow()

        assertTrue(outcome is ArchiveOutcome.Failed)
        assertEquals(listOf("running-app-archive-2026-06-01-080000.zip"), folder.files.keys.toList())
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

    /**
     * Two backups can still land in the same second — an empty history archives in no time — and the
     * second one must not take the name off the first. Clearing the name means deleting a finished
     * archive *before* the replacement can be promoted, and a folder that then refused the rename
     * would have taken the runner's only backup away.
     */
    @Test
    fun `backing up twice in the same second keeps the archive already written`() = runTest {
        val folder = FakeFolder()
        val archiver = archiver(folder)

        archiver.archiveNow()
        val first = folder.files.getValue(expectedName)
        folder.log.clear()
        val outcome = archiver.archiveNow()

        assertEquals(ArchiveOutcome.Archived(expectedName, julyMorning), outcome)
        assertEquals(listOf(expectedName), folder.files.keys.toList())
        // The archive standing there is the one that was there — never deleted, never rewritten.
        assertArrayEquals(first, folder.files.getValue(expectedName))
        assertEquals(emptyList<String>(), folder.log.filterNot { it == "list" })
    }

    /**
     * The archive is in the folder by the time the time is recorded, so a settings write that fails
     * — app-private storage full is exactly the condition a big backup can leave behind — must not
     * throw out of a backup that worked. The manual path launches this with nothing to catch it.
     */
    @Test
    fun `an archive still counts as made when the time cannot be recorded`() = runTest {
        val folder = FakeFolder()

        val outcome = archiver(folder, onArchived = { throw IOException("no space left") }).archiveNow()

        assertEquals(ArchiveOutcome.Archived(expectedName, julyMorning), outcome)
        assertEquals(listOf(expectedName), folder.files.keys.toList())
    }

    @Test
    fun `wreckage left by an earlier failure is swept up by the next backup`() = runTest {
        val folder = FakeFolder(listOf("running-app-archive-2026-06-01-080000.zip.part"))

        archiver(folder).archiveNow()

        assertEquals(listOf(expectedName), folder.files.keys.toList())
    }

    /**
     * A folder that will not give up the name is the case where a promotion can *look* like it
     * worked: the old archive would still be sitting under the new name, satisfying every check,
     * while the archive just written stayed a `.part`.
     *
     * Only reachable through a folder that will not be listed — a listable one shows the name is
     * taken and the backup already there is kept instead of cleared.
     */
    @Test
    fun `a name that cannot be cleared fails the backup rather than claiming it`() = runTest {
        val folder = FakeFolder(listOf(expectedName)).apply {
            failList = true
            failDeleteOf = expectedName
        }
        folder.files[expectedName] = "yesterday".toByteArray()
        var recordedAt: Long? = null

        val outcome = archiver(folder, onArchived = { recordedAt = it }).archiveNow()

        assertTrue(outcome is ArchiveOutcome.Failed)
        assertNull(recordedAt)
        // The finished archive that was there is untouched, and the new one waits under its part
        // name — the newest complete copy in the folder, for the next attempt to promote.
        assertEquals("yesterday", String(folder.files.getValue(expectedName)))
        assertTrue(folder.files.containsKey(expectedInProgressName))
    }

    /**
     * The button and the monthly job share one archiver, so this is the shape of a real overlap:
     * the second attempt must not touch the folder while the first is still writing into it — they
     * would be writing the same `.part` under the same name.
     */
    @Test
    fun `a second backup asked for mid-write waits rather than joining in`() = runTest {
        val folder = FakeFolder()
        val firstIsWriting = CompletableDeferred<Unit>()
        val letTheFirstFinish = CompletableDeferred<Unit>()
        var isFirst = true
        val archiver = archiver(folder, contents = {
            if (isFirst) {
                isFirst = false
                firstIsWriting.complete(Unit)
                letTheFirstFinish.await()
            }
            listOf(ArchiveEntry.ofText(ArchiveJson.FILE_NAME, "{}"))
        })

        val first = launch { archiver.archiveNow() }
        firstIsWriting.await()
        val second = launch { archiver.archiveNow() }
        runCurrent()

        // The first is suspended mid-write, so the folder has seen exactly its one write.
        assertEquals(
            listOf("write $expectedInProgressName"),
            folder.log.filterNot { it == "list" }
        )

        letTheFirstFinish.complete(Unit)
        first.join()
        second.join()

        // The second waited, found the first's archive already standing under the name it wanted,
        // and left it alone — so the folder is left holding one archive, and it is the one that was
        // written whole rather than a replacement that had to clear it first.
        assertEquals(listOf(expectedName), folder.files.keys.toList())
        assertEquals(
            listOf(
                "write $expectedInProgressName",
                "rename $expectedInProgressName -> $expectedName"
            ),
            folder.log.filterNot { it == "list" }
        )
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
