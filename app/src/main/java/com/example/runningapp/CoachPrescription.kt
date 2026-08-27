package com.example.runningapp

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The work the coach has standing, one slot per Run Type (#175).
 *
 * One global prescription worked while only one Workout was ever queued. With three Runs to choose
 * between it becomes a hazard: intervals reasoned about for a Long Run would land on a session built
 * from twenty-second strides. So which session a prescription is about is *storage*, not a check —
 * [get] is the only way to reach one, and it can only be asked by Run Type.
 *
 * Empty is a whole answer, not a missing one: [NONE] means the plan runs as written.
 */
data class CoachPrescriptions(private val byRunType: Map<RunType, CoachPrescription>) {

    /** What the coach wrote for [runType], or null when it has written nothing for that kind. */
    operator fun get(runType: RunType): CoachPrescription? = byRunType[runType]

    companion object {
        val NONE = CoachPrescriptions(emptyMap())
    }
}

/**
 * Today's work, as written by the AI coach (#113).
 *
 * Exactly [WorkoutTemplate]'s four prescribable fields and nothing else — the coach prescribes a
 * workout, it does not configure the app. It cannot reach the cue switches, Max HR, or
 * `coachingEnabled`, because none of them are here to reach.
 *
 * It carries [targetZone] because a coach that can lengthen your intervals but cannot say "today is
 * easier, drop to Z2" is crippled at its one job; the plan already varies target zone.
 *
 * Deliberately *not* a `UserSettings` field. The three globals this replaces
 * (`aiRunIntervalSeconds`/`aiWalkIntervalSeconds`/`aiRepeats`) were per-run prescriptions stored as
 * permanent settings, which is why testing mode had to special-case around them at read time. A
 * prescription with a date on it needs no such special case: it is superseded by the coach's next
 * one, dropped when the stage advances, erased when testing mode comes on, and — see
 * [isFreshAt] — expires on its own if neither happens.
 */
data class CoachPrescription(
    val targetZone: Int,
    val runDurationSeconds: Int,
    val walkDurationSeconds: Int,
    val totalRepeats: Int,
    val prescribedAtEpochMillis: Long
)

/**
 * How long a prescription stands before it stops being about you (#113).
 *
 * The coach writes after a run and the next run is days away, so a prescription has to survive the
 * gap between runs — expiring at midnight would mean it almost never applied. But a plan's own
 * numbers never change within a stage, so a prescription left standing would quietly become the
 * plan: two weeks off, and the numbers waiting for you were computed from a body that has since
 * detrained. Past this, the stage's own workout is the honest starting point.
 */
const val COACH_PRESCRIPTION_MAX_AGE_DAYS = 14

private const val MAX_AGE_MILLIS = COACH_PRESCRIPTION_MAX_AGE_DAYS * 24L * 60L * 60L * 1000L

/**
 * Whether this prescription still applies at [nowEpochMillis].
 *
 * A stamp in the future counts as fresh: it means the clock moved backwards (a timezone fix, a
 * manual set), and throwing away a real prescription over that would be the app inventing a reason
 * to ignore the coach.
 */
fun CoachPrescription.isFreshAt(nowEpochMillis: Long): Boolean =
    nowEpochMillis - prescribedAtEpochMillis <= MAX_AGE_MILLIS

/**
 * The two Prescriptions the coach has written, newest first (#156).
 *
 * A Prescription is reasoned about from the Runs the coach was shown, so when one of those Runs is
 * deleted the numbers standing on it are about a history the runner no longer has. The Prescription
 * before it is what stands then — it was reasoned from the Runs *before* the deleted one, which are
 * still there — so the coach's previous work is kept alongside the standing work for exactly that.
 *
 * Two deep and no deeper. One step back is what a delete costs: the Runs a Prescription stood on are
 * the last three of the Stage, so unwinding further would reach Prescriptions reasoned from Runs the
 * runner has since replaced anyway, and the Stage's own Workout is the honest floor once there is
 * nothing left standing.
 *
 * [keyPrefix] is what makes this storage rather than a rule: the standing generation keeps the key
 * names it has always had, so no install is migrated to gain a provenance it never recorded.
 */
internal enum class CoachWorkGeneration(val keyPrefix: String) {
    STANDING("coach_"),
    PREVIOUS("coach_previous_"),
}

/**
 * The Runs the coach was shown when it wrote one generation's work (#156).
 *
 * Stored as strings because that is the only set DataStore preferences hold. **Absent is not
 * empty**: an empty set is a Prescription written with no Run to reason from, which no delete can
 * take away, while an absent key is a Prescription from before provenance was recorded at all — see
 * [standsOnDeletedRuns].
 */
private fun coachSourceRunsKey(generation: CoachWorkGeneration) =
    stringSetPreferencesKey("${generation.keyPrefix}source_runs")

/**
 * The debrief belonging to one generation, which travels with the numbers it explains (#113, #156).
 *
 * The standing one is [PreferencesKeys.LATEST_COACH_MESSAGE], which is where it has always been and
 * what the card reads.
 */
private fun coachDebriefKey(generation: CoachWorkGeneration) = when (generation) {
    CoachWorkGeneration.STANDING -> PreferencesKeys.LATEST_COACH_MESSAGE
    CoachWorkGeneration.PREVIOUS -> PreferencesKeys.PREVIOUS_COACH_MESSAGE
}

/**
 * Who wrote the debrief that is standing (#296).
 *
 * One slot holds the text the runner reads after a Run, and two different writers put things in it:
 * the coach, whose words came back from Gemini, and the app itself, which writes a graduation, a
 * Plan finished, or a Test missed in its own words because a requirement written in numbers is not
 * the coach's to judge ([ADR 0016](docs/adr/0016-a-requirement-stated-in-numbers-is-not-the-coachs-to-judge.md)).
 * Without a name on it the screen has to guess, and it guessed "AI Coach Debrief" over every one of
 * them — handing back, in the one place the runner actually looks, the attribution the design took
 * away. A runner with AI sharing switched off was being congratulated by a coach they never turned
 * on.
 *
 * Stamped where the message is written, never worked out afterwards from what the text looks like.
 */
enum class DebriefAuthor(internal val stored: String?) {
    /** Gemini's words, sent the Runs the runner consented to share. */
    COACH("coach"),

    /** The app's own words, written offline and with no Gemini key. */
    APP("app"),

    /**
     * Nobody this app can name — a debrief that was standing before the stamp existed (#296).
     *
     * Has no stored spelling, and never gets one: it is only ever what an absent stamp reads as, so
     * it cannot be written by anyone and cannot come back out of storage. The screen says "Run
     * Debrief" over it, which is true of both writers.
     */
    UNKNOWN(null);

    internal companion object {
        /**
         * Absent is [UNKNOWN], because an unstamped debrief may have been written by either of
         * them. The app's own writes — a Stage granted (#290), a Test missed (#292), a Plan
         * finished (#294) — all shipped *before* this stamp did, so an install upgrading into #296
         * can be standing on the app's own words with nothing beside them. Reading that absence as
         * [COACH] would put "AI Coach Debrief" over exactly the messages this change exists to stop
         * misattributing, to runners who may never have turned a coach on. Which of the two wrote
         * it cannot be recovered afterwards: the Prescription's provenance does not discriminate —
         * the missed-Test path writes no Prescription at all and leaves the coach's `source_runs`
         * where they were — and guessing from what the text looks like is the thing this stamp
         * replaced. So the screen names no writer rather than name the wrong one.
         *
         * Unreadable is [COACH] all the same, for the reason a corrupt provenance id does not take
         * coaching away: a stamp *is* present, and a value this app never wrote must not be what
         * decides the heading.
         */
        fun of(stored: String?): DebriefAuthor =
            if (stored == null) UNKNOWN
            else entries.firstOrNull { it.stored == stored } ?: COACH
    }
}

/**
 * The name on one generation's debrief, which travels with it (#296).
 *
 * Prefixed like every other generation-scoped key, so promoting the previous generation brings the
 * name back along with the text it belongs to — see [copyCoachWork].
 */
private fun coachDebriefAuthorKey(generation: CoachWorkGeneration) =
    stringPreferencesKey("${generation.keyPrefix}debrief_author")

/**
 * The standing debrief and the name of whoever wrote it, put down together (#296).
 *
 * Together because a stamp that can be written without its text — or left behind when the text is
 * replaced — is a heading over somebody else's words, which is the whole of this bug. Every writer
 * of the standing slot goes through here, the coach's included ([writeCoachWork]), so the stamp is
 * always overwritten rather than inherited from whatever stood before.
 *
 * [DebriefAuthor.UNKNOWN] cannot be written: it is what an absent stamp reads as, and storing it
 * would make the app the author of an anonymity it could have named.
 */
internal fun MutablePreferences.writeStandingDebrief(message: String, author: DebriefAuthor) {
    val name = requireNotNull(author.stored) {
        "A debrief is written by the coach or by the app; $author is only what an absent stamp reads as."
    }
    this[coachDebriefKey(CoachWorkGeneration.STANDING)] = message
    this[coachDebriefAuthorKey(CoachWorkGeneration.STANDING)] = name
}

/** The standing debrief gone, its name with it — a stamp cannot outlive the text it names. */
internal fun MutablePreferences.removeStandingDebrief() {
    remove(coachDebriefKey(CoachWorkGeneration.STANDING))
    remove(coachDebriefAuthorKey(CoachWorkGeneration.STANDING))
}

/** Who wrote the standing debrief, as the card asks it (#296). */
internal fun debriefAuthorOf(preferences: Preferences): DebriefAuthor =
    DebriefAuthor.of(preferences[coachDebriefAuthorKey(CoachWorkGeneration.STANDING)])

/**
 * One Run Type's five keys in one generation, spelled from the type itself so a slot cannot be added
 * without its storage (#175, #156).
 *
 * `internal` rather than private so a test can assert on the stored keys without a second copy of
 * the key strings — same reason [PreferencesKeys] is. One spelling of a key, one meaning.
 */
internal class CoachPrescriptionKeys private constructor(
    runType: RunType,
    generation: CoachWorkGeneration
) {
    private val suffix = runType.name.lowercase()
    private val prefix = generation.keyPrefix
    val targetZone = intPreferencesKey("${prefix}target_zone_$suffix")
    val runSeconds = intPreferencesKey("${prefix}run_seconds_$suffix")
    val walkSeconds = intPreferencesKey("${prefix}walk_seconds_$suffix")
    val repeats = intPreferencesKey("${prefix}repeats_$suffix")
    val prescribedAt = longPreferencesKey("${prefix}prescribed_at_$suffix")

    /** All five together, for the callers that treat a slot as one thing rather than five. */
    val all: List<Preferences.Key<*>> =
        listOf(targetZone, runSeconds, walkSeconds, repeats, prescribedAt)

    companion object {
        private val slots = CoachWorkGeneration.entries.associateWith { generation ->
            RunType.entries.associateWith { CoachPrescriptionKeys(it, generation) }
        }

        fun of(
            runType: RunType,
            generation: CoachWorkGeneration = CoachWorkGeneration.STANDING
        ): CoachPrescriptionKeys = slots.getValue(generation).getValue(runType)
    }
}

/**
 * The unsuffixed keys the single global prescription used before the split (#175).
 *
 * Read by nothing: a global prescription cannot say which kind of session it was about, and guessing
 * is the mistake the slots exist to make impossible. Named here so [dropLegacyCoachWork] can take
 * them away on upgrade instead of leaving them in storage for good.
 *
 * `internal` for the same reason the current keys are: one spelling of a key, so a test can assert
 * on these strings without a second copy of them.
 */
internal val LEGACY_GLOBAL_KEYS: List<Preferences.Key<*>> = listOf(
    intPreferencesKey("coach_target_zone"),
    intPreferencesKey("coach_run_seconds"),
    intPreferencesKey("coach_walk_seconds"),
    intPreferencesKey("coach_repeats"),
    longPreferencesKey("coach_prescribed_at")
)

/**
 * Takes the abandoned global prescription away on the launch that upgrades, debrief included (#175).
 *
 * Dropping the numbers is not enough on its own. The debrief that explains them is stored separately
 * and rendered on its own, so a legacy prescription that reads as nothing would leave the runner a
 * card describing intervals that no run will do — until the next stage or coach reply happened to
 * clear it. `clearCoachWork` exists precisely because the text and the numbers are one thing to
 * invalidate, so the upgrade uses it.
 *
 * A DataStore migration rather than a clear folded into some later write, because a migration lands
 * before the first read: there is no launch on which the orphaned text can be shown. It runs only
 * when a legacy key is actually present, so an install that never had one is never rewritten.
 */
internal val dropLegacyCoachWork: DataMigration<Preferences> = object : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        LEGACY_GLOBAL_KEYS.any { currentData.contains(it) }

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply { clearCoachWork() }

    override suspend fun cleanUp() = Unit
}

/**
 * Everything standing, read out of one snapshot of the preferences.
 *
 * A slot missing any of its five keys stands for nothing: all four numbers were reasoned about
 * together, so half a prescription is not a lighter one. Freshness is *not* applied here — whoever
 * runs a workout decides against its own clock, so the expiry cannot drift between the card and the
 * run.
 */
internal fun Preferences.coachPrescriptions(
    generation: CoachWorkGeneration = CoachWorkGeneration.STANDING
): CoachPrescriptions = CoachPrescriptions(
    RunType.entries.mapNotNull { runType ->
        coachPrescription(runType, generation)?.let { runType to it }
    }.toMap()
)

private fun Preferences.coachPrescription(
    runType: RunType,
    generation: CoachWorkGeneration
): CoachPrescription? {
    val keys = CoachPrescriptionKeys.of(runType, generation)
    return CoachPrescription(
        targetZone = this[keys.targetZone] ?: return null,
        runDurationSeconds = this[keys.runSeconds] ?: return null,
        walkDurationSeconds = this[keys.walkSeconds] ?: return null,
        totalRepeats = this[keys.repeats] ?: return null,
        prescribedAtEpochMillis = this[keys.prescribedAt] ?: return null
    )
}

/**
 * Stores [prescription] in [runType]'s slot of [generation], leaving the other two exactly as they
 * were.
 *
 * The numbers alone, so this is the write for *changing what is already standing* rather than for
 * the coach saying something new — the hold pares a standing Prescription back without making it a
 * new one (#248), and the provenance and debrief it keeps are the ones it already had. A new
 * Prescription from the coach goes through [writeCoachWork].
 */
internal fun MutablePreferences.writeCoachPrescription(
    runType: RunType,
    prescription: CoachPrescription
) {
    val keys = CoachPrescriptionKeys.of(runType)
    this[keys.targetZone] = prescription.targetZone
    this[keys.runSeconds] = prescription.runDurationSeconds
    this[keys.walkSeconds] = prescription.walkDurationSeconds
    this[keys.repeats] = prescription.totalRepeats
    this[keys.prescribedAt] = prescription.prescribedAtEpochMillis
}

/**
 * A new Prescription from the coach: [prescription] for [runType], the [debrief] that explains it, and the
 * Runs it was reasoned from — with whatever stood before it kept as the previous generation (#156).
 *
 * One write for all three, which is what makes the rollback possible at all: a debrief stored
 * separately from the numbers it describes could be shifted back on its own, leaving the runner a
 * card whose text and intervals came from different evaluations. `editCoachWrite` already refuses
 * the coach's work whole rather than in part, and this is that promise kept in storage.
 *
 * [sourceRunIds] is written even when it is empty, because empty and unrecorded are different
 * answers — see [coachSourceRunsKey].
 */
internal fun MutablePreferences.writeCoachWork(
    runType: RunType,
    prescription: CoachPrescription,
    debrief: String,
    sourceRunIds: Set<Long>
) {
    copyCoachWork(from = CoachWorkGeneration.STANDING, to = CoachWorkGeneration.PREVIOUS)
    writeCoachPrescription(runType, prescription)
    // Stamped [DebriefAuthor.COACH] rather than left alone, because what stood before may have been
    // the app's (#296): a graduation or a missed Test writes into this same slot, and a stamp that
    // is only ever written by one of the two writers is a stale name over the other's words.
    writeStandingDebrief(debrief, DebriefAuthor.COACH)
    this[coachSourceRunsKey(CoachWorkGeneration.STANDING)] =
        sourceRunIds.map { it.toString() }.toSet()
}

/**
 * Takes back the coach's work that stood on Runs that are no longer in history, standing its
 * previous work up in place of it (#156). True when anything moved.
 *
 * Deleting a Run leaves its row gone and the coaching reasoned from it on the card — intervals dialled
 * back because of a Run the runner threw away, under a debrief explaining a Run that no longer
 * exists. The previous Prescription is what replaces it, rather than the Stage's own Workout: the
 * Runs still in history earned that progression, and falling back to day one of the Stage would
 * throw it away.
 *
 * A promoted Prescription keeps its own date, so one written a fortnight ago reads as nothing
 * standing ([isFreshAt]) and the Stage's Workout runs — which is right, and is why the promotion
 * does not restamp it.
 *
 * The previous work is dropped whenever this runs, whether it was promoted or not. Promoted, there
 * is nothing behind it to fall back to; poisoned by the same delete, it is not something to leave
 * standing behind the next one.
 *
 * **A generation that recorded no provenance is assumed to stand on the deleted Runs.** Every
 * Prescription written before #156 is such a one, which is the very state the runner reporting this
 * was in: guessing the other way would be the app claiming the coaching survived a delete it cannot
 * know anything about. It costs a Prescription that would have stood, once, on the first delete
 * after the upgrade.
 */
internal fun MutablePreferences.rollBackCoachWorkFedBy(deletedRunIds: Set<Long>): Boolean {
    if (deletedRunIds.isEmpty()) return false
    val standing = CoachWorkGeneration.STANDING
    val previous = CoachWorkGeneration.PREVIOUS
    // No Prescription standing is nothing to take back, and an empty store must not be written to on
    // every delete — a runner with no plan attached has never been prescribed anything.
    //
    // A debrief with no numbers under it is not coaching about a Run either: the one thing that
    // leaves that state is a graduation, whose "you have finished this stage" is deliberately kept
    // when the Prescriptions it advanced past are dropped
    // ([SettingsRepository.graduateStage]). Read as work standing, it would be
    // taken away by the next delete of any Run at all, because a graduation leaves no provenance
    // behind for it to be measured against.
    val anythingStanding = coachPrescriptions(standing) != CoachPrescriptions.NONE
    val previousStands = coachPrescriptions(previous) != CoachPrescriptions.NONE

    if (!anythingStanding || !standsOnDeletedRuns(standing, deletedRunIds)) {
        // The standing work survives, but the work behind it may not: dropped now rather than left
        // to be promoted by some later delete into coaching about Runs that are already gone.
        if (previousStands && standsOnDeletedRuns(previous, deletedRunIds)) {
            removeCoachWork(previous)
            return true
        }
        return false
    }

    if (previousStands && !standsOnDeletedRuns(previous, deletedRunIds)) {
        copyCoachWork(from = previous, to = standing)
    } else {
        removeCoachWork(standing)
    }
    removeCoachWork(previous)
    return true
}

/**
 * Whether [generation]'s work was reasoned from any of [deletedRunIds] — true when it recorded no
 * provenance at all, for the reason [rollBackCoachWorkFedBy] gives.
 *
 * A stored id that is not a number is treated as no id rather than as a match: it cannot name a Run
 * being deleted, and taking coaching away over an unreadable one would be a corrupt key deciding it.
 */
/**
 * Every Run the coach's work says it was reasoned from, across both generations (#270).
 *
 * The question a launch pass asks before it can ask history anything: which Runs does the standing
 * coaching claim to stand on? Both generations, because the previous one is a Prescription waiting
 * to be promoted, and coaching promoted onto a deleted Run is the same fault a turn later.
 *
 * **Absent provenance contributes nothing.** A Prescription written before #156 recorded no Runs, so
 * there is nothing here to check it against — which is not the reading [standsOnDeletedRuns] gives,
 * and deliberately so: that one is answering a delete that is happening, where guessing safe means
 * taking the coaching away, while this one is answering "is there anything to look into at all", and
 * an unanswerable question must not become a reason to look. ADR 0013 covers the legacy case.
 *
 * A stored id that is not a number is skipped, for the reason [standsOnDeletedRuns] gives.
 */
internal fun Preferences.coachWorkProvenance(): Set<Long> =
    CoachWorkGeneration.entries
        .mapNotNull { this[coachSourceRunsKey(it)] }
        .flatten()
        .mapNotNull { it.toLongOrNull() }
        .toSet()

private fun Preferences.standsOnDeletedRuns(
    generation: CoachWorkGeneration,
    deletedRunIds: Set<Long>
): Boolean {
    val sources = this[coachSourceRunsKey(generation)] ?: return true
    return sources.mapNotNull { it.toLongOrNull() }.any { it in deletedRunIds }
}

/**
 * Copies one generation's whole work over another's — every slot, its provenance, and its debrief.
 *
 * A key absent in [from] is *removed* from [to] rather than left as it was, so the destination is
 * the source afterwards and never a blend of the two.
 */
private fun MutablePreferences.copyCoachWork(from: CoachWorkGeneration, to: CoachWorkGeneration) {
    RunType.entries.forEach { runType ->
        // A slot as one thing rather than five, which is what [CoachPrescriptionKeys.all] is for: the
        // two lists are built by one constructor from one list of fields, so they line up by
        // construction and a sixth field is copied without this being touched.
        CoachPrescriptionKeys.of(runType, from).all
            .zip(CoachPrescriptionKeys.of(runType, to).all)
            .forEach { (source, destination) -> copyKey(source, destination) }
    }
    copyKey(coachSourceRunsKey(from), coachSourceRunsKey(to))
    copyKey(coachDebriefKey(from), coachDebriefKey(to))
    // The name travels with the text (#296). Promoted without it, the app's own graduation comes
    // back up under the coach's heading — and an install that never stamped one keeps the absence,
    // which stays unnamed rather than being promoted into a writer nobody recorded.
    copyKey(coachDebriefAuthorKey(from), coachDebriefAuthorKey(to))
}

/**
 * One key's value put under another, or taken away where there was none.
 *
 * Untyped because it is handed pairs out of [CoachPrescriptionKeys.all], which is a list of keys of
 * mixed type; the cast is safe for the one reason the zip above is — both keys come from the same
 * field of the same class, so they are the same type in every pair this can be given.
 */
@Suppress("UNCHECKED_CAST")
private fun MutablePreferences.copyKey(from: Preferences.Key<*>, to: Preferences.Key<*>) {
    val value = this[from]
    if (value == null) remove(to) else this[to as Preferences.Key<Any>] = value
}

/** One generation's work gone entirely: all three slots, its provenance, and its debrief. */
private fun MutablePreferences.removeCoachWork(generation: CoachWorkGeneration) {
    RunType.entries.flatMap { CoachPrescriptionKeys.of(it, generation).all }
        .plus(coachSourceRunsKey(generation))
        .plus(coachDebriefKey(generation))
        .plus(coachDebriefAuthorKey(generation))
        .forEach { remove(it) }
}

/**
 * Drops every standing prescription, in whatever edit the caller is already making.
 *
 * An extension on the preferences rather than a repository call so the settings changes that
 * invalidate a prescription — testing mode coming on, the stage advancing — can drop it in the same
 * atomic write. Two writes could interleave with a run starting between them, which is the one
 * moment the guarantee matters. All three slots go here for the same reason: three writes would
 * leave a window where a run could start on a stage it had half-left.
 *
 * The previous generation goes whole, debrief included (#156): once the standing numbers are wrong
 * for the stage the runner is in, the ones behind them are older work against the same stage and
 * cannot be rolled back to either. The *standing* debrief is deliberately left — graduating keeps
 * the coach's "you have finished this stage", and [clearCoachWork] is where the two go together.
 */
internal fun MutablePreferences.clearCoachPrescriptions() {
    removeCoachWork(CoachWorkGeneration.PREVIOUS)
    RunType.entries.flatMap { CoachPrescriptionKeys.of(it).all }
        .plus(coachSourceRunsKey(CoachWorkGeneration.STANDING))
        .plus(LEGACY_GLOBAL_KEYS)
        .forEach { remove(it) }
}

class CoachPrescriptionRepository(private val context: Context) {

    /** Every Run Type's standing prescription; [CoachPrescriptions.NONE] when the coach is silent. */
    val prescriptionsFlow: Flow<CoachPrescriptions> =
        context.dataStore.data.map { it.coachPrescriptions() }

    /**
     * Records what the coach wants run next for [runType] and the [debrief] that explains it,
     * replacing anything it wrote for that kind before and touching no other kind.
     *
     * [sourceRunIds] is the Runs the coach was shown to arrive at this, kept so that deleting one of
     * them can take the Prescription back rather than leave it standing on a history the runner no
     * longer has (#156).
     *
     * [scope] is the plan and stage the prescription was reasoned about against; the write is
     * refused if they are no longer the active ones. See `editCoachWrite`.
     */
    suspend fun prescribe(
        runType: RunType,
        prescription: CoachPrescription,
        debrief: String,
        sourceRunIds: Set<Long>,
        scope: CoachWriteScope
    ) {
        context.dataStore.editCoachWrite(scope) { preferences ->
            preferences.writeCoachWork(runType, prescription, debrief, sourceRunIds)
        }
    }

    /**
     * Changes the numbers of what is already standing for [runType], without it becoming a new
     * prescription (#248).
     *
     * The hold pares a standing Prescription back to the Stage's Workout when the coach could not be
     * reached, which is the same Prescription said again more quietly — so its debrief, its date and
     * the Runs it stood on are all the ones it already had. Written as a new Prescription it would
     * shift the coach's real last one into the previous generation and hand the runner a rollback
     * target that was never a separate evaluation.
     */
    suspend fun amendStanding(
        runType: RunType,
        prescription: CoachPrescription,
        scope: CoachWriteScope
    ) {
        context.dataStore.editCoachWrite(scope) { preferences ->
            preferences.writeCoachPrescription(runType, prescription)
        }
    }

    /**
     * Takes back the coach's work that stood on the Runs in [deletedRunIds] (#156).
     *
     * Called with the Runs a delete has just taken out of history, and deliberately **not** through
     * `editCoachWrite`: that gate is about the coach writing something new, which can be refused
     * because the ground moved. This is the opposite — work being taken away because its evidence
     * went — and there is no state of the app in which coaching about a deleted Run should be left
     * standing. Testing mode has erased it already; a plan change has too.
     */
    /**
     * The Runs the coach's work names as its evidence — see [coachWorkProvenance] (#270).
     *
     * Read as one snapshot rather than watched, because the only caller is a launch pass that
     * asks once and acts on the answer under the same lock a delete takes.
     */
    suspend fun runsTheWorkStandsOn(): Set<Long> =
        context.dataStore.data.first().coachWorkProvenance()

    suspend fun forgetWorkFedBy(deletedRunIds: Set<Long>) {
        if (deletedRunIds.isEmpty()) return
        var rolledBack = false
        context.dataStore.edit { preferences ->
            rolledBack = preferences.rollBackCoachWorkFedBy(deletedRunIds)
        }
        if (rolledBack) {
            Log.d(
                "AiCoach",
                "Took back coaching that stood on deleted runs: $deletedRunIds"
            )
        }
    }
}
