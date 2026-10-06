package com.example.runningapp.export

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a file goes so the runner can pick it in a browser (#217).
 *
 * A different seam from [ExportFileStore] on purpose: that one writes to a private cache for a
 * share sheet to read, and a browser's file picker cannot reach a cache.
 */
interface DownloadsFileStore {
    /** True once [fileName] is in the phone's Downloads folder holding [contents]. */
    suspend fun save(fileName: String, mimeType: String, contents: ByteArray): Boolean
}

/**
 * Writes into the public `Download/` folder through `MediaStore.Downloads`, the way
 * [com.example.runningapp.data.DatabaseBackupManager] does.
 *
 * Saving the same name again overwrites this app's own earlier file rather than letting MediaStore
 * invent "name (1).fit", so tapping twice on one run leaves one file. Only entries this install
 * created are visible to the query, so a file of the same name from anywhere else is left alone and
 * MediaStore numbers the new one.
 */
class MediaStoreDownloadsFileStore(context: Context) : DownloadsFileStore {

    private val resolver = context.applicationContext.contentResolver

    override suspend fun save(fileName: String, mimeType: String, contents: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val existing = findOwn(fileName)
                if (existing != null) {
                    // "wt": truncate, so a shorter file never keeps the tail of a longer one.
                    val out = resolver.openOutputStream(existing, "wt")
                    if (out != null) {
                        out.use { it.write(contents) }
                        return@withContext true
                    }
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext false
                try {
                    resolver.openOutputStream(uri)?.use { it.write(contents) }
                        ?: error("Could not open an output stream for $fileName")
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null
                    )
                } catch (e: Exception) {
                    // Never leave a half-written entry in the runner's Downloads.
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
                true
            } catch (e: Exception) {
                Log.e("DownloadsStore", "Could not save $fileName to Downloads", e)
                false
            }
        }

    private fun findOwn(fileName: String): Uri? {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(fileName, "${Environment.DIRECTORY_DOWNLOADS}/"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
            }
        }
        return null
    }
}

/** Garmin Connect's web page for uploading a finished activity file (#217). */
const val GARMIN_IMPORT_URL = "https://connect.garmin.com/app/import-data"

/** A FIT file waiting in Downloads for the runner to pick on Garmin's page. */
data class GarminImportFile(val sessionId: Long, val fileName: String)
