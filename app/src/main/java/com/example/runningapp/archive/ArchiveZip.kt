package com.example.runningapp.archive

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One thing an archive carries, and how to write it out.
 *
 * The contents are written on demand rather than handed over as bytes, so an archive of a year's
 * running never has to exist in memory all at once: the database snapshot streams from the file it
 * is a copy of, and each run's GPX is built as its turn comes and dropped again after.
 */
class ArchiveEntry(
    /** Path inside the ZIP, directories included — e.g. `activities/run-2026-07-30-0712-41.gpx`. */
    val path: String,
    /**
     * Suspending because a run's GPX is built from the database as its turn comes: the whole
     * reason contents are written on demand is so the archive never has to be held in memory.
     */
    val writeTo: suspend (OutputStream) -> Unit
) {
    companion object {
        fun ofText(path: String, contents: String) =
            ArchiveEntry(path) { out -> out.write(contents.toByteArray(Charsets.UTF_8)) }
    }
}

/**
 * Writes the entries of an archive into one ZIP (#85).
 *
 * Deliberately knows nothing about runs, folders or Android: hand it entries and a stream and it
 * makes a ZIP, which is what lets the shape of an archive be checked on a laptop rather than
 * discovered on a phone.
 *
 * The stream is **not** closed here. Whoever opened it — a `content://` stream into the runner's
 * chosen folder, a byte array in a test — closes it, and only a ZIP whose central directory has
 * been written is a ZIP at all, so [finish] rather than `close` is what this promises.
 */
object ArchiveZip {

    /** Where each run's GPX lands, so the archive reads as a folder of activities. */
    const val ACTIVITIES_DIRECTORY = "activities"

    /** Where the raw database snapshot lands — the half a restore actually reads (#86). */
    const val DATABASE_DIRECTORY = "database"

    /**
     * Where the Run Journal lands (#310). Nothing restores from it; it is here so a Run that died
     * on a phone weeks ago can still be diagnosed from the backup.
     */
    const val DIAGNOSTICS_DIRECTORY = "diagnostics"

    suspend fun write(destination: OutputStream, entries: Iterable<ArchiveEntry>) {
        val zip = ZipOutputStream(destination)
        entries.forEach { entry ->
            zip.putNextEntry(ZipEntry(entry.path))
            entry.writeTo(zip)
            zip.closeEntry()
        }
        // Ends the ZIP without closing the stream underneath it: the caller owns that.
        zip.finish()
        zip.flush()
    }
}
