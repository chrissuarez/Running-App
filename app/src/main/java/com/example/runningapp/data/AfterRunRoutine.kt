package com.example.runningapp.data

import android.util.Log
import com.example.runningapp.run.RunMode

/**
 * The work that outlives a Run: its weather, and the snapshot of history it now belongs to (#122).
 *
 * Both used to be launched from the service on a scope of its own and left to finish on their own
 * time. That works while there is a process, and a Run stopped from the notification is exactly the
 * case where there may not be one for long: the service takes itself down on the next main-loop
 * message and Android is free to reclaim the process straight after, before either coroutine has
 * been dequeued. Room was never the loser there — the Run is committed before this — but the
 * Downloads copy could be a Run behind, which is only ever discovered by someone restoring it.
 *
 * So this is the *body* of that work, with the process death taken out of it: [AfterRunWorker] is
 * what carries it, and WorkManager is what remembers it across a death. The body is here rather
 * than in the worker so the order below can be checked on a laptop.
 *
 * The order is the point. Weather first, then the snapshot, so the copy that lands in Downloads
 * holds the weather rather than being taken a moment before it arrives. And a weather look-up is
 * allowed to fail without costing the snapshot: a missed fetch is retried at the next launch
 * ([SessionRepository.retryMissingWeather]), where a missed snapshot is only noticed by a runner
 * who has already cleared their storage.
 *
 * The Run is read fresh rather than handed in, because by the time this runs the row may have moved
 * on — or gone. A Run deleted in between is still snapshotted, and must be: the deletion is itself
 * history the copy should carry.
 */
class AfterRunRoutine(
    private val readRun: suspend (Long) -> RunnerSession?,
    private val fetchWeather: suspend (
        runRowId: Long,
        latitude: Double,
        longitude: Double,
        atEpochMillis: Long,
    ) -> Unit,
    private val snapshotHistory: suspend () -> Boolean,
) {

    /**
     * Returns whether a new snapshot of history was published — the one part of this worth trying
     * again, and the reason [AfterRunWorker] can ask WorkManager for another go.
     */
    suspend fun perform(runRowId: Long): Boolean {
        val run = readRun(runRowId)
        val latitude = run?.startLatitude
        val longitude = run?.startLongitude
        if (run != null && run.runMode == RunMode.OUTDOOR.settingValue &&
            latitude != null && longitude != null
        ) {
            // Swallowed on purpose: see the ordering note above. The launch retry is what covers a
            // fetch that could not be made, and nothing covers a snapshot that was not taken.
            try {
                fetchWeather(runRowId, latitude, longitude, run.startTime)
            } catch (e: Exception) {
                Log.w(TAG, "Weather for run $runRowId failed; leaving it to the launch retry", e)
            }
        }
        return snapshotHistory()
    }

    private companion object {
        private const val TAG = "AfterRun"
    }
}
