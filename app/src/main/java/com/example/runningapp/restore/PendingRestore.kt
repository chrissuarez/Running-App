package com.example.runningapp.restore

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.util.Log
import com.example.runningapp.archive.ArchivedSettings
import com.example.runningapp.data.DatabaseBackupManager
import java.io.File

/**
 * Holds a confirmed restore until the one moment it is safe to carry out (#86).
 *
 * The database is open and being read the instant the runner confirms — History is drawing off it,
 * a background job may be writing to it. Replacing the file underneath all of that is the one way
 * this feature could destroy the thing it exists to rescue. So confirming does not restore anything:
 * it *arms* a restore, and the app closes and reopens itself. On the way back up, before Room has
 * opened anything, [applyIfArmed] moves the staged snapshot into place. That is the same window the
 * automatic Downloads restore already uses, and it is the only point in the app's life at which the
 * database provably has no readers.
 *
 * The marker is a file rather than a preference so it lives and dies with the staged snapshot beside
 * it, in storage the restore already depends on. A phone that dies between the confirmation and the
 * relaunch finds the restore still armed and finishes it, rather than leaving the runner with an
 * unexplained un-restored phone; a phone that dies mid-move finds either the old database or the new
 * one, never a blend, because the move is a rename.
 */
object PendingRestore {
    private const val TAG = "DbRestore"

    /** Written last and read first: its presence is the whole decision. */
    private const val MARKER = "restore-armed"

    private fun marker(context: Context) = File(RestoreReader.stagingDirectory(context), MARKER)

    private fun stagedDatabase(context: Context) =
        File(RestoreReader.stagingDirectory(context), RestoreReader.STAGED_DATABASE)

    /**
     * Commits to restoring whatever [RestoreReader.stage] left staged, at the next launch.
     *
     * Returns false when there is nothing staged to arm — a pick lost to the process being killed
     * while the confirmation was on screen. Better to make the runner pick again than to arm a
     * restore with no file behind it.
     */
    fun arm(context: Context): Boolean {
        if (!stagedDatabase(context).exists()) {
            Log.w(TAG, "Nothing staged to restore; not arming")
            return false
        }
        return runCatching { marker(context).writeBytes(ByteArray(0)); true }
            .onFailure { Log.w(TAG, "Could not arm the restore", it) }
            .getOrDefault(false)
    }

    /** Whether a confirmed restore is waiting to be applied. */
    fun isArmed(context: Context): Boolean = marker(context).exists()

    /**
     * Applies an armed restore, or clears away an abandoned pick. Returns true if history was
     * replaced.
     *
     * **Call before Room opens the database, and before the automatic Downloads restore.** Ordering
     * against that one matters: this is the restore the runner explicitly asked for, so it wins, and
     * once it has run the database exists, which is exactly the condition under which the automatic
     * restore correctly does nothing.
     *
     * [applySettings] is handed an archive's settings *after* its history is in place, and never
     * otherwise. Settings and history are two halves of one restore, and the harmful order is the
     * one that leaves the settings replaced and the history not: every run on the phone would then
     * be read against a heart-rate profile and a training plan from a file the runner never got.
     * The reverse gap — history restored, settings a moment behind — is survivable, and is closed by
     * leaving the restore armed until both halves have landed.
     *
     * Clearing an *un*-armed staging directory is the other half of the job. A pick the runner
     * backed out of leaves a whole copy of a database in app storage; nothing else would ever
     * remove it, and it would sit there as large as the history itself.
     */
    fun applyIfArmed(
        context: Context,
        applySettings: (ArchivedSettings) -> Unit = {},
    ): Boolean {
        if (!isArmed(context)) {
            if (RestoreReader.stagingDirectory(context).exists()) RestoreReader.clear(context)
            return false
        }
        val staged = stagedDatabase(context)
        var replaced = false
        if (staged.exists()) {
            try {
                moveIntoPlace(context, staged)
                replaced = true
                Log.d(TAG, "Restored run history from the picked backup")
            } catch (e: Exception) {
                // Best-effort, like every other recovery path here: a failed restore must not stop
                // the app launching. The previous database is untouched, so the runner lands where
                // they were and can try again — and because the settings had not been written yet,
                // what they land in is entirely the phone they had before they picked.
                Log.w(TAG, "Could not apply the pending restore", e)
                RestoreReader.clear(context)
                return false
            }
        } else {
            // Armed, but the snapshot has gone: the move landed and only what follows it was cut
            // short. The database in place is already the restored one, so carry on to the settings
            // rather than treating this as nothing to do.
            Log.w(TAG, "Restore was armed with nothing staged; the database swap had already landed")
        }
        val settings = RestoreReader.stagedSettings(context)
        if (settings != null) {
            try {
                applySettings(settings)
            } catch (e: Exception) {
                // Left armed on purpose. The history is in place and its settings are not, which is
                // the one state this restore must not be abandoned in; the next launch finds the
                // marker, finds no snapshot, and finishes the job.
                Log.w(TAG, "Restored the history but not its settings; will try again next launch", e)
                return replaced
            }
        }
        RestoreReader.clear(context)
        return replaced
    }

    /**
     * Puts [staged] where Room will look for it.
     *
     * The previous database's log is dealt with first, and by folding it into the database it
     * belongs to rather than by moving it out of the way. That ordering is not optional: the
     * snapshot arrives already checkpointed, so a log left beside it describes writes to a file
     * that no longer exists, and Room would either replay it into the restored history or call the
     * result corrupt.
     *
     * Folded rather than deleted, because a restore that fails promises the runner their phone is
     * exactly as it was. The app is killed without closing Room, so recent runs can live only in
     * that log — deleting it and then failing to promote the replacement would quietly roll back
     * history this had just said it would not touch. A checkpoint puts those runs into the database
     * itself, where a failed restore leaves them safe and a successful one discards them along with
     * everything else that database held.
     *
     * Earlier revisions set the log aside under another name and put it back if the promotion
     * failed. That is the same idea with a tail of failures behind it — the put-back is itself a
     * rename that can fail, and a log stranded under a name nothing recognises is worse than no log
     * at all, because the next launch opens the database without it and any later checkpoint makes
     * it unreplayable. Folding it in has no such state: after this returns there is one file, and it
     * is either wholly the old history or wholly the new.
     */
    private fun moveIntoPlace(context: Context, staged: File) {
        val destination = DatabaseBackupManager.databaseFile(context)
        destination.parentFile?.mkdirs()
        foldPreviousLogIntoItsDatabase(destination)
        if (!staged.renameTo(destination)) {
            // A rename can only fail here if staging and the database sit on different volumes,
            // which they do not today — but a copy through a sibling temp file keeps the same
            // promise if that ever changes: the destination is only ever the whole snapshot or
            // the previous database, never a half-written mixture of the two.
            val temp = File("${destination.path}.restore.tmp")
            staged.copyTo(temp, overwrite = true)
            if (!temp.renameTo(destination)) {
                temp.delete()
                throw IllegalStateException("Could not move the restored database into place")
            }
            staged.delete()
        }
    }

    /**
     * Folds whatever the previous database left beside it back into the database itself, leaving no
     * journal of any kind for the restored file to inherit. Does nothing when there is no previous
     * database, which is the freshly-wiped phone this feature exists for.
     *
     * Opened unconditionally, not only when a `-wal` is sitting there. Room picks its journal mode
     * to suit the device and falls back to a rollback journal on low-memory ones, so the leftovers
     * of a killed write can just as easily be a hot `running_app_db-journal`. Merely opening the
     * database is what makes SQLite deal with either — replaying a write-ahead log, rolling back an
     * interrupted transaction — and skipping that because one particular file was absent would let
     * the other kind survive to be replayed into the restored history.
     *
     * Folded rather than deleted, because a restore that fails promises the runner their phone is
     * exactly as it was. The app is killed without closing Room, so recent runs can live only in
     * these files — dropping them and then failing to promote the replacement would quietly roll
     * back history this had just said it would not touch. Recovered into the database, those runs
     * are safe if the restore fails and discarded with everything else if it succeeds.
     *
     * `TRUNCATE` rather than the default `PASSIVE`, because a partial checkpoint is exactly the
     * outcome that must not be mistaken for success: it reports whether it was blocked, and being
     * blocked here throws, which abandons the restore with the database and its journals untouched.
     * Nothing else in the process holds this file — this runs before Room opens anything — so being
     * blocked means something is wrong enough to stop for. On a database in rollback-journal mode
     * the pragma has nothing to do and says so, which is the right answer there too: the recovery
     * that mattered already happened when it opened.
     *
     * A database that will not open splits in two, on SQLite's own verdict. *Corrupt* is stepped
     * over rather than stopped for: its journals describe pages of something unreadable, cannot be
     * folded in and could not be replayed by Room either, and a database in that state is precisely
     * what the runner is restoring their way out of. Anything else — storage full, an I/O error —
     * may pass, and its journals may still hold runs that exist nowhere else, so nothing is deleted
     * and the restore is abandoned instead. Guessing wrong the other way is the expensive mistake:
     * a transient failure here is likely to fail the promotion a moment later too, and Room would
     * reopen the old database with its recent runs already thrown away.
     */
    private fun foldPreviousLogIntoItsDatabase(destination: File) {
        if (!destination.exists()) return
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(destination.path, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: SQLiteDatabaseCorruptException) {
            // SQLite's own verdict that the file is not a database any more. Its journals describe
            // pages of something unreadable: they cannot be folded in, and Room could not replay
            // them either, so they are only ever dangerous from here. Dropping them and carrying on
            // is right, because a database in this state is precisely what the runner is restoring
            // their way out of.
            Log.w(TAG, "Previous database is corrupt; dropping its journals unread", e)
            deleteJournalsOf(destination)
            return
        } catch (e: Exception) {
            // Anything else — storage full, an I/O error, a file that cannot be opened just now —
            // is a condition that may pass, and the journals may still hold runs that exist nowhere
            // else. It is also a condition likely to fail the promotion a moment later, which would
            // leave Room reopening the old database with its recent runs already thrown away. So
            // nothing is deleted and the restore is abandoned; the runner picks again, having lost
            // only a temporary copy.
            throw IllegalStateException("Could not recover the previous database before restoring", e)
        }
        try {
            val blocked = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                // First column is 1 when SQLite could not finish. No row at all is the same answer.
                !cursor.moveToFirst() || cursor.getInt(0) != 0
            }
            if (blocked) {
                throw IllegalStateException("Could not fold the previous database's log into it")
            }
        } finally {
            runCatching { db.close() }
        }
        // A truncating checkpoint empties the log and a clean close removes these; anything left
        // describes nothing, and leaving it would put it beside the restored database.
        deleteJournalsOf(destination)
    }

    /** Everything SQLite can leave beside a database file. */
    private fun deleteJournalsOf(destination: File) {
        listOf("-wal", "-shm", "-journal").forEach { File("${destination.path}$it").delete() }
    }
}
