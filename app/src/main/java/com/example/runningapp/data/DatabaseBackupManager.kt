package com.example.runningapp.data

import android.content.ContentUris
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
 *   database into `Downloads/RunningApp/`, then retires the copies it supersedes. Refreshing on
 *   delete matters as much as on finish — otherwise a stale snapshot would restore deleted runs.
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

    // The name every snapshot asks for. It does not always get it — see writeBackupBytes.
    private const val BACKUP_DISPLAY_NAME = "running_app_history_backup.db"
    // Every snapshot this install has ever written shares this prefix — including the numbered
    // "running_app_history_backup (7).db" names MediaStore invents when it won't overwrite, and the
    // "running_app_history_backup.tmp.db" left over from the old promote-by-rename write. The backup
    // is identified by *recency within the folder*, not by an exact name; see findNewestBackup for
    // why the name can't be trusted to be the one we asked for.
    private const val BACKUP_NAME_PREFIX = "running_app_history_backup"
    private const val BACKUP_SUBDIR = "RunningApp"
    private const val BACKUP_MIME = "application/octet-stream"
    private val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_SUBDIR"

    // A blocked TRUNCATE checkpoint (busy=1) is transient — a UI query holding a read snapshot —
    // so retry a few times to ride it out before giving up and copying the file anyway.
    private const val CHECKPOINT_ATTEMPTS = 4
    private const val CHECKPOINT_RETRY_DELAY_MS = 50L

    private val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    // Serializes concurrent backups. Two can overlap — e.g. the post-run snapshot from the Run's
    // finalization and the one from SessionRepository.saveFeelFeedback() — and each ends by
    // retiring every snapshot but its own, so without this the one that finished first would be
    // swept away mid-write by the one that started second. Whichever runs last wins, and both are
    // complete snapshots, so serializing (rather than skipping) is correct.
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
                val dbFile = databaseFile(context)
                if (!dbFile.exists()) return
                if (!checkpoint(database)) {
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

    /** The live database file. Shared with the archive (#85), which snapshots the same one. */
    fun databaseFile(context: Context): File = context.getDatabasePath(DATABASE_NAME)

    /**
     * Folds the WAL and copies the live database to [destination]. Returns whether the fold
     * completed; the copy is made either way.
     *
     * Under [backupLock] with everything else that checkpoints, and that is the whole point of it
     * existing rather than the archive (#85) doing these two steps for itself. A TRUNCATE checkpoint
     * rewrites pages *inside* the main database file — it is the one routine moment when that file
     * changes underneath a reader — so a plain file copy running beside the post-run snapshot could
     * take some pages from before the fold and some from after, and produce an archive holding a
     * SQLite image that never existed. Stale would be survivable; torn is not.
     */
    fun snapshotTo(context: Context, database: AppDatabase, destination: File): Boolean =
        synchronized(backupLock) {
            val folded = checkpoint(database)
            databaseFile(context).copyTo(destination, overwrite = true)
            folded
        }

    /**
     * Folds the WAL into the main `.db` so the copied file includes the latest write. A TRUNCATE
     * checkpoint reports a blocked run in its result row (busy=1) instead of throwing — e.g. when a
     * UI query is holding a read snapshot — leaving recent frames only in `-wal`. Retry a few times
     * to ride out that transient reader. Returns true once a checkpoint completes cleanly.
     *
     * Public because the full archive (#85) snapshots the same file and needs the same fold first;
     * one implementation, so the two copies can't come to mean different things.
     */
    fun checkpoint(database: AppDatabase): Boolean {
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

    /**
     * Writes one complete snapshot into the backup folder, then retires the copies it supersedes.
     *
     * Insert under [BACKUP_DISPLAY_NAME] and let MediaStore settle any collision — it never
     * overwrites, so if a file of that name is already there (the snapshot this one replaces, or a
     * previous install's leftover the app no longer owns) it quietly lands as
     * `running_app_history_backup (1).db` instead. That is deliberate rather than tolerated:
     *
     * - The entry stays `IS_PENDING` until every byte is written, so a snapshot that never finishes
     *   can't be restored, and nothing existing is touched until this one is complete.
     * - [findNewestBackup] identifies the backup by *recency*, not by name, so a numbered name
     *   restores exactly as well as the canonical one.
     * - [claimCanonicalName] takes the name back at the end, once the sweep has freed it.
     *
     * This used to write under a temp name and rename it into place afterwards, which #196 showed
     * cannot be made to work: a reinstall left the old canonical file behind with no MediaStore
     * owner, invisible to the query that was supposed to delete it, so every rename collided with a
     * file the app could neither see nor replace. MediaStore de-duplicated silently up to
     * `(31).db`, then began throwing `Failed to build unique file` — five days of backups landing
     * where nothing read, and then not landing at all.
     */
    private fun writeBackupBytes(context: Context, bytes: ByteArray) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_DISPLAY_NAME)
            put(MediaStore.Downloads.MIME_TYPE, BACKUP_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert returned no URI")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not open output stream for backup")
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            // Roll back the half-written entry; every earlier snapshot is still untouched.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        // Complete, so from here on this *is* the backup — only now retire what it replaces.
        retireOtherBackups(context, keep = uri)
        claimCanonicalName(context, uri)
    }

    /**
     * Renames the new snapshot to [BACKUP_DISPLAY_NAME] if it didn't get that name at insert, and
     * says so loudly when it still can't have it.
     *
     * Runs *after* the sweep, because on a healthy install the name is held by the snapshot this
     * one replaces and only comes free when that one is retired — check before the sweep and every
     * second backup would report a problem that isn't one. Runs after rather than before the write
     * for the reason the rest of this class is built around: nothing is deleted until a complete
     * replacement exists.
     *
     * Failing here is not an error. The snapshot is live and restorable under a numbered name —
     * [findNewestBackup] reads the newest, not the name. It is the tell that something in the folder
     * holds the canonical name and isn't ours to replace: a previous install's leftover, which
     * scoped storage makes invisible and undeletable. That is the condition that hid #196 for five
     * days, and a backup that quietly stops living where it says it does deserves a line in the log.
     */
    private fun claimCanonicalName(context: Context, uri: Uri) {
        if (displayNameOf(context, uri) == BACKUP_DISPLAY_NAME) return
        val rename = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_DISPLAY_NAME)
        }
        // Throws "Failed to build unique file" when the name is taken; the read-back below is what
        // decides, so the exception is context for the warning rather than something to act on.
        val failure = runCatching { context.contentResolver.update(uri, rename, null, null) }
            .exceptionOrNull()
        val landed = displayNameOf(context, uri)
        if (landed != BACKUP_DISPLAY_NAME) {
            Log.w(
                TAG,
                "Backup is \"$landed\", not \"$BACKUP_DISPLAY_NAME\" — something in " +
                    "Downloads/$BACKUP_SUBDIR holds that name and isn't ours to replace. The " +
                    "snapshot is still live; restore reads the newest one, not the name.",
                failure,
            )
        }
    }

    /**
     * Deletes every other snapshot this install owns in the backup folder, leaving only [keep].
     *
     * Runs last, once the replacement is finished and published, so there is no instant where the
     * folder holds no readable backup. Entries this install doesn't own — a previous install's
     * leftovers — can't be deleted under scoped storage and aren't ours to remove; skip them
     * individually so one refusal can't strand the rest of the sweep.
     */
    private fun retireOtherBackups(context: Context, keep: Uri) {
        val keepId = ContentUris.parseId(keep)
        val stale = backupIdsNewestFirst(context, onlyComplete = false).filter { it != keepId }
        var retired = 0
        stale.forEach { id ->
            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            runCatching { context.contentResolver.delete(uri, null, null) }
                .onSuccess { retired++ }
                .onFailure { Log.w(TAG, "Could not retire superseded backup $id", it) }
        }
        if (retired > 0) Log.d(TAG, "Retired $retired superseded backup file(s)")
    }

    private fun readBackupBytes(context: Context): ByteArray? {
        val uri = findNewestBackup(context) ?: return null
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    /**
     * The snapshot to restore: the most recently written finished backup in the folder, whatever it
     * is called.
     *
     * Deliberately *not* "the one named [BACKUP_DISPLAY_NAME]". That name can be held by a file the
     * app no longer owns, in which case every snapshot since lands beside it under a numbered name
     * and reading by name hands back the last copy from before that happened — five days stale, in
     * the case that found this (#196). Recency is the honest question: whatever this install wrote
     * last is the newest history it has.
     *
     * Only finished entries count (`IS_PENDING = 0`), so a snapshot interrupted part-way through is
     * never restored — the newest *finished* one is then the previous snapshot, which is exactly
     * right. Leftovers from the old promote-by-rename write share the prefix and are eligible too:
     * a completed `.tmp.db` from that era is a whole database, and if it is the most recent thing
     * this install wrote then it is the best copy there is.
     */
    private fun findNewestBackup(context: Context): Uri? {
        val id = backupIdsNewestFirst(context, onlyComplete = true).firstOrNull() ?: return null
        return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
    }

    /**
     * Ids of every backup entry this install can see in the folder, newest first. Scoped storage
     * already limits the query to entries the app owns, which is what makes another install's
     * leftovers invisible here rather than something to filter out.
     */
    private fun backupIdsNewestFirst(context: Context, onlyComplete: Boolean): List<Long> {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        var selection =
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        if (onlyComplete) selection += " AND ${MediaStore.Downloads.IS_PENDING} = 0"
        val args = arrayOf("$RELATIVE_PATH%", "$BACKUP_NAME_PREFIX%")
        // DATE_MODIFIED only has second granularity and two backups can land inside one second, so
        // break ties on _ID — rows are handed out in insertion order, so the higher id is the later
        // write.
        val order = "${MediaStore.Downloads.DATE_MODIFIED} DESC, ${MediaStore.Downloads._ID} DESC"
        val ids = mutableListOf<Long>()
        context.contentResolver.query(collection, projection, selection, args, order)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            while (cursor.moveToNext()) ids += cursor.getLong(idColumn)
        }
        return ids
    }

    /** The name [uri] actually carries right now, or null if it has gone. */
    private fun displayNameOf(context: Context, uri: Uri): String? {
        val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME))
            }
        }
        return null
    }
}
