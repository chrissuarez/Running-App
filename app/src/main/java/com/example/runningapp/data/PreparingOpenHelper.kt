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
 * - It is off the main thread for free. Room refuses main-thread queries (nothing here calls
 *   `allowMainThreadQueries`), so the first query — and therefore the first open, and therefore this
 *   — can only ever happen on a background thread.
 *
 * [prepare] is best-effort work that answers for its own failures; this wrapper does not swallow
 * them. It does refuse to repeat one: a preparation that threw may have half-replaced the database
 * already, and running it a second time against that is worse than the failure was.
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
     */
    private fun prepareOnce() {
        if (prepared) return
        synchronized(lock) {
            if (prepared) return
            try {
                prepare()
            } finally {
                prepared = true
            }
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
