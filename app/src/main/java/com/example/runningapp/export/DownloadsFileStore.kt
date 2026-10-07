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
    /**
     * The name the file now has in Downloads, or null if it could not be saved. Not always
     * [fileName]: when that name is taken by a file this install cannot see, MediaStore numbers
     * the new one, and the runner has to be told the name that was really written.
     */
    suspend fun save(fileName: String, mimeType: String, contents: ByteArray): String?
}

/**
 * Writes into the public `Download/` folder through `MediaStore.Downloads`, the way
 * [com.example.runningapp.data.DatabaseBackupManager] does.
 *
 * Saving the same name again overwrites this app's own earlier file rather than letting MediaStore
 * invent "name (1).fit", so tapping twice on one run leaves one file. Only entries this install
 * created are visible to the query, so a file of the same name from anywhere else is left alone and
 * MediaStore numbers the new one — which the next save finds again by that numbered name, and the
 * caller is told it, so the runner is never pointed at the other file.
 */
class MediaStoreDownloadsFileStore(context: Context) : DownloadsFileStore {

    private val resolver = context.applicationContext.contentResolver

    override suspend fun save(fileName: String, mimeType: String, contents: ByteArray): String? =
        withContext(Dispatchers.IO) {
            try {
                val existing = findOwn(fileName)
                if (existing != null) {
                    // A stale row — its file deleted elsewhere — throws here; that must fall
                    // through to a fresh insert rather than fail every later send.
                    val overwritten = runCatching {
                        // "wt": truncate, so a shorter file never keeps the tail of a longer one.
                        resolver.openOutputStream(existing, "wt")?.use { it.write(contents) } != null
                    }.getOrDefault(false)
                    if (overwritten) return@withContext displayName(existing) ?: fileName
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
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
                displayName(uri) ?: fileName
            } catch (e: Exception) {
                Log.e("DownloadsStore", "Could not save $fileName to Downloads", e)
                null
            }
        }

    /** The name MediaStore actually gave the entry — it may have numbered ours. */
    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    /**
     * This install's entry for [fileName]: the name itself, or the "name (N).ext" MediaStore
     * numbered it to. Newest first, so a repeat save lands on the file the last one named.
     */
    private fun findOwn(fileName: String): Uri? {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot < 0) fileName else fileName.substring(0, dot)
        val extension = if (dot < 0) "" else fileName.substring(dot)
        // LIKE only narrows the query: '_' and '%' in a name are wildcards, so the exact shape is
        // checked below.
        val like = escapeLike(base) + "%" + escapeLike(extension)
        val shape = Regex(Regex.escape(base) + "( \\(\\d+\\))?" + Regex.escape(extension))
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? ESCAPE '\\' " +
                "AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(like, "${Environment.DIRECTORY_DOWNLOADS}/"),
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (shape.matches(cursor.getString(1) ?: continue)) {
                    return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                }
            }
        }
        return null
    }

    private fun escapeLike(text: String): String =
        text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

/** Garmin Connect's web page for uploading a finished activity file (#217). */
const val GARMIN_IMPORT_URL = "https://connect.garmin.com/app/import-data"

/** A FIT file waiting in Downloads for the runner to pick on Garmin's page. */
data class GarminImportFile(val sessionId: Long, val fileName: String)
