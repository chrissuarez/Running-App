package com.example.runningapp.ui

import com.example.runningapp.archive.ArchiveOutcome
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the Backup section of Settings says (#85).
 *
 * Pure and outside the composable for the reason the rest of this screen's rules are: the three
 * lines here are the whole of what the runner knows about whether their history is safe, and a line
 * that quietly says the wrong thing is the worst bug this feature can have. None of them are
 * testable while they live inside a `@Composable`.
 */

private val LAST_BACKUP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)

/**
 * The folder, in the runner's words rather than the system's.
 *
 * A tree Uri carries a document id that is a readable path on the phone's own storage
 * (`primary:Backups/Running`) and an opaque token on a cloud provider (`acc=1;doc=…`). The path is
 * worth showing — it is how they will find the folder on a laptop — and the token is not, so this
 * says which folder it is only when it can say something true.
 */
fun backupFolderLabel(treeUri: String?): String {
    if (treeUri.isNullOrBlank()) return "Not set"
    val documentId = try {
        URLDecoder.decode(treeUri.substringAfterLast("/tree/", ""), "UTF-8")
    } catch (e: Exception) {
        ""
    }
    val volume = documentId.substringBefore(':', missingDelimiterValue = "")
    val path = documentId.substringAfter(':', missingDelimiterValue = "")
    return when {
        path.isBlank() -> if (volume == "primary") "Phone storage" else "Chosen folder"
        // Not a path at all — a provider's own identifier, which would read as gibberish.
        path.any { it == '=' || it == ';' } -> "Chosen folder"
        else -> path
    }
}

/**
 * When the last complete archive landed.
 *
 * "No backup yet" rather than a blank or a dash: the absence is the point of the line. A runner
 * who has picked a folder and never backed up should be told so in as many words.
 */
fun lastBackupLine(
    lastBackupAtEpochMillis: Long?,
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    if (lastBackupAtEpochMillis == null) return "No backup yet"
    val at = LAST_BACKUP_FORMAT.format(Instant.ofEpochMilli(lastBackupAtEpochMillis).atZone(zoneId))
    return "Last backup $at"
}

/**
 * What to tell the runner about the backup that just finished, or null while there is nothing to
 * say.
 *
 * A failure names what went wrong rather than saying "backup failed": the reasons are things the
 * runner can act on — a folder they moved, a disk that is full — and a message that hides them
 * leaves tapping the button again as the only idea available.
 */
fun backupResultMessage(outcome: ArchiveOutcome?): String? = when (outcome) {
    null -> null
    is ArchiveOutcome.Archived -> "Backed up"
    ArchiveOutcome.NoFolderChosen -> "Choose a backup folder first"
    is ArchiveOutcome.Failed -> "Backup failed: ${outcome.reason}"
}
