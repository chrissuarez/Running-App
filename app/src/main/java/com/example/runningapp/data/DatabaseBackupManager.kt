package com.example.runningapp.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Keeps a spare copy of the run database in the phone's public Downloads folder so run history
 * survives **"Clear storage"** — which wipes the app's private database directory but leaves this
 * copy behind.
 *
 * Scope, deliberately: this covers Clear-storage on the *same* install, not reinstall. Under scoped
 * storage a reinstalled app is a new owner and can no longer read a `MediaStore.Downloads` entry the
 * previous install created (that would need a Storage Access Framework grant). **Reinstall recovery
 * is handled separately by Android Auto Backup**, which restores the Room database at install time,
 * before this ever runs — so on reinstall the database already exists and [restoreIfDatabaseMissing]
 * correctly no-ops. The two layers are complementary: Auto Backup for reinstall, this for
 * Clear-storage.
 *
 * - [backup] runs after each finished run and after a run is deleted: it snapshots the live
 *   database into `Downloads/RunningApp/` (overwriting the previous snapshot). Refreshing on delete
 *   matters as much as on finish — otherwise a stale snapshot would restore deleted runs.
 * - [restoreIfDatabaseMissing] runs once at startup, *before Room opens*, and only when the app has
 *   no database of its own yet — a freshly-cleared install. It reads back the copy this same install
 *   wrote, never overwrites a database that already exists, so it can only add history back.
 *
 * Everything here is best-effort: any failure is logged and swallowed, because losing a backup must
 * never crash a run or block launch.
 *
 * The Downloads collection is writable without a runtime permission only from API 29 (scoped
 * storage). Below that the feature is a no-op — the app's minSdk-26 devices simply go unprotected
 * rather than dragging in a `WRITE_EXTERNAL_STORAGE` permission prompt.
 */
object DatabaseBackupManager {
    private const val TAG = "DbBackup"

    /** Must match the name passed to Room in [AppDatabase.getDatabase]. */
    const val DATABASE_NAME = "running_app_db"

    private const val BACKUP_DISPLAY_NAME = "running_app_history_backup.db"
    // The in-progress snapshot is written under this name first and only promoted to
    // BACKUP_DISPLAY_NAME once complete, so a failed write can't destroy the previous good backup.
    private const val BACKUP_TEMP_DISPLAY_NAME = "running_app_history_backup.tmp.db"
    private const val BACKUP_SUBDIR = "RunningApp"
    private const val BACKUP_MIME = "application/octet-stream"
    private val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_SUBDIR"

    // A blocked TRUNCATE checkpoint (busy=1) is transient — a UI query holding a read snapshot —
    // so retry a few times to ride it out before giving up and copying the file anyway.
    private const val CHECKPOINT_ATTEMPTS = 4
    private const val CHECKPOINT_RETRY_DELAY_MS = 50L

    private val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    // Serializes concurrent backups. Two can overlap — e.g. the post-run snapshot from
    // the Run's finalization and the one from SessionRepository.saveFeelFeedback() — and
    // they share the single BACKUP_TEMP_DISPLAY_NAME entry, so without this the second writer would
    // delete the first's pending item and corrupt or drop the snapshot. Whichever runs last wins,
    // and both are complete snapshots, so serializing (rather than skipping) is correct.
    private val backupLock = Any()

    /**
     * Snapshots the live database to Downloads. Safe to call from any background thread; concurrent
     * calls are serialized on [backupLock].
     *
     * Takes the open [database] so the WAL checkpoint runs on Room's own connection — the one
     * holding the log. Folding it there guarantees the copied `.db` includes the run that just
     * finished; a checkpoint issued from a second connection can silently no-op under Room's lock.
     */
    fun backup(context: Context, database: AppDatabase) {
        if (!isSupported) return
        synchronized(backupLock) {
            try {
                val dbFile = context.getDatabasePath(DATABASE_NAME)
                if (!dbFile.exists()) return
                if (!checkpointWal(database)) {
                    // Couldn't fully fold the WAL — a reader held a snapshot through every retry.
                    // Copy anyway (better a slightly-stale backup than none), but say so rather than
                    // logging an unqualified success.
                    Log.w(TAG, "WAL checkpoint stayed blocked; backup may lag the most recent write")
                }
                val bytes = dbFile.readBytes()
                writeBackupBytes(context, bytes)
                Log.d(TAG, "Backed up ${bytes.size} bytes of run history to Downloads/$BACKUP_SUBDIR")
            } catch (e: Exception) {
                Log.w(TAG, "History backup failed (non-fatal)", e)
            }
        }
    }

    /**
     * Folds the WAL into the main `.db` so the copied file includes the latest write. A TRUNCATE
     * checkpoint reports a blocked run in its result row (busy=1) instead of throwing — e.g. when a
     * UI query is holding a read snapshot — leaving recent frames only in `-wal`. Retry a few times
     * to ride out that transient reader. Returns true once a checkpoint completes cleanly.
     */
    private fun checkpointWal(database: AppDatabase): Boolean {
        val db = database.openHelper.writableDatabase
        repeat(CHECKPOINT_ATTEMPTS) { attempt ->
            val complete = db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                // Row is (busy, logFrames, checkpointedFrames). busy=0 means the checkpoint got the
                // lock and folded everything it could; an empty row means the db is not in WAL mode.
                if (!cursor.moveToFirst()) return true
                cursor.getInt(0) == 0
            }
            if (complete) return true
            if (attempt < CHECKPOINT_ATTEMPTS - 1) Thread.sleep(CHECKPOINT_RETRY_DELAY_MS)
        }
        return false
    }

    /**
     * Restores the database from the Downloads copy this install wrote, when the app has none of its
     * own — i.e. after "Clear storage". Returns true if a file was written into place. Call this
     * before Room first opens the database.
     *
     * After a reinstall this is a no-op by design: Auto Backup has already restored the database (so
     * it exists and we return early), and even if it hadn't, the reinstalled app can't read the
     * previous install's Downloads entry under scoped storage. Reinstall recovery is Auto Backup's
     * job, not this one's.
     */
    fun restoreIfDatabaseMissing(context: Context): Boolean {
        if (!isSupported) return false
        return try {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (dbFile.exists()) return false // never overwrite a live database
            val bytes = readBackupBytes(context) ?: return false
            dbFile.parentFile?.mkdirs()
            // Write to a sibling temp file and rename it into place only once the full copy lands.
            // A direct write to dbFile could be interrupted (process death, storage full) partway,
            // leaving a truncated file that the next launch mistakes for a live database — Room
            // would then open a corrupt DB instead of retrying the restore. A same-directory rename
            // is atomic, so dbFile is only ever the complete snapshot or absent.
            val tempFile = File("${dbFile.path}.restore.tmp")
            tempFile.writeBytes(bytes)
            if (!tempFile.renameTo(dbFile)) {
                tempFile.delete()
                throw IllegalStateException("Could not move restored database into place")
            }
            // A restored file is already checkpointed; drop any stale WAL/SHM siblings so SQLite
            // reads exactly what we wrote rather than replaying an old, unrelated log.
            File("${dbFile.path}-wal").delete()
            File("${dbFile.path}-shm").delete()
            Log.d(TAG, "Restored ${bytes.size} bytes of run history from Downloads/$BACKUP_SUBDIR")
            true
        } catch (e: Exception) {
            Log.w(TAG, "History restore failed (non-fatal)", e)
            false
        }
    }

    private fun writeBackupBytes(context: Context, bytes: ByteArray) {
        val resolver = context.contentResolver
        // Write the replacement under a temp name first. The previous good snapshot is only
        // removed once the new one is fully written, so an insert/write/finalize failure here
        // leaves the user's existing backup intact instead of wiping their only copy.
        deleteByDisplayName(context, BACKUP_TEMP_DISPLAY_NAME) // clear any leftover temp
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_TEMP_DISPLAY_NAME)
            put(MediaStore.Downloads.MIME_TYPE, BACKUP_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val tempUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert returned no URI")
        try {
            resolver.openOutputStream(tempUri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not open output stream for backup")
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(tempUri, done, null, null)
        } catch (e: Exception) {
            // Roll back the half-written temp entry; the old snapshot was never touched.
            runCatching { resolver.delete(tempUri, null, null) }
            throw e
        }
        // The new snapshot is complete. Retire the old one, then promote temp to the real name.
        deleteByDisplayName(context, BACKUP_DISPLAY_NAME)
        val rename = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_DISPLAY_NAME) }
        resolver.update(tempUri, rename, null, null)
    }

    private fun readBackupBytes(context: Context): ByteArray? {
        // Prefer the promoted backup. If it's missing we may have been killed (or the rename may
        // have failed) mid-promotion: writeBackupBytes deletes the old copy just before it renames
        // the temp to the real name, so the completed temp can briefly be the only good snapshot.
        // Fall back to it — but only when it's finished (IS_PENDING = 0) so a half-written temp from
        // an interrupted write is never restored.
        val uri = findByDisplayName(context, BACKUP_DISPLAY_NAME)
            ?: findByDisplayName(context, BACKUP_TEMP_DISPLAY_NAME, onlyComplete = true)
            ?: return null
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    private fun deleteByDisplayName(context: Context, displayName: String) {
        val uri = findByDisplayName(context, displayName) ?: return
        context.contentResolver.delete(uri, null, null)
    }

    /**
     * Locates a backup entry in Downloads by relative path + display name, or null. When
     * [onlyComplete] is set, ignores an entry still marked pending (a half-written temp).
     */
    private fun findByDisplayName(
        context: Context,
        displayName: String,
        onlyComplete: Boolean = false,
    ): Uri? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        var selection =
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ?"
        if (onlyComplete) selection += " AND ${MediaStore.Downloads.IS_PENDING} = 0"
        // RELATIVE_PATH comes back with a trailing slash, so match on a prefix.
        val args = arrayOf("$RELATIVE_PATH%", displayName)
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return android.content.ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }
}
