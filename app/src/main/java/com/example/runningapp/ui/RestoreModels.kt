package com.example.runningapp.ui

import com.example.runningapp.restore.RestoreFileKind
import com.example.runningapp.restore.RestorePlan
import com.example.runningapp.restore.RestoreRefusal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the Restore section of Settings says (#86, #198).
 *
 * Pure and outside the composable, like [backupFolderLabel] and for a sharper version of the same
 * reason. A restore cannot be undone, so the confirmation text *is* the safety mechanism — there is
 * no "never overwrite" rule standing behind it, deliberately: that rule belongs to the automatic
 * restore, which only runs on a phone with no database at all, and reusing it here would refuse
 * every manual restore including the Clear-storage recovery this exists for. What protects a runner
 * is having been told the truth in numbers they can compare, which makes these strings load-bearing
 * and worth testing.
 */

private val RUN_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

/** The Settings row's supporting line, which says what the button is *for* before it is needed. */
fun restoreRowSubtitle(runInProgress: Boolean): String =
    if (runInProgress) {
        // Not a scolding and not a mystery: the reason is that restarting would end the run.
        "Finish your run first."
    } else {
        "Bring history back from a backup file or archive."
    }

/**
 * The confirmation, as the runner needs to read it: what is coming in, what is going out, and that
 * there is no way back.
 *
 * Both dates appear whenever both exist, which is what makes a stale file answer for itself. The
 * app does not refuse a backup older than the history already on the phone — someone who has just
 * lost everything and picks a month-old archive is doing exactly the right thing, and being
 * overruled by their own app at that moment would be its own kind of failure. It only has to make
 * sure the two dates are on screen together.
 */
fun restoreConfirmationBody(plan: RestorePlan, zoneId: ZoneId = ZoneId.systemDefault()): String {
    val incoming = buildString {
        append("This ")
        append(if (plan.summary.kind == RestoreFileKind.ARCHIVE) "archive" else "backup")
        append(" holds ")
        append(runCountPhrase(plan.summary.runCount))
        plan.summary.newestRunStartedAtEpochMillis?.let {
            append(", the most recent on ")
            append(RUN_DATE_FORMAT.format(Instant.ofEpochMilli(it).atZone(zoneId)))
        }
        append(".")
    }
    val outgoing = if (plan.replacesExistingHistory) {
        buildString {
            append("You have ")
            append(runCountPhrase(plan.current.runCount))
            plan.current.newestRunStartedAtEpochMillis?.let {
                append(", the most recent on ")
                append(RUN_DATE_FORMAT.format(Instant.ofEpochMilli(it).atZone(zoneId)))
            }
            append(if (plan.goingBackInTime) ". Restoring replaces it with older history." else ". Restoring replaces it.")
        }
    } else {
        // Nothing to lose — the state a wiped phone is in, and the case this feature exists for.
        // Saying so removes the fright from a screen that otherwise reads like a warning.
        "You have no run history on this phone, so nothing will be lost."
    }
    // Settings are only in an archive, never in a bare database, and after a Clear storage they are
    // gone too — so a runner restoring a .db needs telling that this will not bring them back. An
    // archive can fail to carry them too, when the settings file inside it cannot be read; the
    // history still restores, and the reason differs enough to be worth its own sentence.
    val settings = when {
        plan.summary.carriesSettings -> "Your settings and training plan will be restored too."
        plan.summary.kind == RestoreFileKind.DATABASE ->
            "Your settings and training plan stay as they are — a backup file does not carry them."
        else ->
            "Your settings and training plan stay as they are — this archive's settings couldn't " +
                "be read. Your runs will still come back."
    }
    return "$incoming $outgoing\n\n$settings\n\nThis cannot be undone. The app will close and reopen."
}

/**
 * Why a picked file was refused, in words that say what to do next.
 *
 * Each of these leaves the phone exactly as it was — worth stating, because a runner who has just
 * been told "no" in the middle of recovering a lost history needs to know they have not made things
 * worse.
 */
fun restoreRefusalMessage(reason: RestoreRefusal): String = when (reason) {
    RestoreRefusal.NOT_A_BACKUP ->
        "That file isn't a Running App backup. Look for a file ending in .db or .zip, in your " +
            "Downloads folder or your backup folder."
    RestoreRefusal.ARCHIVE_HAS_NO_DATABASE ->
        "That archive doesn't contain a copy of your history — only the exported run files. Try " +
            "another archive."
    RestoreRefusal.FROM_A_NEWER_APP ->
        "That backup was made by a newer version of the app than this one. Update the app, then " +
            "try again."
    RestoreRefusal.A_RESTORE_IS_UNFINISHED ->
        "Your history is back, but the last restore still has your settings and training plan to " +
            "put back. Close the app and open it again to finish, then you can restore another file."
    // Says only what is always true of this refusal. The usual cause is a backup Room will not
    // accept, but the trial can also stop for a reason that is nothing to do with the file's
    // contents, and a sentence naming what is "wrong inside it" would then be telling the runner
    // something false about a perfectly good backup.
    RestoreRefusal.CANNOT_BE_MIGRATED ->
        "That backup couldn't be brought forward to this version of the app. Try another backup — " +
            "your history on this phone is untouched."
    RestoreRefusal.UNREADABLE ->
        "That backup couldn't be read — it may be damaged or only partly copied. Nothing on your " +
            "phone has changed."
}

private fun runCountPhrase(count: Int): String = if (count == 1) "1 run" else "$count runs"
