package com.example.runningapp.export

import com.example.runningapp.data.RunnerSession
import com.example.runningapp.ranAt
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What an exported run is called, whatever format it leaves in (#218).
 *
 * One place, because the app now writes a run out two ways — GPX and FIT — and two runs of the same
 * evening arriving in Drive under names that disagreed about which evening it was would be a bug
 * with nowhere to live.
 */
object RunExportName {

    private val NAME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.UK)

    private val FILE_NAME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.UK)

    /**
     * Named for the evening the runner ran, read off the Run's own stamp (#304). [zoneId] is only
     * the fallback for a Run recorded before v32 — see [RunnerSession.ranAt].
     */
    fun runName(session: RunnerSession, zoneId: ZoneId = ZoneId.systemDefault()): String =
        "Run " + NAME_FORMAT.format(session.ranAt(zoneId))

    /**
     * Lower-case and hyphenated: it becomes a real file name in Drive, on a laptop, in an email.
     *
     * The run's own id closes the name off: exports share one cache directory and a later write to
     * the same name overwrites the earlier file, which would hand a share still in flight the wrong
     * run. Two runs can share a local date and minute — back-to-back intervals, or the hour a clock
     * change repeats — but never an id.
     *
     * The extension is last, so the same run in two formats is two files rather than one overwriting
     * the other.
     */
    fun fileName(
        session: RunnerSession,
        extension: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String = "run-" + FILE_NAME_FORMAT.format(session.ranAt(zoneId)) + "-" + session.id + "." + extension
}
