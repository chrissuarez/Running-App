package com.example.runningapp.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveZipTest {

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val contents = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                contents[entry.name] = zip.readBytes()
            }
        }
        return contents
    }

    @Test
    fun `an archive holds a GPX per run, the JSON and the database snapshot`() = runTest {
        val out = ByteArrayOutputStream()

        ArchiveZip.write(
            out,
            listOf(
                ArchiveEntry.ofText("${ArchiveZip.ACTIVITIES_DIRECTORY}/run-one.gpx", "<gpx>one</gpx>"),
                ArchiveEntry.ofText("${ArchiveZip.ACTIVITIES_DIRECTORY}/run-two.gpx", "<gpx>two</gpx>"),
                ArchiveEntry.ofText(ArchiveJson.FILE_NAME, "{}"),
                ArchiveEntry("${ArchiveZip.DATABASE_DIRECTORY}/running_app_db") { stream ->
                    stream.write(byteArrayOf(1, 2, 3))
                }
            )
        )

        val contents = unzip(out.toByteArray())
        assertEquals(
            setOf(
                "activities/run-one.gpx",
                "activities/run-two.gpx",
                "archive.json",
                "database/running_app_db"
            ),
            contents.keys
        )
        assertEquals("<gpx>one</gpx>", contents["activities/run-one.gpx"]?.toString(Charsets.UTF_8))
        assertEquals(listOf<Byte>(1, 2, 3), contents["database/running_app_db"]?.toList())
    }

    @Test
    fun `the stream the caller opened is left open for them to close`() = runTest {
        var closed = false
        val out = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
                super.close()
            }
        }

        ArchiveZip.write(out, listOf(ArchiveEntry.ofText("archive.json", "{}")))

        assertFalse(closed)
        // Finished all the same: a ZIP without its central directory is not readable at all.
        assertTrue(unzip(out.toByteArray()).containsKey("archive.json"))
    }

    @Test
    fun `a run with nothing to say still leaves a readable archive`() = runTest {
        val out = ByteArrayOutputStream()

        ArchiveZip.write(out, emptyList())

        assertEquals(emptyMap<String, ByteArray>(), unzip(out.toByteArray()))
    }
}
