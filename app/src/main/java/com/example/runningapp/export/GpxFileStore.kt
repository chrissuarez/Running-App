package com.example.runningapp.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.runningapp.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a generated GPX file goes so the share sheet can read it. */
interface GpxFileStore {
    /** The shareable Uri of the written file, or null if the file could not be made shareable. */
    suspend fun write(fileName: String, contents: String): Uri?
}

/**
 * Writes the export into a private cache directory and hands back a `content://` Uri granted to
 * whichever app the runner picks in the share sheet (#84). The cache is the right home: the file
 * exists only to be handed on, and Android is free to reclaim it afterwards.
 */
class FileProviderGpxFileStore(context: Context) : GpxFileStore {

    private val appContext = context.applicationContext

    override suspend fun write(fileName: String, contents: String): Uri = withContext(Dispatchers.IO) {
        val directory = File(appContext.cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
        // Re-sharing the same run overwrites its own file, and nothing else is swept up: the
        // receiving app reads the Uri when it gets round to it — Gmail attaches on send, Drive
        // uploads in the background — so clearing out an earlier export could break a share still in
        // flight. These are a few KB each, in a cache Android is free to reclaim.
        val file = File(directory, fileName)
        file.writeText(contents)
        FileProvider.getUriForFile(appContext, "${BuildConfig.APPLICATION_ID}$AUTHORITY_SUFFIX", file)
    }

    companion object {
        /** Must match the `gpx_share_paths.xml` cache-path. */
        const val SHARE_DIRECTORY = "shared-gpx"

        /**
         * Completes the provider authority declared in the manifest as `${applicationId}.fileprovider`
         * — hence BuildConfig rather than `packageName`, which a build-type suffix would part company
         * with.
         */
        private const val AUTHORITY_SUFFIX = ".fileprovider"
    }
}
