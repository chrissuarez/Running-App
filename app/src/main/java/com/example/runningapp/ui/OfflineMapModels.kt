package com.example.runningapp.ui

import com.example.runningapp.map.OFFLINE_AREA_RADIUS_METERS
import com.example.runningapp.map.OfflineMapFailure
import com.example.runningapp.map.OfflineMapProgress
import com.example.runningapp.map.OfflineMapState
import com.example.runningapp.map.OfflineMapStep
import com.example.runningapp.map.StoredOfflineMap
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the offline-map row in Settings says (#42).
 *
 * Pure and outside the composable for the reason the Backup lines are: the row is all the runner
 * knows about whether the map will be there with no signal, and a row that says "Saved" over half a
 * map is the bug that matters.
 */

private val SAVED_ON_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

private val RADIUS_KM = (OFFLINE_AREA_RADIUS_METERS / 1000).roundToInt()

fun offlineMapRowLabel(state: OfflineMapState): String = when (state) {
    OfflineMapState.Checking, is OfflineMapState.Ready -> "Download map for this area"
    OfflineMapState.Locating -> "Finding where you are…"
    is OfflineMapState.Downloading -> "Downloading map…"
}

/**
 * The line under the row, or null when the label already says it all.
 *
 * A failure is said first, because it answers the tap just made, and then what is saved now — the
 * runner's real question is still "will the map be there".
 */
fun offlineMapRowSubtitle(
    state: OfflineMapState,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String? = when (state) {
    OfflineMapState.Checking, OfflineMapState.Locating -> null
    is OfflineMapState.Downloading -> state.progress?.let(::progressLine) ?: "Starting…"
    is OfflineMapState.Ready -> when (val failure = state.failure) {
        null -> storedLine(state.stored, zoneId, explain = true)
        else -> "${failureLine(failure)} ${storedLine(state.stored, zoneId, explain = false)}"
    }
}

private fun storedLine(stored: StoredOfflineMap?, zoneId: ZoneId, explain: Boolean): String = when {
    stored == null ->
        if (explain) "None saved yet. Saves about $RADIUS_KM km around you, for runs with no signal."
        else "None saved yet."
    !stored.complete -> "Only part saved · ${megabytes(stored.bytes)}. Tap to try again."
    stored.downloadedAtMillis == null -> "Saved · ${megabytes(stored.bytes)}. Tapping again replaces it."
    else -> {
        val on = SAVED_ON_FORMAT.format(Instant.ofEpochMilli(stored.downloadedAtMillis).atZone(zoneId))
        "Saved $on · ${megabytes(stored.bytes)}. Tapping again replaces it."
    }
}

private fun failureLine(failure: OfflineMapFailure): String = when (failure) {
    OfflineMapFailure.NO_PERMISSION -> "Needs location, to know which area to save."
    OfflineMapFailure.NO_LOCATION -> "Couldn't find where you are. Turn on location, then try again."
    OfflineMapFailure.DISK_FULL -> "Phone storage is full. Free some space, then try again."
    OfflineMapFailure.AREA_TOO_BIG -> "Mapbox's limit on saved map for this phone was reached."
    OfflineMapFailure.DOWNLOAD_FAILED -> "Download failed. Check you have internet, then try again."
}

private fun progressLine(progress: OfflineMapProgress): String {
    val step = when (progress.step) {
        OfflineMapStep.STYLE -> "Step 1 of 2, map style"
        OfflineMapStep.TILES -> "Step 2 of 2, map"
    }
    // No percentage until Mapbox has counted what the step needs: "0%" of an unknown is a guess.
    val share = if (progress.required > 0) " · ${progress.completed * 100 / progress.required}%" else ""
    return "$step$share · ${megabytes(progress.bytes)}"
}

/** Decimal megabytes, as the phone's own storage screen counts them. */
fun megabytes(bytes: Long): String {
    if (bytes == 0L) return "0 MB"
    val mb = bytes / 1_000_000.0
    return if (mb < 9.95) String.format(Locale.UK, "%.1f MB", mb) else "${mb.roundToInt()} MB"
}
