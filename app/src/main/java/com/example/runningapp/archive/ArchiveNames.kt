package com.example.runningapp.archive

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What an archive is called, and which archives a fresh one retires (#85).
 *
 * Pure, and separate from the folder it writes to, because the rotation rule is the one part of
 * backing up that can *destroy* something: everything else in this feature either writes a new file
 * or fails. A rule that decides what to delete belongs somewhere it can be read and tested without
 * a phone attached.
 */
object ArchiveNames {

    /**
     * How many archives the folder keeps, newest first — the spec's number (#77).
     *
     * Three is enough to survive noticing a problem late: if the newest archive turns out to have
     * been written from a database that was already wrong, there are two older ones behind it.
     */
    const val KEEP = 3

    const val PREFIX = "running-app-archive-"
    const val EXTENSION = ".zip"

    /**
     * The suffix an archive wears while it is still being written.
     *
     * A ZIP is only a ZIP once its central directory has landed at the end, so an interrupted write
     * leaves a file that looks like an archive and is not one. Writing under this name and renaming
     * on success means nothing in the folder is ever a half-archive — the same discipline
     * [com.example.runningapp.data.DatabaseBackupManager] uses on the Downloads snapshot.
     */
    const val IN_PROGRESS_EXTENSION = ".part"

    /**
     * Sorts oldest-first as text, which is what makes [retire] a matter of taking from the front
     * rather than parsing every name back into a date. Local time, not UTC: the runner reading the
     * folder is the person these names are for.
     */
    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.UK)

    fun archiveName(atEpochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        PREFIX + TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(atEpochMillis).atZone(zoneId)) + EXTENSION

    fun inProgressName(atEpochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        archiveName(atEpochMillis, zoneId) + IN_PROGRESS_EXTENSION

    /** Whether a file in the folder is one of this app's finished archives. */
    fun isArchive(fileName: String): Boolean =
        fileName.startsWith(PREFIX) && fileName.endsWith(EXTENSION)

    /** Whether a file is the wreckage of an archive that never finished. */
    fun isAbandoned(fileName: String): Boolean =
        fileName.startsWith(PREFIX) && fileName.endsWith(IN_PROGRESS_EXTENSION)

    /**
     * Which of the folder's files a completed backup should now delete.
     *
     * Two kinds, and the distinction matters:
     *  - **archives beyond [keep]**, oldest first. The just-written one is expected to be in
     *     [fileNames] already, so keeping three means the caller ends up with three, not four.
     *  - **abandoned part-files**, all of them. They are unreadable by construction, and left alone
     *     they would accumulate one per failed backup for ever.
     *
     * Anything else in the folder is untouched. The runner picked a folder, not a private
     * directory — it may be their Drive root, full of things that are none of this app's business.
     */
    fun retire(fileNames: List<String>, keep: Int = KEEP): List<String> {
        val archives = fileNames.filter(::isArchive).sorted()
        val surplus = (archives.size - keep).coerceAtLeast(0)
        return archives.take(surplus) + fileNames.filter(::isAbandoned)
    }
}
