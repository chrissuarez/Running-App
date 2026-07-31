package com.example.runningapp.restore

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import com.example.runningapp.archive.ArchiveJson
import com.example.runningapp.archive.ArchiveZip
import com.example.runningapp.archive.ArchivedSettings
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Turns a file the runner picked into something the app can describe and then restore (#86, #198).
 *
 * Reading and staging are the same act here, deliberately. To say anything true about a picked file
 * — how many runs, how recent, what version wrote it — the database inside it has to be opened, and
 * to open it, it has to be a file on this app's own storage rather than a stream from another app's
 * document provider. So the copy that answers the question *is* the copy that gets moved into place
 * later. Nothing is read twice, and nothing can change between the screen the runner agreed to and
 * the database they end up with.
 *
 * Nothing here touches the live database. [stage] only ever writes inside [stagingDirectory], and
 * the live file is not so much as opened until [PendingRestore] applies the staged copy at the next
 * launch. A runner who reads the confirmation and backs out has lost nothing but a temporary file.
 */
object RestoreReader {
    private const val TAG = "DbRestore"

    /** Where a picked file is unpacked and held between the pick and the confirmation. */
    private const val STAGING_DIRECTORY = "restore"

    /** The database ready to be moved into place — extracted from an archive, or the pick itself. */
    internal const val STAGED_DATABASE = "staged.db"

    /** The archive's settings, kept beside it so a pick survives this process being killed. */
    internal const val STAGED_SETTINGS = "staged-settings.json"

    internal fun stagingDirectory(context: Context) = File(context.filesDir, STAGING_DIRECTORY)

    /** What a pick turned into: something restorable and described, or a refusal with a reason. */
    sealed interface Outcome {
        data class Staged(val summary: RestoreSummary) : Outcome
        data class Refused(val reason: RestoreRefusal) : Outcome
    }

    /**
     * Copies [uri] into staging, works out what it is, and reports what it holds.
     *
     * Call from a background thread: this copies a whole database across a content provider.
     *
     * Any previous staging is cleared first. Two picks in a row must not leave the first one's
     * database sitting where the second one's settings will be read beside it — a mismatched pair
     * would restore one file's runs under another file's max heart rate, which is a quiet way to
     * strand every restored run on a profile nobody chose.
     */
    fun stage(context: Context, uri: Uri, currentDatabaseVersion: Int): Outcome {
        val directory = stagingDirectory(context)
        clear(context)
        directory.mkdirs()
        return try {
            val staged = File(directory, STAGED_DATABASE)
            val kind = context.contentResolver.openInputStream(uri)?.use { input ->
                unpack(input, staged, File(directory, STAGED_SETTINGS))
            } ?: return refuse(context, RestoreRefusal.NOT_A_BACKUP)
            when (kind) {
                Unpacked.NOT_A_BACKUP -> return refuse(context, RestoreRefusal.NOT_A_BACKUP)
                Unpacked.ARCHIVE_HAS_NO_DATABASE ->
                    return refuse(context, RestoreRefusal.ARCHIVE_HAS_NO_DATABASE)
                Unpacked.DATABASE, Unpacked.ARCHIVE -> Unit
            }
            val fileKind =
                if (kind == Unpacked.ARCHIVE) RestoreFileKind.ARCHIVE else RestoreFileKind.DATABASE
            // Read now, so the confirmation promises settings only when there are settings to
            // promise: an archive whose `archive.json` this app cannot parse is still restorable
            // for its history, and it must not say otherwise. Parsed twice — once here to tell the
            // truth, once at the restore to apply it — which is a few hundred bytes either way.
            val carriesSettings = fileKind == RestoreFileKind.ARCHIVE &&
                stagedSettings(context) != null
            val summary = summarise(staged, fileKind, carriesSettings)
                ?: return refuse(context, RestoreRefusal.UNREADABLE)
            when (val eligibility = RestoreEligibility.of(summary, currentDatabaseVersion)) {
                is RestoreEligibility.Refused -> refuse(context, eligibility.reason)
                is RestoreEligibility.Allowed -> Outcome.Staged(eligibility.summary)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the picked backup", e)
            refuse(context, RestoreRefusal.UNREADABLE)
        }
    }

    /** The settings an already-staged archive carried, or null for a bare database pick. */
    fun stagedSettings(context: Context): ArchivedSettings? {
        val file = File(stagingDirectory(context), STAGED_SETTINGS)
        if (!file.exists()) return null
        return runCatching { ArchiveJson.read(file.readText())?.settings }.getOrNull()
    }

    /** Throws away anything staged. Safe to call when nothing is. */
    fun clear(context: Context) {
        stagingDirectory(context).deleteRecursively()
    }

    private fun refuse(context: Context, reason: RestoreRefusal): Outcome {
        // A refused pick leaves nothing behind: the runner will pick again, and a half-unpacked
        // archive under the name the next pick writes to is exactly the mismatched pair above.
        clear(context)
        return Outcome.Refused(reason)
    }

    private enum class Unpacked { DATABASE, ARCHIVE, ARCHIVE_HAS_NO_DATABASE, NOT_A_BACKUP }

    /**
     * Writes the database out of [input] into [database], and an archive's settings into [settings].
     *
     * The first bytes decide which of the two file kinds this is — see [RestoreFileKind.detect] for
     * why the name is not consulted. They are consumed from the stream and then written back into
     * the copy, because a document provider's stream cannot be rewound.
     */
    private fun unpack(input: InputStream, database: File, settings: File): Unpacked {
        val head = ByteArray(RestoreFileKind.MAGIC_BYTES)
        val headLength = input.readAtMost(head)
        val kind = RestoreFileKind.detect(head.copyOf(headLength)) ?: return Unpacked.NOT_A_BACKUP
        val whole = SequencedInputStream(head.copyOf(headLength), input)
        return when (kind) {
            RestoreFileKind.DATABASE -> {
                database.outputStream().use { whole.copyTo(it) }
                Unpacked.DATABASE
            }
            RestoreFileKind.ARCHIVE -> unpackArchive(whole, database, settings)
        }
    }

    /**
     * Pulls the snapshot and `archive.json` out of an archive zip.
     *
     * Any entry under `database/` counts as the snapshot rather than one exact path: the archive
     * writes it under the live database's file name, and a restore should not start failing because
     * that name changed in some later version. There is only ever one file in that directory.
     *
     * A missing `archive.json` is not fatal — the runs are the part that matters, and an archive
     * from some future version whose settings this app cannot parse still holds a perfectly good
     * database. A missing snapshot is fatal, because then there is nothing to restore.
     */
    private fun unpackArchive(input: InputStream, database: File, settings: File): Unpacked {
        val databasePrefix = "${ArchiveZip.DATABASE_DIRECTORY}/"
        var foundDatabase = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when {
                    entry.isDirectory -> Unit
                    !foundDatabase && name.startsWith(databasePrefix) -> {
                        database.outputStream().use { zip.copyTo(it) }
                        foundDatabase = true
                    }
                    name == ArchiveJson.FILE_NAME ->
                        settings.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        return if (foundDatabase) Unpacked.ARCHIVE else Unpacked.ARCHIVE_HAS_NO_DATABASE
    }

    /**
     * What [database] holds, or null if it is not this app's database after all.
     *
     * Opened read-only, so a snapshot that turns out to be corrupt is discovered *here* — before
     * anything has been replaced — rather than at the next launch with the previous history already
     * gone. That is #86's "restoring an invalid file fails safely with current data untouched", and
     * it is the same read that lets the confirmation quote real numbers: one open, both jobs.
     */
    private fun summarise(
        database: File,
        kind: RestoreFileKind,
        carriesSettings: Boolean,
    ): RestoreSummary? {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                database.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            val version = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else return null
            }
            db.rawQuery("SELECT COUNT(*), MAX(startTime) FROM sessions", null).use { cursor ->
                if (!cursor.moveToFirst()) return null
                RestoreSummary(
                    kind = kind,
                    runCount = cursor.getInt(0),
                    // MAX over no rows is SQL NULL — a backup holding no runs, which restores fine.
                    newestRunStartedAtEpochMillis = if (cursor.isNull(1)) null else cursor.getLong(1),
                    databaseVersion = version,
                    carriesSettings = carriesSettings,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Picked file is not a readable run database", e)
            null
        } finally {
            runCatching { db?.close() }
        }
    }

    /** Fills as much of [buffer] as the stream has, returning how many bytes landed. */
    private fun InputStream.readAtMost(buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val read = read(buffer, filled, buffer.size - filled)
            if (read <= 0) break
            filled += read
        }
        return filled
    }

    /** [head] again, then the rest of [rest] — a stream that has un-read its own first bytes. */
    private class SequencedInputStream(
        private val head: ByteArray,
        private val rest: InputStream,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int =
            if (position < head.size) head[position++].toInt() and 0xFF else rest.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= head.size) return rest.read(buffer, offset, length)
            val fromHead = minOf(length, head.size - position)
            head.copyInto(buffer, offset, position, position + fromHead)
            position += fromHead
            return fromHead
        }

        override fun close() = rest.close()
    }
}
