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
     *
     * Seconds, not minutes, because every name two backups can share is a name they can collide on,
     * and two backups a minute apart are ordinary where two inside one second are not. Rare rather
     * than impossible, though — an empty history archives in no time — so the archiver never takes
     * an occupied name: see [Archiver], which keeps the archive already standing there instead.
     */
    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.UK)

    fun archiveName(atEpochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        PREFIX + TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(atEpochMillis).atZone(zoneId)) + EXTENSION

    fun inProgressName(atEpochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        archiveName(atEpochMillis, zoneId) + IN_PROGRESS_EXTENSION

    /**
     * The exact shape of a name this app wrote: the prefix, a timestamp of the fixed width
     * [TIMESTAMP_FORMAT] produces, and nothing else before the extension.
     *
     * Matched whole rather than by prefix and suffix, because these two predicates are the ones that
     * decide what [retire] *deletes*, and the folder belongs to the runner, not to this app. A file
     * they called `running-app-archive-family.zip` sits between the same two ends as a real archive
     * and would otherwise be retired as one — the folder's fourth archive deleting a photo album.
     */
    /**
     * Four digits as well as six, because the folder is not empty when the format changes.
     *
     * Archives written before [TIMESTAMP_FORMAT] gained its seconds are sitting in the runner's
     * folder wearing `HHmm`, and a rule that no longer recognises them would never retire them
     * either: three new archives would rotate correctly while the old ones accumulated beside them
     * for ever, and any `.part` left by an earlier failure would never be swept. They are this app's
     * files, and the rotation is supposed to be the whole of what this app leaves behind.
     *
     * They still sort correctly among the new ones, which is what [retire] depends on: the date
     * leads, and within one minute the shorter name sorts first — which is the older of the two.
     */
    private const val TIMESTAMP = """\d{4}-\d{2}-\d{2}-\d{4}(?:\d{2})?"""

    private val ARCHIVE_NAME = Regex("""^\Q$PREFIX\E$TIMESTAMP\Q$EXTENSION\E$""")
    private val IN_PROGRESS_NAME =
        Regex("""^\Q$PREFIX\E$TIMESTAMP\Q$EXTENSION$IN_PROGRESS_EXTENSION\E$""")

    /** Whether a file in the folder is one of this app's finished archives. */
    fun isArchive(fileName: String): Boolean = ARCHIVE_NAME.matches(fileName)

    /** Whether a file is the wreckage of an archive that never finished. */
    fun isAbandoned(fileName: String): Boolean = IN_PROGRESS_NAME.matches(fileName)

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
     *
     * [justWritten] is the archive this backup has only just promoted, and it is never retired
     * whatever the sort says. These names carry local wall-clock time, so a phone that has moved to
     * an earlier time zone — or simply lived through the October clock change — can write an archive
     * whose name sorts *before* ones already in the folder. Without this the fourth backup could
     * delete itself the instant it landed, and still record a last-backup time: the one claim this
     * class exists to keep honest. Ordering among the older archives can be an hour out across a
     * clock change, which costs at most a slightly newer archive retiring before a slightly older
     * one — a different thing entirely from losing the backup just made.
     */
    fun retire(
        fileNames: List<String>,
        keep: Int = KEEP,
        justWritten: String? = null
    ): List<String> {
        val archives = fileNames.filter(::isArchive).sorted()
        val surplus = (archives.size - keep).coerceAtLeast(0)
        val retired = archives.filterNot { it == justWritten }.take(surplus)
        return retired + fileNames.filter(::isAbandoned)
    }
}
