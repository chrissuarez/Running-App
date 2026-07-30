package com.example.runningapp.archive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The runner's chosen folder, reached through the Storage Access Framework (#85).
 *
 * A folder they picked rather than one the app owns, because that is what makes this a backup: a
 * Drive-synced folder puts the archive somewhere a lost phone cannot take it with it. The grant is
 * persisted at the moment the picker returns ([takePersistedAccess]), so the monthly job can still
 * write there months later without asking again.
 *
 * Built on [DocumentsContract] directly rather than on the `documentfile` library: the four things
 * an archive needs of a folder — list, create, rename, delete — are one call each, and this is the
 * only place in the app that talks to a folder it does not own.
 *
 * Everything here throws [IOException] rather than returning null on failure. A revoked grant, an
 * unmounted SD card and a deleted folder are all the same event to a backup — the folder is not
 * there — and [Archiver] reports them as one.
 */
class SafArchiveFolder(context: Context, private val treeUri: Uri) : ArchiveFolder {

    private val resolver = context.applicationContext.contentResolver

    private val directoryUri: Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private val childrenUri: Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        children().map { it.name }
    }

    override suspend fun write(fileName: String, contents: suspend (OutputStream) -> Unit) {
        withContext(Dispatchers.IO) {
            // A provider handed a name it already holds invents a second one — "…(1).zip" — so the
            // old file goes first and the name means what it says.
            deleteIfPresent(fileName)
            val document = DocumentsContract.createDocument(resolver, directoryUri, MIME_TYPE, fileName)
                ?: throw IOException("Could not create $fileName in the backup folder")
            // "wt" truncates: without it a provider handing back an existing file would leave the
            // tail of whatever was there beyond the end of what is written now.
            val stream = resolver.openOutputStream(document, "wt")
                ?: throw IOException("Could not open $fileName for writing")
            stream.use { contents(it) }
        }
    }

    override suspend fun rename(fileName: String, newName: String) {
        withContext(Dispatchers.IO) {
            val document = documentUri(fileName)
                ?: throw IOException("$fileName is no longer in the backup folder")
            DocumentsContract.renameDocument(resolver, document, newName)
            // The return value cannot be trusted as a verdict: a provider whose document ids do not
            // change with the name — Drive's do not — returns null on a rename that worked. So the
            // folder is asked instead, which is the only answer that means the same thing
            // everywhere.
            if (children().none { it.name == newName }) {
                throw IOException("Could not rename $fileName to $newName")
            }
        }
    }

    override suspend fun delete(fileName: String) {
        withContext(Dispatchers.IO) { deleteIfPresent(fileName) }
    }

    private fun deleteIfPresent(fileName: String) {
        val document = documentUri(fileName) ?: return
        DocumentsContract.deleteDocument(resolver, document)
    }

    private fun documentUri(fileName: String): Uri? =
        children().firstOrNull { it.name == fileName }
            ?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.documentId) }

    private data class Child(val name: String, val documentId: String)

    private fun children(): List<Child> {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        val cursor = try {
            resolver.query(childrenUri, projection, null, null, null)
        } catch (e: SecurityException) {
            // The grant was revoked, or this is a Uri from an install that no longer holds it.
            throw IOException("The backup folder is no longer available", e)
        } ?: throw IOException("The backup folder could not be read")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: continue
                    add(Child(name = name, documentId = it.getString(0)))
                }
            }
        }
    }

    companion object {
        /**
         * Deliberately not `application/zip`, and this is load-bearing.
         *
         * A provider is entitled to correct a name whose extension disagrees with the MIME type it
         * was created under, and Android's own does: `FileUtils.splitFileName` keeps the name only
         * when the given type matches the type the extension implies. `.part` implies nothing, so
         * it falls back to `application/octet-stream` — which matches this and leaves the name
         * alone. Created as `application/zip` the same name disagrees, and comes back as
         * `…zip.part.zip`: an unfinished archive wearing a finished archive's name, which is the
         * one state [ArchiveNames] exists to make impossible.
         */
        private const val MIME_TYPE = "application/octet-stream"

        /**
         * Keeps the folder reachable after this process — and this install — has gone away.
         *
         * Called with the Uri the picker returned, on the Activity that launched it, before the
         * grant expires with the task that received it. Without this the monthly job would find the
         * folder closed the first time it ran.
         */
        fun takePersistedAccess(context: Context, treeUri: Uri) {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }
}
