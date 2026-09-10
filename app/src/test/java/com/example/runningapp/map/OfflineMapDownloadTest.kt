package com.example.runningapp.map

import com.example.runningapp.analysis.MapFix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Settings row can be told about the offline map, driven through the two things the app
 * cannot see into — the phone's location and Mapbox's store — both faked here.
 */
class OfflineMapDownloadTest {

    private val home = MapFix(50.83, -0.14)
    private val savedEarlier = StoredOfflineMap(bytes = 40_000_000, complete = true, downloadedAtMillis = 1_000L)

    private class FakeStore(var held: StoredOfflineMap?) : OfflineMapStore {
        val downloads = mutableListOf<Pair<MapFix, Long>>()
        var answer: OfflineMapFailure? = null
        var progressToReport: List<OfflineMapProgress> = emptyList()
        var gate: CompletableDeferred<Unit>? = null
        var afterDownload: StoredOfflineMap? = null

        override suspend fun stored(): StoredOfflineMap? = held

        override suspend fun download(
            center: MapFix,
            downloadedAtMillis: Long,
            onProgress: (OfflineMapProgress) -> Unit
        ): OfflineMapFailure? {
            downloads += center to downloadedAtMillis
            progressToReport.forEach(onProgress)
            gate?.await()
            afterDownload?.let { held = it }
            return answer
        }
    }

    private fun TestScope.downloader(
        store: FakeStore,
        place: MapFix? = home,
        now: Long = 5_000L
    ): Pair<OfflineMapDownload, CoroutineScope> {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        return OfflineMapDownload(scope, store, whereAmI = { place }, now = { now }) to scope
    }

    @Test
    fun `it opens on whatever map is already saved`() = runTest {
        val (download, scope) = downloader(FakeStore(savedEarlier))
        advanceUntilIdle()

        assertEquals(OfflineMapState.Ready(savedEarlier), download.state.value)
        scope.cancel()
    }

    @Test
    fun `a download saves the area round where the runner is, stamped with now`() = runTest {
        val fresh = StoredOfflineMap(bytes = 48_000_000, complete = true, downloadedAtMillis = 5_000L)
        val store = FakeStore(savedEarlier).apply { afterDownload = fresh }
        val (download, scope) = downloader(store, now = 5_000L)
        advanceUntilIdle()

        download.download()
        advanceUntilIdle()

        assertEquals(listOf(home to 5_000L), store.downloads)
        // What the row then reports is read back from the store, not assumed from the call.
        assertEquals(OfflineMapState.Ready(fresh), download.state.value)
        scope.cancel()
    }

    @Test
    fun `the row says where the download has got to while it runs`() = runTest {
        val tiles = OfflineMapProgress(OfflineMapStep.TILES, completed = 30, required = 100, bytes = 12_000_000)
        val store = FakeStore(null).apply {
            progressToReport = listOf(tiles)
            gate = CompletableDeferred()
        }
        val (download, scope) = downloader(store)
        advanceUntilIdle()

        download.download()
        runCurrent()

        assertEquals(OfflineMapState.Downloading(tiles), download.state.value)
        store.gate!!.complete(Unit)
        advanceUntilIdle()
        scope.cancel()
    }

    @Test
    fun `a second tap while one is running starts nothing`() = runTest {
        val store = FakeStore(null).apply { gate = CompletableDeferred() }
        val (download, scope) = downloader(store)
        advanceUntilIdle()

        download.download()
        runCurrent()
        download.download()
        store.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, store.downloads.size)
        scope.cancel()
    }

    @Test
    fun `a tap before the saved map has been read is not overwritten by that read`() = runTest {
        // The read that opens the row and the tap race. The tap must win: a row reporting "Saved"
        // over a download it has started would hide the download behind a stale answer.
        val store = FakeStore(savedEarlier).apply { gate = CompletableDeferred() }
        val (download, scope) = downloader(store)

        download.download()
        runCurrent()

        assertTrue(download.state.value is OfflineMapState.Downloading)
        store.gate!!.complete(Unit)
        advanceUntilIdle()
        scope.cancel()
    }

    @Test
    fun `no fix means no download, and the row says why`() = runTest {
        val store = FakeStore(savedEarlier)
        val (download, scope) = downloader(store, place = null)
        advanceUntilIdle()

        download.download()
        advanceUntilIdle()

        assertTrue(store.downloads.isEmpty())
        assertEquals(OfflineMapState.Ready(savedEarlier, OfflineMapFailure.NO_LOCATION), download.state.value)
        scope.cancel()
    }

    @Test
    fun `a failed download reports what the store holds now, not what it held before`() = runTest {
        // A download that stops halfway can leave part of the new area behind. The row says what is
        // actually there rather than repeating the answer from before the tap.
        val part = StoredOfflineMap(bytes = 9_000_000, complete = false, downloadedAtMillis = 5_000L)
        val store = FakeStore(savedEarlier).apply {
            answer = OfflineMapFailure.DOWNLOAD_FAILED
            afterDownload = part
        }
        val (download, scope) = downloader(store)
        advanceUntilIdle()

        download.download()
        advanceUntilIdle()

        assertEquals(OfflineMapState.Ready(part, OfflineMapFailure.DOWNLOAD_FAILED), download.state.value)
        scope.cancel()
    }

    @Test
    fun `a store that cannot be read is read as nothing saved, not a crash`() = runTest {
        // The row lives on the app's scope, which has no handler: an escape would end the app.
        val store = object : OfflineMapStore {
            override suspend fun stored(): StoredOfflineMap? = error("Mapbox could not open its store")
            override suspend fun download(
                center: MapFix,
                downloadedAtMillis: Long,
                onProgress: (OfflineMapProgress) -> Unit
            ): OfflineMapFailure? = null
        }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val download = OfflineMapDownload(scope, store, whereAmI = { home }, now = { 0L })
        advanceUntilIdle()

        assertEquals(OfflineMapState.Ready(null), download.state.value)
        download.download()
        advanceUntilIdle()
        assertEquals(OfflineMapState.Ready(null), download.state.value)
        scope.cancel()
    }

    @Test
    fun `refused location permission is said on the row, and nothing is downloaded`() = runTest {
        val store = FakeStore(null)
        val (download, scope) = downloader(store)
        advanceUntilIdle()

        download.locationRefused()
        advanceUntilIdle()

        assertTrue(store.downloads.isEmpty())
        assertEquals(OfflineMapState.Ready(null, OfflineMapFailure.NO_PERMISSION), download.state.value)
        scope.cancel()
    }
}
