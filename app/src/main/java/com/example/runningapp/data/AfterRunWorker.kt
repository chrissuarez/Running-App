package com.example.runningapp.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.runningapp.runningAppContainer

/**
 * Carries [AfterRunRoutine] somewhere the death of the app's process cannot reach it (#122).
 *
 * A Run stopped from the notification finalizes and then takes the service down, and the process
 * with it — so the work that follows a Run cannot be left on a coroutine belonging to that process.
 * WorkManager writes the request into its own database at [enqueue], and runs it whether or not the
 * app is still alive to see it: this is the difference between a Downloads copy that is always one
 * Run behind and one that is not.
 *
 * Not expedited. Expedited work before API 31 demands a foreground notification from the worker,
 * and `minSdk` is 30 — so asking for it would be asking one phone in the supported range to show
 * the runner a notification about a backup. Ordinary work with no constraints is scheduled straight
 * away in practice, and nothing downstream is waiting on it: the Run is in history already, and the
 * snapshot only has to happen before the next Clear storage.
 */
class AfterRunWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val runRowId = inputData.getLong(KEY_RUN_ROW_ID, NO_RUN)
        if (runRowId == NO_RUN) {
            Log.w(TAG, "No run named; nothing to do")
            return Result.success()
        }
        val container = applicationContext.runningAppContainer()
        val routine = AfterRunRoutine(
            readRun = { container.database.sessionDao().getSessionById(it) },
            fetchWeather = { rowId, latitude, longitude, atEpochMillis ->
                container.sessionRepository.fetchAndSaveWeather(
                    sessionId = rowId,
                    latitude = latitude,
                    longitude = longitude,
                    atEpochMillis = atEpochMillis,
                )
            },
            snapshotHistory = {
                DatabaseBackupManager.backup(applicationContext, container.database)
            },
        )
        val published = try {
            routine.perform(runRowId)
        } catch (e: Exception) {
            // Not the snapshot — that one reports a failure rather than throwing one. What is left
            // is reading the Run's row, which fails when the database cannot be opened at all.
            Log.w(TAG, "After-run work for $runRowId failed", e)
            false
        }
        return when {
            published -> Result.success()
            // Storage full, MediaStore unreachable, the database locked by a restore — all of them
            // pass, and a backup an hour late is a backup. Retried rather than dropped, because
            // this run is the one the standing copy is missing.
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            // Given up on out loud. The next finished Run books work of its own and snapshots this
            // one along with it, so the history is not lost, only late.
            else -> {
                Log.w(TAG, "Gave up snapshotting history after run $runRowId; next run will carry it")
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "AfterRun"
        private const val KEY_RUN_ROW_ID = "runRowId"
        private const val NO_RUN = -1L
        private const val WORK_NAME_PREFIX = "after-run-"

        /** Roughly a day of WorkManager's default backoff before the next Run takes over. */
        private const val MAX_ATTEMPTS = 8

        /**
         * Books the work for a Run that has just been written down, and **returns only once
         * WorkManager has the request in its own database**.
         *
         * The wait is the whole point of calling this from the finalization rather than after it:
         * `enqueue` hands the write to WorkManager's own executor, so a caller that returned
         * immediately could still have the process reclaimed before the request was ever recorded —
         * which is the failure this ticket is about, moved one step along. Call it from a
         * background thread; it blocks.
         */
        fun enqueue(context: Context, runRowId: Long) {
            val request = OneTimeWorkRequestBuilder<AfterRunWorker>()
                .setInputData(Data.Builder().putLong(KEY_RUN_ROW_ID, runRowId).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    "$WORK_NAME_PREFIX$runRowId",
                    // One Run's work is one job. KEEP rather than REPLACE so a second ask for a Run
                    // already booked leaves the first standing instead of pushing it back.
                    ExistingWorkPolicy.KEEP,
                    request,
                )
                .result
                .get()
        }
    }
}
