package com.example.runningapp.archive

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * Turns an [ArchiveDocument] into the `archive.json` an archive carries, and back again (#85).
 *
 * Pretty-printed, because the point of this half of the archive is that a runner can open it and
 * read it — on a laptop, years later, with this app long gone. A few bytes of indentation is a
 * cheap price for that.
 *
 * Nulls are written out rather than omitted, for the same reason: a run with no note and no weather
 * should *say* so. Absent fields read back as null (or zero, for a number) whatever the writer
 * intended, so writing them makes the document say what it means instead of leaving the reader to
 * infer it.
 */
object ArchiveJson {

    const val FILE_NAME = "archive.json"

    private val gson: Gson = GsonBuilder()
        .serializeNulls()
        .setPrettyPrinting()
        .create()

    fun write(document: ArchiveDocument): String = gson.toJson(document)

    /**
     * The document, or null if this app cannot honestly claim to understand it.
     *
     * Two ways to get null, and both are refusals rather than failures:
     *  - the text is not this document at all (not JSON, or JSON of some other shape);
     *  - it was written by a later version of the app ([ArchiveDocument.formatVersion] above
     *    [ARCHIVE_FORMAT_VERSION]). A newer document may mean something this code would misread,
     *    and a restore that misreads is worse than one that declines.
     *
     * An *older* known version is accepted: fields added since read back as null or zero, which is
     * the truth about a document that never carried them.
     */
    fun read(json: String): ArchiveDocument? {
        val document = try {
            gson.fromJson(json, ArchiveDocument::class.java)
        } catch (e: Exception) {
            return null
        } ?: return null
        // Gson fills anything the text did not mention with null, including the fields below —
        // which is how "some other JSON entirely" arrives here looking like a document.
        @Suppress("SENSELESS_COMPARISON")
        if (document.settings == null || document.runs == null || document.intervalStats == null) {
            return null
        }
        if (document.formatVersion > ARCHIVE_FORMAT_VERSION) return null
        return document
    }
}
