package com.example.runningapp.archive

import android.util.Log
import java.io.OutputStream
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The folder the runner picked, as the archive needs to see it (#85).
 *
 * An interface rather than the Storage Access Framework directly, so the order of operations that
 * makes a backup safe — write under a temporary name, promote it, only then delete anything — can
 * be tested without a phone and without a real folder to lose files in.
 *
 * Every method throws on failure rather than returning a flag: a folder that has been moved,
 * unmounted or had its permission revoked fails at whichever call reaches it first, and [Archiver]
 * treats them all the same way.
 */
interface ArchiveFolder {

    /** The names of the files in the folder — this app's and everyone else's. */
    suspend fun list(): List<String>

    /**
     * Writes [fileName], replacing any file already called that.
     *
     * Replacing rather than refusing is what makes a backup repeatable: an attempt that died partway
     * leaves a `.part` under the name the next attempt wants, and that wreckage should simply be
     * taken over rather than made to block a backup.
     */
    suspend fun write(fileName: String, contents: suspend (OutputStream) -> Unit)

    suspend fun rename(fileName: String, newName: String)

    suspend fun delete(fileName: String)
}

/** What came of asking for a backup. */
sealed interface ArchiveOutcome {

    /** Nothing was written because the runner has not picked a folder to write to. */
    object NoFolderChosen : ArchiveOutcome

    data class Archived(val fileName: String, val atEpochMillis: Long) : ArchiveOutcome

    data class Failed(val reason: String) : ArchiveOutcome
}

/**
 * Writes one complete archive into the runner's folder, and retires what it replaces (#85).
 *
 * The order below is the whole point of this class, and it is chosen so that a backup interrupted
 * at any moment leaves the runner no worse off than before it started:
 *
 *  1. sweep away anything an earlier attempt abandoned, so a folder that keeps refusing does not
 *     collect one unfinished copy of the database per try;
 *  2. write the new archive under a `.part` name — a name nothing counts as a backup;
 *  3. promote it by renaming, which is the instant it becomes one;
 *  4. only now retire the surplus, so the folder never dips below its old contents while the
 *     replacement is still being written;
 *  5. and only now record the time, because a last-backup time is a claim that there is a backup.
 *
 * Rotation is deliberately *not* allowed to fail the backup. By the time it runs the archive is
 * written and promoted; a folder that then refuses a delete has cost the runner nothing but disk.
 */
class Archiver(
    /** The folder in force, or null if the runner has not picked one. */
    private val folder: suspend () -> ArchiveFolder?,
    /** Everything the archive should contain, assembled fresh for the moment it is being made. */
    private val contents: suspend (createdAtEpochMillis: Long) -> List<ArchiveEntry>,
    /** Records when a backup last succeeded. Called only after one did. */
    private val onArchived: suspend (atEpochMillis: Long) -> Unit,
    private val now: () -> Long,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    /**
     * One backup at a time, whoever asked.
     *
     * The button and the monthly job share this one archiver, and two attempts overlapping would
     * share more than they can: the same `.part` name, the same finished name, and the same local
     * snapshot of the database. The second attempt's sweep would delete the first's part-file
     * mid-write, and the first could record a last-backup time for an archive the second had already
     * replaced. Serialised rather than refused, because both attempts are asking for the same true
     * thing — an archive of the history as it stands — and the second simply gets it a moment later.
     */
    private val oneAtATime = Mutex()

    suspend fun archiveNow(): ArchiveOutcome = oneAtATime.withLock { archive() }

    private suspend fun archive(): ArchiveOutcome {
        val destination = try {
            folder()
        } catch (e: Exception) {
            Log.w(TAG, "Could not reach the backup folder", e)
            return ArchiveOutcome.Failed(FOLDER_UNREACHABLE)
        } ?: return ArchiveOutcome.NoFolderChosen

        val at = now()
        val finishedName = ArchiveNames.archiveName(at, zoneId)
        val inProgressName = ArchiveNames.inProgressName(at, zoneId)

        // Anything a previous attempt left unfinished goes before this one starts, not only after
        // it succeeds. A folder that keeps refusing — read-only, full, signed out — would otherwise
        // gain one abandoned copy of the whole database per attempt, and nothing would ever sweep
        // them, because sweeping only happens after a backup that worked.
        runCatching {
            destination.list().filter(ArchiveNames::isAbandoned).forEach { destination.delete(it) }
        }.onFailure { Log.w(TAG, "Could not clear unfinished archives", it) }

        try {
            destination.write(inProgressName) { out -> ArchiveZip.write(out, contents(at)) }
        } catch (e: Exception) {
            Log.w(TAG, "Backup failed while writing", e)
            // A half-written file is no use to anyone, so it goes. It cannot be mistaken for a
            // backup while it waits to be deleted either — it never had an archive's name.
            runCatching { destination.delete(inProgressName) }
            return ArchiveOutcome.Failed(e.message ?: WRITE_FAILED)
        }

        try {
            // Names carry seconds and backups are taken one at a time, so this name should be free.
            // Cleared rather than assumed, because some folders answer an occupied name by quietly
            // inventing "…(1).zip" and leaving both — and a failure here is fatal to the promotion
            // rather than shrugged off, because a folder that kept the old file would make the
            // rename *look* like it worked: the check that the new name is now present would be
            // satisfied by the very file that should have gone. Failing instead leaves the finished
            // `.part` in place, which is the newest complete copy in the folder.
            destination.delete(finishedName)
            destination.rename(inProgressName, finishedName)
        } catch (e: Exception) {
            Log.w(TAG, "Backup could not be promoted", e)
            // Deliberately **not** deleted. By this point it is a complete archive — everything
            // this backup set out to write — and only its name is wrong. It is also the newest copy
            // of the history in the folder, which matters most in exactly the case that got here:
            // the same-minute delete above may just have retired the archive it was replacing. It
            // is not counted as a backup, and the next attempt sweeps it.
            return ArchiveOutcome.Failed(e.message ?: PROMOTION_FAILED)
        }

        // Listed *after* the promotion, so the archive just written is one of the ones being kept.
        runCatching {
            ArchiveNames.retire(destination.list(), justWritten = finishedName)
                .forEach { destination.delete(it) }
        }.onFailure { Log.w(TAG, "Could not retire older archives", it) }

        onArchived(at)
        return ArchiveOutcome.Archived(finishedName, at)
    }

    private companion object {
        const val TAG = "Archive"
        const val FOLDER_UNREACHABLE = "The backup folder could not be opened"
        const val WRITE_FAILED = "The archive could not be written"
        const val PROMOTION_FAILED = "The archive could not be named"
    }
}
