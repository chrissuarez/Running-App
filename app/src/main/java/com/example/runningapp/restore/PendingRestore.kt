package com.example.runningapp.restore

import android.content.Context
import android.util.Log
import com.example.runningapp.archive.ArchivedSettings
import com.example.runningapp.data.DatabaseBackupManager
import java.io.File

/**
 * Holds a confirmed restore until the one moment it is safe to carry out (#86).
 *
 * The database is open and being read the instant the runner confirms — History is drawing off it,
 * a background job may be writing to it. Replacing the file underneath all of that is the one way
 * this feature could destroy the thing it exists to rescue. So confirming does not restore anything:
 * it *arms* a restore, and the app closes and reopens itself. On the way back up, before Room has
 * opened anything, [applyIfArmed] moves the staged snapshot into place. That is the same window the
 * automatic Downloads restore already uses, and it is the only point in the app's life at which the
 * database provably has no readers.
 *
 * The marker is a file rather than a preference so it lives and dies with the staged snapshot beside
 * it, in storage the restore already depends on. A phone that dies between the confirmation and the
 * relaunch finds the restore still armed and finishes it, rather than leaving the runner with an
 * unexplained un-restored phone; a phone that dies mid-move finds either the old database or the new
 * one, never a blend, because the move is a rename.
 */
object PendingRestore {
    private const val TAG = "DbRestore"

    /** Written last and read first: its presence is the whole decision. */
    private const val MARKER = "restore-armed"

    private fun marker(context: Context) = File(RestoreReader.stagingDirectory(context), MARKER)

    private fun stagedDatabase(context: Context) =
        File(RestoreReader.stagingDirectory(context), RestoreReader.STAGED_DATABASE)

    /**
     * Commits to restoring whatever [RestoreReader.stage] left staged, at the next launch.
     *
     * Returns false when there is nothing staged to arm — a pick lost to the process being killed
     * while the confirmation was on screen. Better to make the runner pick again than to arm a
     * restore with no file behind it.
     */
    fun arm(context: Context): Boolean {
        if (!stagedDatabase(context).exists()) {
            Log.w(TAG, "Nothing staged to restore; not arming")
            return false
        }
        return runCatching { marker(context).writeBytes(ByteArray(0)); true }
            .onFailure { Log.w(TAG, "Could not arm the restore", it) }
            .getOrDefault(false)
    }

    /** Whether a confirmed restore is waiting to be applied. */
    fun isArmed(context: Context): Boolean = marker(context).exists()

    /**
     * Applies an armed restore, or clears away an abandoned pick. Returns true if history was
     * replaced.
     *
     * **Call before Room opens the database, and before the automatic Downloads restore.** Ordering
     * against that one matters: this is the restore the runner explicitly asked for, so it wins, and
     * once it has run the database exists, which is exactly the condition under which the automatic
     * restore correctly does nothing.
     *
     * [applySettings] is handed an archive's settings *after* its history is in place, and never
     * otherwise. Settings and history are two halves of one restore, and the harmful order is the
     * one that leaves the settings replaced and the history not: every run on the phone would then
     * be read against a heart-rate profile and a training plan from a file the runner never got.
     * The reverse gap — history restored, settings a moment behind — is survivable, and is closed by
     * leaving the restore armed until both halves have landed.
     *
     * Clearing an *un*-armed staging directory is the other half of the job. A pick the runner
     * backed out of leaves a whole copy of a database in app storage; nothing else would ever
     * remove it, and it would sit there as large as the history itself.
     */
    fun applyIfArmed(
        context: Context,
        applySettings: (ArchivedSettings) -> Unit = {},
    ): Boolean {
        if (!isArmed(context)) {
            if (RestoreReader.stagingDirectory(context).exists()) RestoreReader.clear(context)
            return false
        }
        val staged = stagedDatabase(context)
        var replaced = false
        if (staged.exists()) {
            try {
                moveIntoPlace(context, staged)
                replaced = true
                Log.d(TAG, "Restored run history from the picked backup")
            } catch (e: StrandedPreviousLog) {
                // The restore failed *and* could not put the previous database's log back, so that
                // log is sitting in staging under a name nothing else knows. Clearing up would
                // delete it, and it may hold runs that exist nowhere else. Everything is left
                // exactly where it is instead: the marker survives, and the next launch tries the
                // whole move again — this time adopting the log already set aside, and putting it
                // back if it fails again. Storage that stays broken costs a retry per launch and
                // nothing more; clearing up would cost the runs.
                Log.w(TAG, "Could not restore, and the previous log is still set aside; leaving it", e)
                return false
            } catch (e: Exception) {
                // Best-effort, like every other recovery path here: a failed restore must not stop
                // the app launching. The previous database is untouched, so the runner lands where
                // they were and can try again — and because the settings had not been written yet,
                // what they land in is entirely the phone they had before they picked.
                Log.w(TAG, "Could not apply the pending restore", e)
                RestoreReader.clear(context)
                return false
            }
        } else {
            // Armed, but the snapshot has gone: the move landed and only what follows it was cut
            // short. The database in place is already the restored one, so carry on to the settings
            // rather than treating this as nothing to do.
            Log.w(TAG, "Restore was armed with nothing staged; the database swap had already landed")
        }
        val settings = RestoreReader.stagedSettings(context)
        if (settings != null) {
            try {
                applySettings(settings)
            } catch (e: Exception) {
                // Left armed on purpose. The history is in place and its settings are not, which is
                // the one state this restore must not be abandoned in; the next launch finds the
                // marker, finds no snapshot, and finishes the job.
                Log.w(TAG, "Restored the history but not its settings; will try again next launch", e)
                return replaced
            }
        }
        RestoreReader.clear(context)
        return replaced
    }

    /**
     * Puts [staged] where Room will look for it, sidecars and all.
     *
     * The previous database's `-wal` and `-shm` are moved out of the way **before** the file they
     * describe is replaced, and only thrown away once the replacement has landed. Both halves of
     * that matter, for opposite reasons.
     *
     * Out of the way first, because the snapshot arrives already checkpointed: a log left beside it
     * describes writes to a file that no longer exists, and replaying it would corrupt the restore.
     * Clearing them *after* the rename would leave a window — however short — in which a death
     * leaves the new database sitting beside the old one's log, and the next launch, finding no
     * snapshot left to move, would take that mixture for a finished restore.
     *
     * Moved rather than deleted, because a restore that fails promises the runner their phone is
     * exactly as it was. The app is killed without closing Room, so recent runs can live only in
     * that log; deleting it and then failing to promote the replacement would quietly roll back the
     * history this had just said it would not touch. Set aside, it can be put back.
     *
     * So at every instant on disk this is one of: the old database with its log, the old database
     * with its log alongside under another name, or the new database with no log at all. Never the
     * new database beside the old log, and never the old database with its log destroyed.
     */
    private fun moveIntoPlace(context: Context, staged: File) {
        val destination = DatabaseBackupManager.databaseFile(context)
        destination.parentFile?.mkdirs()
        // Accumulated as they move rather than returned at the end, so that a failure partway
        // through — one log set aside and the next refusing to budge — puts back the one that did.
        val sidecars = mutableListOf<Pair<File, File>>()
        try {
            setPreviousSidecarsAside(context, destination, sidecars)
            if (!staged.renameTo(destination)) {
                // A rename can only fail here if staging and the database sit on different volumes,
                // which they do not today — but a copy through a sibling temp file keeps the same
                // promise if that ever changes: the destination is only ever the whole snapshot or
                // the previous database, never a half-written mixture of the two.
                val temp = File("${destination.path}.restore.tmp")
                staged.copyTo(temp, overwrite = true)
                if (!temp.renameTo(destination)) {
                    temp.delete()
                    throw IllegalStateException("Could not move the restored database into place")
                }
                staged.delete()
            }
        } catch (e: Exception) {
            // The rollback is itself two renames that can fail. If one does, the log it was carrying
            // is still in staging, and staging is what gets cleared away after a failed restore —
            // so say so loudly enough that the caller keeps it. Reported even though the restore has
            // already failed, because this is the more serious of the two failures: one costs a
            // retry, the other costs runs that exist nowhere else.
            val stranded = sidecars.filterNot { (saved, original) -> saved.renameTo(original) }
            if (stranded.isNotEmpty()) {
                throw StrandedPreviousLog(stranded.map { (saved, _) -> saved.name }, e)
            }
            throw e
        }
        sidecars.forEach { (saved, _) -> saved.delete() }
    }

    /**
     * A restore that failed and could not put the previous database's log back where it belongs.
     *
     * Carries the cause, so the reason the restore failed in the first place is not lost behind the
     * reason the rollback did.
     */
    private class StrandedPreviousLog(names: List<String>, cause: Throwable) :
        IllegalStateException("Could not put back: ${names.joinToString()}", cause)

    /**
     * Moves the live database's log files into staging, recording each move into [movedSoFar] as
     * where it went and where it came from. Records nothing when there were no logs, which is the
     * ordinary case on a phone whose database Room checkpointed cleanly.
     *
     * They go into staging rather than beside the database so that the one thing which clears up
     * after an abandoned restore clears these up too — a crash between the move and the promotion
     * leaves them named after nothing, and [RestoreReader.clear] is what eventually removes them.
     *
     * A file already sitting in staging is a move that a previous attempt made before being cut
     * short. It is still the right log for the database still in place, so it is adopted rather than
     * overwritten.
     *
     * A log that exists and cannot be moved throws, which abandons the restore with the previous
     * database and its log both whole. Carrying on would be the one outcome this whole arrangement
     * exists to prevent: the two renames are separate calls and can fail separately, so a sidecar
     * that refuses to move says nothing about whether the database is about to be replaced beside
     * it. Failing here costs the runner another tap; not failing costs them the restore.
     */
    private fun setPreviousSidecarsAside(
        context: Context,
        destination: File,
        movedSoFar: MutableList<Pair<File, File>>,
    ) {
        val directory = RestoreReader.stagingDirectory(context)
        listOf("-wal", "-shm").forEach { suffix ->
            val original = File("${destination.path}$suffix")
            val saved = File(directory, "previous$suffix")
            when {
                original.exists() && original.renameTo(saved) -> movedSoFar += saved to original
                original.exists() ->
                    throw IllegalStateException("Could not move the previous database's $suffix aside")
                saved.exists() -> movedSoFar += saved to original
                // No log at all — the ordinary case on a database Room checkpointed cleanly.
                else -> Unit
            }
        }
    }
}
