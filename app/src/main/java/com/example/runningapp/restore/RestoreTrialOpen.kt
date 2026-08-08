package com.example.runningapp.restore

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.example.runningapp.HrProfile
import com.example.runningapp.data.AppDatabase
import com.example.runningapp.data.appDatabaseMigrations
import java.io.File

/**
 * Proves a staged backup will open before anything is replaced, by having Room open it (#201).
 *
 * The reads [RestoreReader] does before this one — `quick_check`, Room's bookkeeping table, a
 * readable `sessions` — say the file is whole and that Room wrote it. None of them says Room will
 * *accept* it. A structurally sound database missing a table this app needs passes all three, is
 * promoted over the runner's history, and is then refused by Room at the next launch with the
 * previous history already gone. That is the one failure this feature cannot survive, because it
 * happens after the only copy has been overwritten.
 *
 * So the file is opened here, in staging, where a refusal costs a temporary file and nothing else.
 * Room runs its own migrations and its own schema validation against it; if it opens, the restore
 * provably cannot fail on the way back up. It has a second benefit worth as much as the first: what
 * gets promoted is then **already migrated**, so the swap installs a database that needs nothing
 * further.
 *
 * What is deliberately not attempted is comparing Room's identity hash. The app has no way to know
 * the correct hash for a backup written at an older schema — which is the ordinary case this
 * feature exists for — and comparing against the live database's hash fails exactly when it matters
 * most, after a Clear storage, when there is no live database to compare with. Letting Room try the
 * file is the only check that answers the actual question.
 */
object RestoreTrialOpen {
    private const val TAG = "DbRestore"

    /**
     * Opens [database] with Room, migrating it to today's schema in place. True if it opened.
     *
     * Call from a background thread: this runs every migration between the file's version and this
     * app's, on however much history the file holds.
     *
     * [hrProfileProvider] feeds the v12 → v13 zone recompute. It has to be the same profile the
     * live app would use for this very file — the archive's own if it carried one — because the
     * migration bands every run in the file against it, and a trial that migrated against a
     * different profile would silently rewrite the restored zone totals to numbers no phone ever
     * produced.
     *
     * Nothing here goes near [AppDatabase.getDatabase]. That accessor memoises one instance, and
     * asking it to open the staged copy would leave the app holding the staging file as its live
     * database for the rest of the process — which outlives the staging directory. This builds its
     * own, against the staged path, and closes it before returning, so by the time the file is
     * promoted nothing has it open.
     */
    fun migrates(
        context: Context,
        database: File,
        hrProfileProvider: () -> HrProfile,
    ): Boolean {
        var room: AppDatabase? = null
        try {
            room = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                // An absolute path rather than a bare name: a database name that starts with a
                // separator is taken as the file itself, which is how the staged copy gets opened
                // where it lies instead of beside the live database it is waiting to replace.
                database.path,
            )
                // The live app's own list, so what is proved here is what happens at the relaunch.
                // Note what is *not* here: no destructive fallback. A backup Room would rather
                // rebuild from scratch than migrate is a backup with no history left in it, and
                // this has to fail on it rather than quietly hand back an empty database.
                .addMigrations(*appDatabaseMigrations(hrProfileProvider))
                .build()
            // Room opens lazily, so nothing above has touched the file yet. This is the line that
            // runs the migrations and the schema validation, and the line that can throw.
            val opened = room.openHelper.writableDatabase
            // Fold the log back into the file while it is still open and known good. The promotion
            // moves one file, so anything left in a write-ahead log beside it is a set of writes
            // the restored database would never see — and these particular writes are the
            // migrations themselves.
            val checkpointed = opened.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                // First column is 1 when SQLite could not finish; no row at all says the same.
                cursor.moveToFirst() && cursor.getInt(0) == 0
            }
            if (!checkpointed) {
                Log.w(TAG, "Could not fold the trial open's log into the staged backup")
                return false
            }
        } catch (e: Exception) {
            // Every way Room can say no lands here: a missing table, a migration that did not
            // produce the shape Room expects, a gap in the migration path, a file that turned out
            // not to be readable after all. They are one answer to the runner — this is a backup
            // that cannot be carried forward — and the phone is untouched either way.
            Log.w(TAG, "Room would not open the staged backup", e)
            return false
        } finally {
            runCatching { room?.close() }
        }
        // A clean close leaves no journal, and a truncated log holds nothing anyway — but a file
        // that did survive would be promoted's neighbour, describing a database that no longer
        // exists. Refuse rather than promote into that.
        return journalsRemoved(database)
    }

    private fun journalsRemoved(database: File): Boolean =
        listOf("-wal", "-shm", "-journal").all { suffix ->
            val journal = File("${database.path}$suffix")
            if (!journal.exists() || journal.delete()) {
                true
            } else {
                Log.w(TAG, "Could not remove the trial open's $suffix")
                false
            }
        }
}
