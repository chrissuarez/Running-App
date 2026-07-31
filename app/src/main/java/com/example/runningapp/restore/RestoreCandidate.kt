package com.example.runningapp.restore

/**
 * What the runner picked, what it holds, and whether this app may honestly put it back (#86, #198).
 *
 * Everything in this file is plain logic over facts already read out of the file — no Android, no
 * storage — so the rules that decide whether a restore may proceed, and what the runner is told
 * before they agree to it, can be tested. The reading itself lives in [RestoreReader], which is all
 * SQLite and zip streams and cannot be.
 *
 * The shape of the decision is deliberately three-part: *what kind of file is this*, *what does it
 * contain*, and only then *may we*. Collapsing them would make "this isn't a backup" and "this is a
 * backup from a newer version of the app" the same answer, and they need very different words.
 */

/**
 * The two files this app writes that can put history back, told apart by their first bytes rather
 * than their name.
 *
 * By content, because the name proves nothing: the system file picker hands back whatever the runner
 * chose, a file copied through Drive or a chat app can arrive as `running_app_history_backup(2).db`
 * or lose its extension altogether, and someone recovering from a wiped phone should not have their
 * real backup refused over a rename. The first four bytes are a fact about the file; the name is a
 * guess about it.
 */
enum class RestoreFileKind {
    /** A full archive (#85): the database snapshot, a GPX per run, and `archive.json` of settings. */
    ARCHIVE,

    /** A bare database snapshot — the copy kept in Downloads after every run (#119). */
    DATABASE;

    companion object {
        /**
         * The sixteen bytes every SQLite database file opens with: `SQLite format 3` and a zero
         * byte. Written as an escape rather than the byte itself so this file stays text — a
         * literal NUL in the source makes grep treat the whole thing as binary.
         */
        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        /** `PK` — the local file header a non-empty zip opens with. */
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        /** How many bytes [detect] needs to see. */
        const val MAGIC_BYTES = 16

        /**
         * The kind [head] belongs to, or null if it is neither — a photo, a text file, an empty
         * file, or a truncated download.
         */
        fun detect(head: ByteArray): RestoreFileKind? = when {
            head.startsWith(SQLITE_MAGIC) -> DATABASE
            head.startsWith(ZIP_MAGIC) -> ARCHIVE
            else -> null
        }

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}

/**
 * What a readable backup file turned out to hold.
 *
 * [newestRunStartedAtEpochMillis] is null when the file holds no runs at all — a real and
 * restorable state (a backup taken from a phone whose history had been cleared), and the reason the
 * count and the date are two facts rather than one.
 */
data class RestoreSummary(
    val kind: RestoreFileKind,
    val runCount: Int,
    val newestRunStartedAtEpochMillis: Long?,
    /**
     * The Room schema version the file was written at — `PRAGMA user_version` of the database
     * inside it. What [RestoreEligibility] compares against the app doing the restoring.
     */
    val databaseVersion: Int,
) {
    /**
     * Whether restoring this file also puts back max heart rate, target zone and the training Plan
     * and Stage.
     *
     * A property of the *kind*, not of the particular file: settings never lived in the database, so
     * a bare `.db` has nowhere to have kept them and an archive always carries `archive.json`
     * beside its snapshot. The runner is told which they picked, because after a Clear storage their
     * settings are gone too and a `.db` restore will not bring them back — better said out loud than
     * discovered later.
     */
    val carriesSettings: Boolean get() = kind == RestoreFileKind.ARCHIVE
}

/** What the phone holds right now, for the runner to weigh the file against. */
data class CurrentHistory(
    val runCount: Int,
    val newestRunStartedAtEpochMillis: Long?,
)

/** Why a picked file cannot be restored. Each is a sentence the runner needs, not an error code. */
enum class RestoreRefusal {
    /** Not a backup this app wrote — neither a database nor an archive. */
    NOT_A_BACKUP,

    /** An archive, but without the `database/` snapshot inside it that a restore reads. */
    ARCHIVE_HAS_NO_DATABASE,

    /** A backup, but from a later version of the app than this one — see [RestoreEligibility]. */
    FROM_A_NEWER_APP,

    /** A database that opened but isn't this app's — no `sessions` table, or unreadable partway. */
    UNREADABLE,
}

/** Whether a summarised file may be restored, and if not, why not. */
sealed interface RestoreEligibility {
    data class Allowed(val summary: RestoreSummary) : RestoreEligibility
    data class Refused(val reason: RestoreRefusal) : RestoreEligibility

    companion object {
        /**
         * The one rule applied to a file that has already been read successfully: **never restore a
         * backup written by a newer app than this one.**
         *
         * Room migrates a database forward and has no path back. Handed a snapshot from a later
         * schema it either refuses to open — leaving the app dead on launch, after the swap, with
         * the previous history already gone — or opens it and reads columns that have since changed
         * meaning. This is the one moment the app can still say no cheaply, so it does.
         *
         * An *older* version is fine and is the ordinary case: that is exactly what a backup from
         * before the last app update is, and Room's migrations exist to carry it forward.
         */
        fun of(summary: RestoreSummary, currentDatabaseVersion: Int): RestoreEligibility =
            if (summary.databaseVersion > currentDatabaseVersion) {
                Refused(RestoreRefusal.FROM_A_NEWER_APP)
            } else {
                Allowed(summary)
            }
    }
}

/**
 * Everything the confirmation screen has to state before the runner agrees, gathered in one place so
 * the promise made and the work done cannot drift apart.
 *
 * The confirmation is the *whole* safety mechanism here. There is no undo, no "never overwrite"
 * guard — that guard belongs to the automatic restore, which only ever runs on a phone with no
 * database, and reusing it would block every manual restore, including the Clear-storage recovery
 * this exists for (an empty database is created the moment the app first launches after a wipe). So
 * what stands between a runner and losing good history is that they were told the truth first, in
 * numbers they can compare.
 *
 * [goingBackInTime] is stated rather than acted on. A runner who has just lost their history and
 * picks a file older than the stub the app rebuilt is doing exactly the right thing; the app has no
 * business overruling them. It only has to make sure the two dates are both on screen.
 */
data class RestorePlan(
    val summary: RestoreSummary,
    val current: CurrentHistory,
) {
    /** True when the picked file's newest run predates the newest run already on the phone. */
    val goingBackInTime: Boolean
        get() {
            val incoming = summary.newestRunStartedAtEpochMillis ?: return current.runCount > 0
            val existing = current.newestRunStartedAtEpochMillis ?: return false
            return incoming < existing
        }

    /** True when there is history here to lose — the case the warning is really for. */
    val replacesExistingHistory: Boolean get() = current.runCount > 0
}
