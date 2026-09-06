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
 * The order is the point, and it is the snapshot that goes first. A missed fetch is retried at the
 * next launch ([SessionRepository.backfillWeather]); a missed snapshot is only ever noticed by
 * a runner who has already cleared their storage. Putting the irrecoverable operation behind the
 * recoverable one is what a weather look-up costs when it goes slowly: the client waits ten seconds
 * to connect and ten more to read, with nothing capping the call as a whole, and a Clear storage
 * inside that window takes the Room database and WorkManager's own request database together — so
 * the very request that was meant to survive the clearing is destroyed by it, and the Downloads
 * copy stays a Run behind. Snapshot first and the network can never hold the copy hostage.
 *
 * The copy should still end up carrying the weather, so once the weather is in, the copy is taken
 * again — and only then. A second snapshot is four megabytes, which buys nothing when there was no
 * fetch to make or the fetch came back empty-handed. What decides it is the Run's row read back
 * afterwards, weather absent before and present after; the fetch itself reports nothing.
 *
 * The Run is read fresh rather than handed in, because by the time this runs the row may have moved
 * on — or gone. A Run deleted in between is still snapshotted, and must be: the deletion is itself
 * history the copy should carry. Read fresh also means a Run that already has its weather is left
 * alone, so a retried job does not re-ask a question already answered.
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
     * Returns whether the Downloads copy now holds everything it should — the one part of this
     * worth trying again, and the reason [AfterRunWorker] can ask WorkManager for another go.
     * False when the first snapshot could not be published, and false when the weather landed and
     * the second snapshot that would have carried it could not be.
     */
    suspend fun perform(runRowId: Long): Boolean {
        val run = readRun(runRowId)
        if (!snapshotHistory()) return false

        val latitude = run?.startLatitude
        val longitude = run?.startLongitude
        if (run == null || run.runMode != RunMode.OUTDOOR.settingValue ||
            latitude == null || longitude == null || run.weatherTempC != null
        ) {
            return true
        }

        // Swallowed on purpose: see the ordering note above. The launch retry is what covers a
        // fetch that could not be made, and the snapshot it used to sit in front of is already out.
        try {
            fetchWeather(runRowId, latitude, longitude, run.startTime)
        } catch (e: Exception) {
            Log.w(TAG, "Weather for run $runRowId failed; leaving it to the launch retry", e)
        }

        // The row rather than the fetch, which reports nothing either way. No weather, no reason to
        // spend a second copy.
        val weathered = readRun(runRowId)?.weatherTempC != null
        return !weathered || snapshotHistory()
    }

    private companion object {
        private const val TAG = "AfterRun"
    }
}
