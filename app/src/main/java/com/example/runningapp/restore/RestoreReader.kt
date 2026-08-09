package com.example.runningapp.restore

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import com.example.runningapp.UserSettings
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
 * later. Nothing is read twice, and no other file can be substituted between the screen the runner
 * agreed to and the database they end up with.
 *
 * That copy is also *migrated* before the confirmation, by [RestoreTrialOpen] (#201) — so the only
 * thing that changes about it between the reading and the restore is the schema version, and it
 * changes because Room has proved it can. The summary keeps quoting the file as picked, because
 * that is what the runner is being asked about.
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
     * Call from a background thread: this copies a whole database across a content provider and
     * then migrates it.
     *
     * A file only ever comes back [Outcome.Staged] once Room has opened it here, in staging (#201).
     * What that leaves behind is a copy already at today's schema — the numbers in the returned
     * summary are still the ones read off the file as picked, because they are what the runner is
     * being asked about, but the file itself has moved on and the swap installs a database that
     * needs nothing further.
     *
     * Any previous staging is cleared first. Two picks in a row must not leave the first one's
     * database sitting where the second one's settings will be read beside it — a mismatched pair
     * would restore one file's runs under another file's max heart rate, which is a quiet way to
     * strand every restored run on a profile nobody chose.
     */
    fun stage(
        context: Context,
        uri: Uri,
        currentDatabaseVersion: Int,
        phoneSettings: UserSettings,
    ): Outcome {
        // A restore still armed while the app is running is the one that got its history in place
        // and not its settings, and is waiting for the next launch to finish. Its settings are the
        // only copy left, and the clear below would delete them — so a second pick, even one that
        // goes on to be refused or backed out of, would strand the restored runs on the previous
        // phone's profile and plan for good. Say no until the relaunch has finished the first one.
        if (PendingRestore.isArmed(context)) {
            return Outcome.Refused(RestoreRefusal.A_RESTORE_IS_UNFINISHED)
        }
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
            val archivedSettings =
                if (fileKind == RestoreFileKind.ARCHIVE) stagedSettings(context) else null
            val summary = summarise(staged, fileKind, carriesSettings = archivedSettings != null)
                ?: return refuse(context, RestoreRefusal.UNREADABLE)
            when (val eligibility = RestoreEligibility.of(summary, currentDatabaseVersion)) {
                is RestoreEligibility.Refused -> return refuse(context, eligibility.reason)
                is RestoreEligibility.Allowed -> Unit
            }
            // Last, because it is much the most expensive question and the cheap refusals above
            // have already turned away everything they can — including a backup from a newer app,
            // which Room could only answer by refusing to open it, and which deserves its own
            // sentence. Everything still here is a backup this app should be able to carry
            // forward, and this is where that stops being an assumption.
            //
            // The profile the migration bands on is whichever one belongs to *this* history — the
            // same choice AppContainer makes, through the same function, because the trial has to
            // migrate the file the way the launch would or it is proving something about a database
            // the runner will never have.
            val migrationHrProfile = restoredHistoryHrProfile(archivedSettings, phoneSettings)
            if (!RestoreTrialOpen.migrateInStaging(context, staged, migrationHrProfile)) {
                return refuse(context, RestoreRefusal.CANNOT_BE_MIGRATED)
            }
            Outcome.Staged(summary)
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
     *
     * Three questions, because reading `sessions` alone answers none of them. `quick_check` is
     * SQLite's own verdict on whether the pages hang together, which is the only way to catch a
     * download that stopped halfway or a file that came back damaged through a chat app — the runs
     * table can read perfectly while a table nobody queries here is shredded. `room_master_table` is
     * the one thing every Room database has and no hand-made SQLite file does, so it separates this
     * app's backup from somebody else's database that happens to have a `sessions` table. Only then
     * does the count mean anything.
     *
     * What is deliberately *not* checked here is the full schema. A backup from an older app is the
     * ordinary case and legitimately lacks tables and columns added since; Room's migrations exist
     * to build them. Demanding today's schema would refuse exactly the backups this feature is for.
     * Whether Room will accept the file is a separate and much more expensive question, and it is
     * asked afterwards by [RestoreTrialOpen] — these three are the cheap refusals, and they stay
     * cheap so that a damaged download never pays for a migration.
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
            if (!db.passesIntegrityCheck()) {
                Log.w(TAG, "Picked backup failed its integrity check")
                return null
            }
            if (!db.hasRoomIdentity()) {
                Log.w(TAG, "Picked file is a database, but not one Room wrote")
                return null
            }
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

    /**
     * SQLite's own answer to "is this file whole?" — `ok` on the first row, or the first thing it
     * found wrong.
     *
     * `quick_check` rather than `integrity_check`: it skips the index cross-check, which is the
     * slowest part and the one thing a restore does not need, because Room rebuilds indexes it finds
     * wrong. On a history of a few thousand runs this is the difference between a pause the runner
     * notices and one they do not.
     */
    private fun SQLiteDatabase.passesIntegrityCheck(): Boolean =
        rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
            cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
        }

    /** Whether Room wrote this database — its bookkeeping table, present in every version. */
    private fun SQLiteDatabase.hasRoomIdentity(): Boolean =
        rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf("room_master_table"),
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) > 0 }

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
