package com.example.runningapp.map

import com.example.runningapp.analysis.MapFix
import com.example.runningapp.recording.degreesEastOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * How far round the runner the offline map reaches (#42): about 15 km each way, which is a routine
 * run from the front door and back with room to spare.
 *
 * It fits Mapbox's cap with a wide margin. Mapbox counts offline data in *tile packs*, at most 750 of
 * them on one phone, and a pack's ground is one tile at the pack's own zoom. The finest packs are
 * keyed at zoom 12 — about 6 km across at British latitudes — so a 30 km circle touches at most six
 * by six of them, and far fewer of every coarser kind, for each layer the map style draws. One area
 * at a time, so the count never builds up.
 */
const val OFFLINE_AREA_RADIUS_METERS = 15_000.0

/** Corners of the ring drawn round the runner. Enough that the ring is a circle to the tiles. */
const val OFFLINE_AREA_CORNERS = 64

private const val MEAN_EARTH_RADIUS_METERS = 6_371_008.8

/**
 * The ground to keep: a ring of [corners] points [radiusMeters] out from [center], closed — the last
 * point repeats the first, which is what a GeoJSON polygon requires.
 *
 * A circle rather than a square because the runner can go any way out of the door, and a square's
 * corners are ground a fifth bigger again that no out-and-back of the same length reaches.
 *
 * Laid out on a sphere. Against the ellipsoid every distance a runner is shown is measured on, a
 * corner lands a few tens of metres off at most, which moves no tile.
 */
fun offlineAreaRing(
    center: MapFix,
    radiusMeters: Double = OFFLINE_AREA_RADIUS_METERS,
    corners: Int = OFFLINE_AREA_CORNERS,
): List<MapFix> {
    val lat = Math.toRadians(center.latitude)
    val lon = Math.toRadians(center.longitude)
    val angle = radiusMeters / MEAN_EARTH_RADIUS_METERS
    val ring = (0 until corners).map { i ->
        val bearing = 2.0 * Math.PI * i / corners
        val cornerLat = asin(sin(lat) * cos(angle) + cos(lat) * sin(angle) * cos(bearing))
        val cornerLon = lon + atan2(
            sin(bearing) * sin(angle) * cos(lat),
            cos(angle) - sin(lat) * sin(cornerLat)
        )
        MapFix(
            latitude = Math.toDegrees(cornerLat),
            // Back onto the map: east of a centre near 180° is written just past -180°, not 180.2°.
            longitude = degreesEastOf(0.0, Math.toDegrees(cornerLon)),
        )
    }
    return ring + ring.first()
}

/**
 * The offline map as the phone holds it now.
 *
 * [complete] is false when a download stopped part-way and left some of its area behind — the row
 * must not call that saved. [downloadedAtMillis] is null only for an area whose own record of when it
 * was taken cannot be read.
 */
data class StoredOfflineMap(
    val bytes: Long,
    val complete: Boolean,
    val downloadedAtMillis: Long?,
)

/** The two things a download fetches, in the order it fetches them. */
enum class OfflineMapStep { STYLE, TILES }

/** How far one step has got. [required] is 0 until Mapbox has counted the step's pieces. */
data class OfflineMapProgress(
    val step: OfflineMapStep,
    val completed: Long,
    val required: Long,
    val bytes: Long,
)

/** Why a download did not finish — each one something the runner can act on, and told as such. */
enum class OfflineMapFailure {
    /** Location permission was refused, so there is no "here" to save the area round. */
    NO_PERMISSION,

    /** The phone could not say where it is — location switched off, or no fix to be had. */
    NO_LOCATION,

    DISK_FULL,

    /** Mapbox's cap on saved map for one phone. One area at a time should never reach it. */
    AREA_TOO_BIG,

    /** Anything else, which in practice is no internet. */
    DOWNLOAD_FAILED,
}

sealed interface OfflineMapState {
    /** Before the store has been asked what it holds. */
    data object Checking : OfflineMapState

    /** Nothing under way. [failure] is the last tap's, until the next tap. */
    data class Ready(
        val stored: StoredOfflineMap?,
        val failure: OfflineMapFailure? = null,
    ) : OfflineMapState

    /** Waiting on the phone for where it is. */
    data object Locating : OfflineMapState

    /** Fetching; [progress] is null until the first report. */
    data class Downloading(val progress: OfflineMapProgress?) : OfflineMapState
}

private val OfflineMapState.busy: Boolean
    get() = this is OfflineMapState.Locating || this is OfflineMapState.Downloading

/**
 * Mapbox's offline store, as this app uses it: one area, replaced by the next.
 *
 * Neither call throws. A read that fails is read as nothing saved, and a download that fails says why.
 */
interface OfflineMapStore {
    suspend fun stored(): StoredOfflineMap?

    /**
     * Fetches the map style and the ground round [center], replacing whatever area was saved before.
     * Returns null once both are in, or why they are not.
     */
    suspend fun download(
        center: MapFix,
        downloadedAtMillis: Long,
        onProgress: (OfflineMapProgress) -> Unit,
    ): OfflineMapFailure?
}

/**
 * The "Download map for this area" row in Settings (#42): find where the runner is, save the map
 * round it, and say how that went.
 *
 * Held by the app rather than by a screen, on the app's own scope. A download of tens of megabytes
 * takes as long as the connection makes it, and the runner will leave Settings while it runs; held by
 * the screen, leaving would cancel it.
 *
 * After every attempt — finished, failed or never started — the row reports what the store holds
 * *then*, read back rather than assumed. A download that stops part-way can leave some of the new area
 * behind, and the answer from before the tap would no longer be true.
 */
class OfflineMapDownload(
    private val scope: CoroutineScope,
    private val store: OfflineMapStore,
    private val whereAmI: suspend () -> MapFix?,
    private val now: () -> Long,
) {
    private val _state = MutableStateFlow<OfflineMapState>(OfflineMapState.Checking)
    val state: StateFlow<OfflineMapState> = _state.asStateFlow()

    init {
        scope.launch {
            val stored = store.stored()
            // Only if nothing has happened since. A tap that lands before this read returns has
            // already moved the row on, and the read would put "Saved" back over a download under way.
            _state.compareAndSet(OfflineMapState.Checking, OfflineMapState.Ready(stored))
        }
    }

    /** One download at a time; a tap while one is under way does nothing, and the row says so. */
    fun download() {
        if (_state.getAndUpdate { if (it.busy) it else OfflineMapState.Locating }.busy) return
        scope.launch {
            val failure = try {
                fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The store promises not to throw, and this is the backstop: an escape here would
                // leave the row at "Downloading…" for good, or take the app down with it.
                OfflineMapFailure.DOWNLOAD_FAILED
            }
            _state.value = OfflineMapState.Ready(store.stored(), failure)
        }
    }

    /** The runner said no to location, so there is nowhere to save the area round. */
    fun locationRefused() {
        if (_state.value.busy) return
        scope.launch {
            val stored = store.stored()
            _state.update { if (it.busy) it else OfflineMapState.Ready(stored, OfflineMapFailure.NO_PERMISSION) }
        }
    }

    private suspend fun fetch(): OfflineMapFailure? {
        val place = whereAmI() ?: return OfflineMapFailure.NO_LOCATION
        _state.value = OfflineMapState.Downloading(null)
        return store.download(place, now()) { progress ->
            // Only while this download is the thing on the row. Mapbox reports on its own threads,
            // and a report that arrives after the finish must not put "Downloading…" back over it.
            _state.update { if (it is OfflineMapState.Downloading) OfflineMapState.Downloading(progress) else it }
        }
    }
}
