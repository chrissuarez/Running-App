package com.example.runningapp.data

import java.io.File

/**
 * A copy of the live database that is complete as of the moment it was taken (#191).
 *
 * SQLite writes it, not this code: `VACUUM INTO` builds a fresh database file out of the pages the
 * source holds *right now*, log included. Copying the `.db` file was the old way, and it was only
 * ever right after the write-ahead log had been folded back into it — a fold that a reader holding
 * a snapshot can block, and did, leaving the copy silently a few minutes behind the history it
 * claimed to be. There is no such window here: what is committed is in the snapshot, and the file
 * that lands has no `-wal`/`-shm` siblings to be separated from.
 *
 * The routine either produces that file or throws. It never returns a snapshot the caller has to
 * judge, because both callers ignored the old "did the fold complete?" flag and published anyway.
 * A snapshot that cannot be vouched for must not be publishable, so it does not exist.
 *
 * What a backup therefore promises, and what a restore may assume of it, is
 * [ADR 0009](docs/adr/0009-a-backup-is-complete-or-it-is-not-a-backup.md).
 */
object DatabaseSnapshot {

    /**
     * Writes a complete snapshot of the database behind [execSql] to [destination].
     *
     * [execSql] runs a statement on the *live* database — Room's own connection in the app, a JDBC
     * one in tests. Nothing else about SQLite is needed, which is what lets the one statement this
     * is made of be checked on a laptop against a real database with a real reader holding it open.
     *
     * `VACUUM INTO` refuses a destination that already exists, so any file in the way is cleared
     * first — including a snapshot an interrupted backup abandoned under the same name. Anything
     * left behind by a failed attempt goes too: a part-written database file is not a snapshot, and
     * the next caller along must not be able to mistake it for one.
     */
    fun writeTo(destination: File, execSql: (String) -> Unit) {
        destination.parentFile?.mkdirs()
        destination.delete()
        try {
            execSql("VACUUM INTO '${destination.path.replace("'", "''")}'")
            check(destination.isFile && destination.length() > 0L) {
                "VACUUM INTO wrote no snapshot to ${destination.path}"
            }
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }
}
