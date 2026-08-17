package com.example.runningapp.export

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/** The two files a finished run can leave as (#84, #218). */
enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    /**
     * The Garmin one. It carries the run's own laps and its own summary, and it can carry a run with
     * no GPS at all, so it is offered first — see [FitWriter].
     */
    FIT(FitWriter.FILE_EXTENSION, FitWriter.MIME_TYPE, "Garmin (.fit)"),

    /** The portable one, for everything that is not Garmin. */
    GPX(GpxWriter.FILE_EXTENSION, GpxWriter.MIME_TYPE, "GPX"),
}

/**
 * A written export, ready to hand to the share sheet.
 *
 * It carries the run it was asked for: the export outlives the screen that requested it, and a
 * chooser is only ever opened by the run whose Share button started it.
 */
data class ExportShareFile(
    val sessionId: Long,
    val uri: Uri,
    val fileName: String,
    val runName: String,
    val format: ExportFormat
)

/**
 * The standard Android share sheet for a finished export (#84). The read grant travels with the
 * Intent, so the app the runner picks — Garmin Connect, Drive, email, Strava — can read this one
 * file and nothing else.
 *
 * The type is the format's own, not one type for both: Garmin Connect's deep-link handler matches on
 * the file, but a chooser offers the runner the apps that claim the *type*, and a `.fit` announced
 * as GPX would be offered to the wrong half of the phone.
 */
fun exportShareChooser(file: ExportShareFile): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = file.format.mimeType
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
