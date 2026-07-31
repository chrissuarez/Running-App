package com.example.runningapp.archive

import android.content.Context
import android.util.Log
import com.example.runningapp.SettingsRepository
import com.example.runningapp.data.AppDatabase
import com.example.runningapp.data.DatabaseBackupManager
import com.example.runningapp.data.RunWalkIntervalStatDao
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.SessionDao
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.isFinished
import com.example.runningapp.export.GpxWriter
import com.example.runningapp.export.RunGpxTrack
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.flow.first

/**
 * Everything the app has, gathered into the entries of one archive (#85).
 *
 * The list is assembled up front but the *contents* are not: each entry knows how to write itself
 * and is asked only when its turn in the ZIP comes ([ArchiveEntry]). A year of running is thousands
 * of GPS fixes per run, and an archive that held all of it in memory at once would be a backup that
 * only works while the history is small.
 */
class RunArchiveContents(
    context: Context,
    private val database: AppDatabase,
    private val sessionDao: SessionDao,
    private val intervalStatDao: RunWalkIntervalStatDao,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository
) {

    private val appContext = context.applicationContext

    suspend fun entries(createdAtEpochMillis: Long): List<ArchiveEntry> {
        // Read once and used by both halves, so the GPX files and the JSON describe the same set of
        // runs even if one finishes while the archive is being assembled.
        val runs = sessionDao.getAllSessions()
        return runEntries(runs) + jsonEntry(createdAtEpochMillis, runs) + databaseEntry()
    }

    /**
     * One GPX per run that has a route, built the same way the Share button builds it (#84) — so a
     * file out of the archive and a file off the share sheet are the same file.
     */
    private suspend fun runEntries(runs: List<RunnerSession>): List<ArchiveEntry> =
        runsWorthAGpx(runs, sessionRepository.getSessionIdsWithMappableTrack())
            .map { run ->
                ArchiveEntry("${ArchiveZip.ACTIVITIES_DIRECTORY}/${RunGpxTrack.fileName(run)}") { out ->
                    out.write(gpx(run).toByteArray(Charsets.UTF_8))
                }
            }

    private suspend fun gpx(run: RunnerSession): String {
        // Through the same #38 accuracy gate as the map and the share sheet, so the route in the
        // archive is the route the runner was shown.
        val trackPoints = sessionRepository.getTrackPointsForMap(run.id)
        val hrSamples = sessionRepository.getHrSamples(run.id)
        return GpxWriter.write(RunGpxTrack.build(run, trackPoints, hrSamples))
    }

    private suspend fun jsonEntry(
        createdAtEpochMillis: Long,
        runs: List<RunnerSession>
    ): ArchiveEntry {
        val document = ArchiveDocument(
            createdAtEpochMillis = createdAtEpochMillis,
            databaseVersion = database.openHelper.readableDatabase.version,
            settings = settingsRepository.userSettingsFlow.first().toArchived(),
            runs = runs,
            intervalStats = intervalStatDao.getAllIntervalStats()
        )
        return ArchiveEntry.ofText(ArchiveJson.FILE_NAME, ArchiveJson.write(document))
    }

    /**
     * The database itself, folded and copied.
     *
     * Two steps rather than one, and the order matters. The write-ahead log is checkpointed first,
     * or the snapshot would be the database as of some earlier moment with the recent writes left
     * behind in a file that is not being copied — and the fold and the copy are taken together under
     * the manager's lock, so no other checkpoint in the app can rewrite the file between them. Then
     * it is copied to a local file *before* being
     * streamed into the archive: writing into the runner's folder means compressing and crossing a
     * content provider, which is slow, and a run finishing halfway through would leave the copy torn
     * across two versions of the database. The local copy is a bulk file copy — narrow enough that a
     * run would have to finish inside it, rather than inside the whole backup.
     */
    private fun databaseEntry(): ArchiveEntry =
        ArchiveEntry("${ArchiveZip.DATABASE_DIRECTORY}/${DatabaseBackupManager.DATABASE_NAME}") { out ->
            val snapshot = File(appContext.cacheDir, SNAPSHOT_FILE_NAME)
            try {
                // Folded and copied as one step by the manager, which holds these two together
                // against every other checkpoint in the app — the post-run snapshot's fold running
                // inside this copy would rewrite pages under it and archive a torn database.
                if (!DatabaseBackupManager.snapshotTo(appContext, database, snapshot)) {
                    Log.w(TAG, "WAL checkpoint stayed blocked; the snapshot may lag the most recent write")
                }
                snapshot.inputStream().use { it.copyTo(out) }
            } finally {
                snapshot.delete()
            }
        }

    private companion object {
        const val TAG = "Archive"

        /**
         * One fixed name in the cache, reused every backup. A backup killed mid-copy leaves this
         * behind, and the next one overwrites it — a stale copy of the database in the app's own
         * cache is nobody's backup and Android is free to reclaim it.
         */
        const val SNAPSHOT_FILE_NAME = "archive-database-snapshot.db"
    }
}

/**
 * Which runs get a GPX file of their own, given the runs there are and which of them recorded a
 * route.
 *
 * Two exclusions, and neither loses anything — every run is in `archive.json` and in the database
 * snapshot whether it appears here or not:
 *
 *  - **a run with no route**, which is every treadmill run, and equally a run whose every fix was
 *    too vague to trust (#38) — the same gate the map and the share sheet apply, so a run the Share
 *    button refuses to export is a run the archive leaves out. GPX is a file of places; a run that
 *    went nowhere would be an empty file with a name, and a reader importing the archive would find
 *    a run it could say nothing about.
 *  - **a run still being recorded** ([RunnerSession.isFinished]). Its track stops wherever the
 *    backup happened to catch it, and the run's own totals are not written until it ends — so the
 *    file would describe a run that never existed in that shape. The next backup gets it whole.
 *
 * Pure, so the rule that decides what an archive contains is checkable without a database.
 */
fun runsWorthAGpx(runs: List<RunnerSession>, runIdsWithTrack: List<Long>): List<RunnerSession> {
    val withTrack = runIdsWithTrack.toSet()
    return runs.filter { it.isFinished() && it.id in withTrack }
}
