package com.example.runningapp.ui

import com.example.runningapp.map.OfflineMapFailure
import com.example.runningapp.map.OfflineMapProgress
import com.example.runningapp.map.OfflineMapState
import com.example.runningapp.map.OfflineMapStep
import com.example.runningapp.map.StoredOfflineMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class OfflineMapModelsTest {

    private val utc = ZoneOffset.UTC
    // August, not September: UK English on the JVM writes "Sept", which is the calendar library's choice to make.
    private val tenAugust = LocalDate.of(2026, 8, 10).atTime(18, 30).toInstant(utc).toEpochMilli()

    @Test
    fun `the row is named for what a tap does, until a tap is under way`() {
        assertEquals("Download map for this area", offlineMapRowLabel(OfflineMapState.Checking))
        assertEquals("Download map for this area", offlineMapRowLabel(OfflineMapState.Ready(null)))
        assertEquals("Finding where you are…", offlineMapRowLabel(OfflineMapState.Locating))
        assertEquals("Downloading map…", offlineMapRowLabel(OfflineMapState.Downloading(null)))
    }

    @Test
    fun `with nothing saved it says so, and what a tap would save`() {
        assertEquals(
            "None saved yet. Saves about 15 km around you, for runs with no signal.",
            offlineMapRowSubtitle(OfflineMapState.Ready(null), utc)
        )
    }

    @Test
    fun `a saved map gives its date and size, and says a tap replaces it`() {
        val stored = StoredOfflineMap(bytes = 48_400_000, complete = true, downloadedAtMillis = tenAugust)

        assertEquals(
            "Saved 10 Aug 2026 · 48 MB. Tapping again replaces it.",
            offlineMapRowSubtitle(OfflineMapState.Ready(stored), utc)
        )
    }

    @Test
    fun `a saved map with no date gives its size alone`() {
        val stored = StoredOfflineMap(bytes = 48_400_000, complete = true, downloadedAtMillis = null)

        assertEquals("Saved · 48 MB. Tapping again replaces it.", offlineMapRowSubtitle(OfflineMapState.Ready(stored), utc))
    }

    @Test
    fun `a map only partly saved says so, rather than calling it saved`() {
        val stored = StoredOfflineMap(bytes = 9_200_000, complete = false, downloadedAtMillis = tenAugust)

        assertEquals(
            "Only part saved · 9.2 MB. Tap to try again.",
            offlineMapRowSubtitle(OfflineMapState.Ready(stored), utc)
        )
    }

    @Test
    fun `a failure is said first, then what is saved now`() {
        val part = StoredOfflineMap(bytes = 9_200_000, complete = false, downloadedAtMillis = tenAugust)

        assertEquals(
            "Download failed. Check you have internet, then try again. Only part saved · 9.2 MB. Tap to try again.",
            offlineMapRowSubtitle(OfflineMapState.Ready(part, OfflineMapFailure.DOWNLOAD_FAILED), utc)
        )
        assertEquals(
            "Couldn't find where you are. Turn on location, then try again. None saved yet.",
            offlineMapRowSubtitle(OfflineMapState.Ready(null, OfflineMapFailure.NO_LOCATION), utc)
        )
    }

    @Test
    fun `every failure has its own sentence`() {
        OfflineMapFailure.entries.forEach { failure ->
            val line = offlineMapRowSubtitle(OfflineMapState.Ready(null, failure), utc)!!
            assert(line.endsWith("None saved yet.")) { line }
        }
        assertEquals(
            OfflineMapFailure.entries.size,
            OfflineMapFailure.entries.map { offlineMapRowSubtitle(OfflineMapState.Ready(null, it), utc) }.toSet().size
        )
    }

    @Test
    fun `progress names the step, the share done and the size so far`() {
        assertEquals(
            "Step 1 of 2, map style · 45% · 1.2 MB",
            offlineMapRowSubtitle(
                OfflineMapState.Downloading(OfflineMapProgress(OfflineMapStep.STYLE, 45, 100, 1_200_000)),
                utc
            )
        )
        assertEquals(
            "Step 2 of 2, map · 37% · 12 MB",
            offlineMapRowSubtitle(
                OfflineMapState.Downloading(OfflineMapProgress(OfflineMapStep.TILES, 37, 100, 12_400_000)),
                utc
            )
        )
    }

    @Test
    fun `a step that has not yet counted its pieces gives no percentage`() {
        assertEquals(
            "Step 2 of 2, map · 0 MB",
            offlineMapRowSubtitle(
                OfflineMapState.Downloading(OfflineMapProgress(OfflineMapStep.TILES, 0, 0, 0)),
                utc
            )
        )
    }

    @Test
    fun `before any progress, and while locating, there is nothing to add`() {
        assertEquals("Starting…", offlineMapRowSubtitle(OfflineMapState.Downloading(null), utc))
        assertNull(offlineMapRowSubtitle(OfflineMapState.Locating, utc))
        assertNull(offlineMapRowSubtitle(OfflineMapState.Checking, utc))
    }

    @Test
    fun `sizes under ten megabytes keep one decimal, bigger ones are whole`() {
        assertEquals("0.4 MB", megabytes(400_000))
        assertEquals("9.9 MB", megabytes(9_940_000))
        assertEquals("10 MB", megabytes(10_000_000))
        assertEquals("148 MB", megabytes(148_400_000))
    }
}
