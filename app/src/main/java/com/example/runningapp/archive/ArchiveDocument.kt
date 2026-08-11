package com.example.runningapp.archive

import com.example.runningapp.HrProfile
import com.example.runningapp.UserSettings
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.RunWalkIntervalStat

/**
 * Everything an archive carries that a GPX file cannot (#85).
 *
 * A GPX file holds where the runner went and what their heart was doing while they went there. It
 * has nowhere to put how the run felt, what the weather was, which Stage of the Plan it was run
 * under, or the intervals it followed — so those travel here, in one JSON document beside the GPX
 * files and the database snapshot.
 *
 * The three halves of an archive answer different questions, deliberately:
 *  - the GPX files are the *portable* half — they open in Strava, Garmin Connect, a laptop;
 *  - this document is the *readable* half — plain JSON anyone can look at without this app;
 *  - the database snapshot is the *restorable* half — the only one that puts the app back exactly
 *    as it was, and the one [#86] restores from.
 *
 * So nothing here needs to be sufficient on its own. Per-second heart-rate samples and GPS track
 * points are deliberately absent: they are the bulk of the database, they are already in the GPX
 * files for every run that has a track, and the snapshot has them whole for every run that hasn't.
 */
data class ArchiveDocument(
    val formatVersion: Int = ARCHIVE_FORMAT_VERSION,
    val createdAtEpochMillis: Long,
    /**
     * The Room schema version the rows below came from. Not the same claim as [formatVersion]:
     * this says what shape the app's database was in, which is what tells a reader whether a
     * column it is missing was dropped or never existed.
     */
    val databaseVersion: Int,
    val settings: ArchivedSettings,
    val runs: List<RunnerSession>,
    val intervalStats: List<RunWalkIntervalStat>
)

/**
 * The current shape of [ArchiveDocument] itself — bumped when what the document *means* changes,
 * not when the app's database gains a column.
 *
 * A reader refuses a version it does not know ([ArchiveJson.read]), because guessing at a document
 * written by a later app is how a restore quietly puts back the wrong thing.
 */
const val ARCHIVE_FORMAT_VERSION = 1

/**
 * The settings worth keeping across a lost phone: who the runner is, and where they are in their
 * training.
 *
 * Three groups are left out on purpose.
 *  - **The strap** (saved devices, the active address). A Bluetooth address belongs to a pairing on
 *    one phone; restored onto another it names hardware that is not there.
 *  - **Simulation and testing mode.** Developer state, and testing mode in particular suppresses the
 *    AI coach — restoring it silently would leave the coach mute with nothing on screen to say why.
 *  - **The coach's latest debrief and its Prescriptions.** A debrief explains one Prescription and a
 *    Prescription expires; both describe the run just finished rather than the runner, and the coach
 *    writes fresh ones after the next Run.
 */
data class ArchivedSettings(
    val maxHr: Int,
    val maxHrEverSet: Boolean,
    /**
     * Whether the one-time Max HR card has been put away (#65).
     *
     * Carried because on a runner who closed it without answering this flag is the *only* record
     * that they were ever asked — [maxHrEverSet] is false for them by definition. Left out, a
     * restore would ask a second time a question the app promised to ask once.
     *
     * An archive written before this field existed reads back false, which is the truth about it:
     * the card did not exist to be put away.
     */
    val maxHrCardDismissed: Boolean,
    val historyMaxHr: Int,
    val restingHr: Int,
    val targetZone: Int,
    val coachingEnabled: Boolean,
    val splitAnnouncementsEnabled: Boolean,
    val autoPauseEnabled: Boolean,
    val aiDataSharingEnabled: Boolean,
    val runMode: String,
    val activePlanId: String?,
    val activeStageId: String?
)

/**
 * The Reserve the runs in this archive are banded against.
 *
 * `historyMaxHr`, never `maxHr`: an archive carries both because they part company the moment a
 * runner corrects their maximum, and every run inside it was read under the history one (#112,
 * #172). Anything re-banding those runs — the v12 → v13 recompute, at the launch that restores them
 * or at the trial open that proves they can be (#201) — has to ask for this pair, or it lands a
 * restored history on a number no phone ever produced.
 *
 * One name rather than the pair spelled out at each site, because the two callers are a launch
 * and a restore and they must not drift: what the trial migrates on is what the runner keeps.
 */
val ArchivedSettings.historyHrProfile: HrProfile get() = HrProfile(historyMaxHr, restingHr)

fun UserSettings.toArchived(): ArchivedSettings = ArchivedSettings(
    maxHr = maxHr,
    maxHrEverSet = maxHrEverSet,
    maxHrCardDismissed = maxHrCardDismissed,
    historyMaxHr = historyMaxHr,
    restingHr = restingHr,
    targetZone = targetZone,
    coachingEnabled = coachingEnabled,
    splitAnnouncementsEnabled = splitAnnouncementsEnabled,
    autoPauseEnabled = autoPauseEnabled,
    aiDataSharingEnabled = aiDataSharingEnabled,
    runMode = runMode,
    activePlanId = activePlanId,
    activeStageId = activeStageId
)
