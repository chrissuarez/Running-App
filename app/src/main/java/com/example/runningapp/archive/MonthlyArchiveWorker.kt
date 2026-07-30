package com.example.runningapp.archive

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.runningapp.runningAppContainer
import java.util.concurrent.TimeUnit

/**
 * Writes the monthly archive without anybody asking (#85).
 *
 * The unattended half of the feature, and the half that has to work while the runner has forgotten
 * the app exists — which is why it is WorkManager rather than an alarm or a check at launch:
 * WorkManager keeps its own schedule across reboots and app upgrades, and runs the job late rather
 * than not at all if the phone was off when its turn came.
 *
 * It shares every line of its work with the "Back up now" button. There is exactly one archiver, so
 * a backup made by the runner and one made by the clock are the same archive, tested the same way.
 *
 * **Known limit**: WorkManager stops a worker that runs for more than ten minutes. A personal
 * history is a copy of a database and a few hundred small files, so that is a wide margin rather
 * than a near miss — but a folder on a slow or unreliable cloud mount could reach it, and the
 * failure mode is a cancelled backup that retries rather than a corrupted one: the archive is only
 * ever a backup once it has been renamed, and an attempt cut short leaves a `.part` the next
 * attempt sweeps. Lifting the limit means running this as a foreground service, which is a bigger
 * change than the risk warrants today.
 */
class MonthlyArchiveWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        when (val outcome = applicationContext.runningAppContainer().archiver.archiveNow()) {
            is ArchiveOutcome.Archived -> {
                Log.d(TAG, "Monthly archive written as ${outcome.fileName}")
                Result.success()
            }
            // Not a failure and not worth retrying: there is nowhere to write until the runner picks
            // a folder, and picking one is a decision, not a transient condition. The job stays
            // scheduled and the next month's run finds the folder if they have chosen one by then.
            ArchiveOutcome.NoFolderChosen -> Result.success()
            // Retried with WorkManager's backoff: a folder can be temporarily unreachable — an SD
            // card out, a cloud provider not signed in — and a month is a long time to wait to try
            // again over something that may have righted itself in an hour.
            is ArchiveOutcome.Failed -> {
                Log.w(TAG, "Monthly archive failed: ${outcome.reason}")
                Result.retry()
            }
        }

    companion object {
        private const val TAG = "Archive"
        private const val WORK_NAME = "monthly-archive"

        /**
         * Keeps the monthly job scheduled. Safe to call on every launch: [ExistingPeriodicWorkPolicy.KEEP]
         * leaves an existing schedule exactly where it is, so opening the app does not push the next
         * backup a month into the future each time — which `REPLACE` would, on a phone that is opened
         * daily, mean a monthly backup that never happens.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                // Neither is about being polite. An archive is a copy of the whole database plus a
                // file per run: run it on a nearly-flat battery or a full disk and the likeliest
                // outcome is a failed backup, which is worse than a backup an hour later.
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<MonthlyArchiveWorker>(30, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
