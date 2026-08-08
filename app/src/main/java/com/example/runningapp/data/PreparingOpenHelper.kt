package com.example.runningapp.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Puts whatever history a launch owes into place, once, immediately before the database file is
 * opened (#121).
 *
 * A restore has exactly one safe moment: after the previous process has let go of the database and
 * before this one reads it. That used to be arranged by ordering — the restore was written above
 * the line that built Room, and a comment asked everyone to leave it there. The trouble is *where*
 * that line runs. Building Room is what a screen does on its way up, on the main thread, so a
 * MediaStore query and a whole-database file copy sat in the launch path, and after a Clear storage
 * with a long history that copy is large enough to risk an ANR.
 *
 * Hanging the preparation off the *open* rather than off the build settles both halves at once:
 *
 * - It is still before Room reads anything, and now by construction rather than by convention —
 *   there is no order left for a later edit to get wrong.
 * - It moves with whoever opens the database, and everyone who does is already off the main thread.
 *   Room's own paths are guaranteed to be: nothing here calls `allowMainThreadQueries`, so a query
 *   on the main thread throws, and the first query is what opens the file. The three places that
 *   reach past Room to `openHelper` directly — `RestoreViewModel`, `RunArchiveContents` and
 *   [DatabaseBackupManager.snapshotTo] — are on `Dispatchers.IO` by their own arrangement, and that
 *   part is convention. Anything new reaching for `openHelper` inherits the same obligation.
 *
 * [prepare] is best-effort work that answers for its own failures; this wrapper neither swallows one
 * nor writes it off. A preparation that throws leaves the database unprepared, and the next open
 * tries again — which is what the restores want, both being built to be resumed at the next launch
 * after a phone that died half way through one.
 */
class PreparingOpenHelper(
    private val delegate: SupportSQLiteOpenHelper,
    private val prepare: () -> Unit
) : SupportSQLiteOpenHelper by delegate {

    @Volatile
    private var prepared = false
    private val lock = Any()

    override val writableDatabase: SupportSQLiteDatabase
        get() {
            prepareOnce()
            return delegate.writableDatabase
        }

    override val readableDatabase: SupportSQLiteDatabase
        get() {
            prepareOnce()
            return delegate.readableDatabase
        }

    /**
     * Held under a lock rather than merely flagged: a second opener arriving mid-restore must wait
     * for it, not walk past it into a database that is halfway through being replaced.
     *
     * Marked done only on the way out of a preparation that returned. A throw leaves it undone, and
     * that is deliberate — see the class note.
     */
    private fun prepareOnce() {
        if (prepared) return
        synchronized(lock) {
            if (prepared) return
            prepare()
            prepared = true
        }
    }
}

/**
 * Hands Room a [PreparingOpenHelper] wrapped around the helper it would have built anyway.
 */
class PreparingOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory,
    private val prepare: () -> Unit
) : SupportSQLiteOpenHelper.Factory {
    override fun create(
        configuration: SupportSQLiteOpenHelper.Configuration
    ): SupportSQLiteOpenHelper = PreparingOpenHelper(delegate.create(configuration), prepare)
}
