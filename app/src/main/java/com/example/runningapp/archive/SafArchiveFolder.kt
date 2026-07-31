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
            //
            // Both ends of the move, not just the destination. A file already wearing [newName] —
            // a listing that was stale when the name was cleared, or another client writing into a
            // synced folder during a write long enough for that to happen — would answer for a
            // rename that never took place, and the archive just written would be swept as
            // wreckage while its predecessor was recorded as the backup.
            val after = children()
            if (after.none { it.name == newName } || after.any { it.name == fileName }) {
                throw IOException("Could not rename $fileName to $newName")
            }
        }
    }

    override suspend fun delete(fileName: String) {
        withContext(Dispatchers.IO) { deleteIfPresent(fileName) }
    }

    /**
     * Removes [fileName] if it is there, and refuses to pretend it did.
     *
     * A provider reports a refused delete by returning false as readily as by throwing, and a
     * silently-kept file is the one failure this class must never pass upwards as success: the very
     * next call creates a document under the same name, the provider invents a second one —
     * `…zip.part (1)` — and the archive is written there while promotion still finds and renames the
     * *stale* file. A backup that was never written would be recorded as one.
     *
     * The folder is asked afterwards rather than the return value believed, for the same reason
     * [rename] asks: it is the only answer that means the same thing on every provider.
     */
    private fun deleteIfPresent(fileName: String) {
        val document = documentUri(fileName) ?: return
        DocumentsContract.deleteDocument(resolver, document)
        if (children().any { it.name == fileName }) {
            throw IOException("Could not remove $fileName from the backup folder")
        }
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
         * The stored folder, but only if this install may still write to it.
         *
         * The Uri is in DataStore, which Auto Backup restores onto a new phone; the grant behind it
         * is held by the install that asked for it and is not restored with it. So a runner who
         * upgrades their phone arrives with a folder that is named in Settings and unreachable in
         * fact — every backup failing, the monthly job retrying the same dead address for as long as
         * they never think to tap the row. Treated as no folder at all, the app asks for one, which
         * is the truth of the situation and the one thing that fixes it.
         */
        fun grantedFolder(context: Context, treeUri: String?): Uri? {
            val uri = treeUri?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
            val held = context.applicationContext.contentResolver.persistedUriPermissions
                .any { it.uri == uri && it.isWritePermission }
            return uri.takeIf { held }
        }

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
