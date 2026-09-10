package com.example.runningapp.map

import android.content.Context
import android.util.Log
import com.example.runningapp.analysis.MapFix
import com.mapbox.bindgen.Value
import com.mapbox.common.TileRegionErrorType
import com.mapbox.common.TileRegionLoadOptions
import com.mapbox.common.TileStore
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.GlyphsRasterizationMode
import com.mapbox.maps.OfflineManager
import com.mapbox.maps.Style
import com.mapbox.maps.StylePackErrorType
import com.mapbox.maps.StylePackLoadOptions
import com.mapbox.maps.TilesetDescriptorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * [OfflineMapStore] on Mapbox's own offline API (#42): a style pack for the map's look, and one tile
 * region for the ground.
 *
 * **The style is the one every map in the app draws**, [Style.STANDARD]. Its day and night looks are
 * settings on that one style, not two styles, so one pack serves both — see `MapCard`.
 *
 * **Replacing is Mapbox's own update.** The region always has the same id, and loading a region under
 * an id that already exists updates it to the new ground rather than adding a second. So there is only
 * ever one area on the phone, and the tile-pack count never builds up towards Mapbox's cap. The old
 * region is deliberately not removed first: a download that then failed would leave the runner with no
 * map at all, where updating in place leaves whatever Mapbox kept.
 *
 * **Nothing here needs telling to use the result.** The maps read offline data from the default tile
 * store and the default offline manager, which are the ones created here.
 *
 * Mapbox answers on its own threads, once per call. A cancelled coroutine cancels the Mapbox call, and
 * the answer Mapbox then gives is dropped, because a cancelled continuation ignores a late resume.
 */
class MapboxOfflineMapStore(private val context: Context) : OfflineMapStore {

    private val offlineManager by lazy { OfflineManager() }
    private val tileStore by lazy { TileStore.create() }

    override suspend fun stored(): StoredOfflineMap? {
        val region = suspendCancellableCoroutine { done ->
            tileStore.getTileRegion(REGION_ID) { done.resume(it) }
        }.value ?: return null
        val style = suspendCancellableCoroutine { done ->
            offlineManager.getStylePack(STYLE) { done.resume(it) }
        }.value
        val downloadedAt = suspendCancellableCoroutine { done ->
            tileStore.getTileRegionMetadata(REGION_ID) { done.resume(it) }
        }.value?.let(::downloadedAtOf)

        val regionWhole = region.requiredResourceCount > 0 &&
            region.completedResourceCount >= region.requiredResourceCount
        // A region without its style is not a map: the ground is there and nothing can draw it.
        val styleWhole = style != null && style.completedResourceCount >= style.requiredResourceCount
        return StoredOfflineMap(
            bytes = region.completedResourceSize + (style?.completedResourceSize ?: 0L),
            complete = regionWhole && styleWhole,
            downloadedAtMillis = downloadedAt,
        )
    }

    override suspend fun download(
        center: MapFix,
        downloadedAtMillis: Long,
        onProgress: (OfflineMapProgress) -> Unit,
    ): OfflineMapFailure? {
        // The style first, because it is small and the tiles are useless without it. A connection
        // that cannot fetch a few megabytes of style is told so before the big step begins.
        val styleResult = suspendCancellableCoroutine { done ->
            val call = offlineManager.loadStylePack(
                STYLE,
                StylePackLoadOptions.Builder()
                    .glyphsRasterizationMode(GlyphsRasterizationMode.IDEOGRAPHS_RASTERIZED_LOCALLY)
                    .build(),
                { progress ->
                    onProgress(
                        OfflineMapProgress(
                            step = OfflineMapStep.STYLE,
                            completed = progress.completedResourceCount,
                            required = progress.requiredResourceCount,
                            bytes = progress.completedResourceSize,
                        )
                    )
                },
                { done.resume(it) },
            )
            done.invokeOnCancellation { call.cancel() }
        }
        styleResult.error?.let { error ->
            Log.w(TAG, "Style pack failed: ${error.type} ${error.message}")
            return if (error.type == StylePackErrorType.DISK_FULL) OfflineMapFailure.DISK_FULL
            else OfflineMapFailure.DOWNLOAD_FAILED
        }

        val descriptor = offlineManager.createTilesetDescriptor(
            TilesetDescriptorOptions.Builder()
                .styleURI(STYLE)
                // From the whole world down to street level. The coarse zooms cost almost nothing —
                // one pack covers the world at the top — and they are what the map shows while it
                // settles on the runner.
                .minZoom(0)
                // Mapbox's vector map stops at 16 and draws anything closer from 16's tiles.
                .maxZoom(16)
                .pixelRatio(context.resources.displayMetrics.density)
                .build()
        )
        val ring = offlineAreaRing(center).map { Point.fromLngLat(it.longitude, it.latitude) }
        val options = TileRegionLoadOptions.Builder()
            .geometry(Polygon.fromLngLats(listOf(ring)))
            .descriptors(listOf(descriptor))
            .metadata(Value.valueOf(hashMapOf(DOWNLOADED_AT to Value.valueOf(downloadedAtMillis))))
            .build()

        val regionResult = suspendCancellableCoroutine { done ->
            val call = tileStore.loadTileRegion(
                REGION_ID,
                options,
                { progress ->
                    onProgress(
                        OfflineMapProgress(
                            step = OfflineMapStep.TILES,
                            completed = progress.completedResourceCount,
                            required = progress.requiredResourceCount,
                            bytes = progress.completedResourceSize,
                        )
                    )
                },
                { done.resume(it) },
            )
            done.invokeOnCancellation { call.cancel() }
        }
        regionResult.error?.let { error ->
            Log.w(TAG, "Tile region failed: ${error.type} ${error.message}")
            return when (error.type) {
                TileRegionErrorType.DISK_FULL -> OfflineMapFailure.DISK_FULL
                TileRegionErrorType.TILE_COUNT_EXCEEDED -> OfflineMapFailure.AREA_TOO_BIG
                else -> OfflineMapFailure.DOWNLOAD_FAILED
            }
        }
        return null
    }

    /** When the area was taken, as written into the region's own metadata by [download]. */
    private fun downloadedAtOf(metadata: Value): Long? =
        ((metadata.contents as? Map<*, *>)?.get(DOWNLOADED_AT) as? Value)
            ?.contents
            ?.let { it as? Number }
            ?.toLong()

    private companion object {
        const val TAG = "OfflineMap"
        const val STYLE = Style.STANDARD

        /** The one area. Every download reuses it, which is what makes the next one a replacement. */
        const val REGION_ID = "home-area"
        const val DOWNLOADED_AT = "downloadedAtMillis"
    }
}
