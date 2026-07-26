package com.example.runningapp.export

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/** A written GPX file, ready to hand to the share sheet. */
data class GpxShareFile(
    val uri: Uri,
    val fileName: String,
    val runName: String
)

/**
 * The standard Android share sheet for a finished GPX export (#84). The read grant travels with the
 * Intent, so the app the runner picks — Drive, email, Strava — can read this one file and nothing
 * else.
 */
fun gpxShareChooser(file: GpxShareFile): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = GpxWriter.MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, file.uri)
        putExtra(Intent.EXTRA_SUBJECT, file.runName)
        putExtra(Intent.EXTRA_TITLE, file.fileName)
        // Some targets read the attachment from clipData rather than EXTRA_STREAM; both carry the
        // same grant.
        clipData = ClipData.newRawUri(file.fileName, file.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "Share run").apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
