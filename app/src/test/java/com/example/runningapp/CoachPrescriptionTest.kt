package com.example.runningapp

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three independent slots, one per Run Type (#175): the coach's Long Run numbers can never land on a
 * stride session, because they are not stored anywhere the stride session reads.
 *
 * Storage is exercised through the pure preferences seam rather than a DataStore, for the reason
 * [coachWriteAllowed] is: the rule is then readable and testable where it is decided.
 */
class CoachPrescriptionTest {

    private val now = 1_700_000_000_000L

    private fun prescription(run: Int = 360, walk: Int = 90, repeats: Int = 5) =
        CoachPrescription(
            targetZone = 2,
            runDurationSeconds = run,
            walkDurationSeconds = walk,
            totalRepeats = repeats,
            prescribedAtEpochMillis = now
        )

    /**
     * Storage as it stood before the split, typed from the names the app itself holds so this cannot
     * drift from what would actually be sitting in a real install's preferences.
     */
    private fun legacyPrescription() = mutablePreferencesOf().apply {
        LEGACY_GLOBAL_KEYS.dropLast(1).forEach { this[intPreferencesKey(it.name)] = 60 }
        this[longPreferencesKey(LEGACY_GLOBAL_KEYS.last().name)] = now
    }

    @Test
    fun `each run type keeps its own prescription`() {
        val long = prescription(run = 660)
        val quality = prescription(run = 25, walk = 90, repeats = 8)
        val preferences = mutablePreferencesOf()

        preferences.writeCoachPrescription(RunType.LONG, long)
        preferences.writeCoachPrescription(RunType.QUALITY, quality)

        val standing = preferences.coachPrescriptions()
        assertEquals(long, standing[RunType.LONG])
        assertEquals(quality, standing[RunType.QUALITY])
        // Never written, so nothing stands for it — an Easy Run is the plan's until the coach says
        // otherwise about the Easy Run itself.
        assertNull(standing[RunType.EASY])
    }

    @Test
    fun `prescribing for one run type replaces only that slot`() {
        val preferences = mutablePreferencesOf()
        preferences.writeCoachPrescription(RunType.LONG, prescription(run = 660))
        preferences.writeCoachPrescription(RunType.EASY, prescription(run = 1_260, walk = 0, repeats = 1))

        preferences.writeCoachPrescription(RunType.LONG, prescription(run = 720))

        val standing = preferences.coachPrescriptions()
        assertEquals(720, standing[RunType.LONG]?.runDurationSeconds)
        assertEquals(1_260, standing[RunType.EASY]?.runDurationSeconds)
    }

    @Test
    fun `a slot missing any one of its five keys stands for nothing`() {
        // Half a prescription is not a lighter prescription: all four numbers were reasoned about
        // together. The other slots are unaffected by the one that is unreadable.
        val preferences = mutablePreferencesOf()
        preferences.writeCoachPrescription(RunType.LONG, prescription(run = 660))
        preferences.writeCoachPrescription(RunType.EASY, prescription(run = 1_260, walk = 0, repeats = 1))
        preferences.remove(CoachPrescriptionKeys.of(RunType.LONG).repeats)

        val standing = preferences.coachPrescriptions()
        assertNull(standing[RunType.LONG])
        assertEquals(1_260, standing[RunType.EASY]?.runDurationSeconds)
    }

    @Test
    fun `nothing stored is nothing standing, for every run type`() {
        assertEquals(CoachPrescriptions.NONE, mutablePreferencesOf().coachPrescriptions())
        RunType.entries.forEach { assertNull(CoachPrescriptions.NONE[it]) }
    }

    @Test
    fun `clearing drops all three slots in the one edit`() {
        // The guarantee this preserves: graduating a stage and testing mode coming on each clear
        // the coach's work in a single write, so no run can start between two writes and pick up
        // half a change. Three slots must therefore go in one call, not three.
        val preferences = mutablePreferencesOf(PreferencesKeys.ACTIVE_PLAN_ID to "5k_sub_25")
        RunType.entries.forEach { preferences.writeCoachPrescription(it, prescription()) }

        preferences.clearCoachPrescriptions()

        assertEquals(CoachPrescriptions.NONE, preferences.coachPrescriptions())
        // Untouched: this says which plan is attached, not what the coach said about it.
        assertEquals("5k_sub_25", preferences[PreferencesKeys.ACTIVE_PLAN_ID])
    }

    @Test
    fun `a prescription written before the split stands for nothing and is cleared away`() {
        // One global prescription was written against whichever Workout was queued; under Run Types
        // there is no way to say which session it was about, and a prescription applied to the wrong
        // kind of session is the whole bug this ticket closes. So it applies nothing, and the keys
        // go with the next clear rather than sitting in storage for good.
        val preferences = legacyPrescription()

        // The names those keys carry, asserted because nothing else reads them any more: a typo in
        // the list would otherwise leave the real keys behind and still pass.
        assertEquals(
            listOf(
                "coach_target_zone",
                "coach_run_seconds",
                "coach_walk_seconds",
                "coach_repeats",
                "coach_prescribed_at"
            ),
            LEGACY_GLOBAL_KEYS.map { it.name }
        )

        assertEquals(CoachPrescriptions.NONE, preferences.coachPrescriptions())

        preferences.clearCoachPrescriptions()

        LEGACY_GLOBAL_KEYS.forEach { assertNull(preferences[it]) }
    }

    @Test
    fun `the upgrade takes the legacy prescription and its debrief together`() = runBlocking {
        // The numbers reading as nothing is not enough: the debrief is stored separately and rendered
        // on its own, so leaving it behind shows a card about intervals no run will do.
        val stored = legacyPrescription().apply {
            this[PreferencesKeys.LATEST_COACH_MESSAGE] = "Shortened after Tuesday."
            this[PreferencesKeys.ACTIVE_PLAN_ID] = "5k_sub_25"
        }

        assertTrue(dropLegacyCoachWork.shouldMigrate(stored))
        val migrated = dropLegacyCoachWork.migrate(stored)

        LEGACY_GLOBAL_KEYS.forEach { assertNull(migrated[it]) }
        assertNull(migrated[PreferencesKeys.LATEST_COACH_MESSAGE])
        // Only the coach's work goes: the plan the runner chose is untouched.
        assertEquals("5k_sub_25", migrated[PreferencesKeys.ACTIVE_PLAN_ID])
    }

    @Test
    fun `an install with no legacy prescription is never rewritten`() = runBlocking {
        val fresh = mutablePreferencesOf()
        fresh.writeCoachPrescription(RunType.LONG, prescription())
        fresh[PreferencesKeys.LATEST_COACH_MESSAGE] = "Longer intervals today."

        // A per-type slot standing with its debrief is the normal state, not something to migrate —
        // an upgrade that cleared it would throw away work the coach did after the split.
        assertFalse(dropLegacyCoachWork.shouldMigrate(fresh))
    }

    @Test
    fun `the stored keys name the run type they belong to`() {
        // Asserted because these strings are the storage contract: renaming one silently orphans a
        // standing prescription rather than failing anywhere.
        assertEquals("coach_run_seconds_long", CoachPrescriptionKeys.of(RunType.LONG).runSeconds.name)
        assertEquals("coach_run_seconds_easy", CoachPrescriptionKeys.of(RunType.EASY).runSeconds.name)
        assertEquals("coach_run_seconds_quality", CoachPrescriptionKeys.of(RunType.QUALITY).runSeconds.name)
    }

    // --- The coach's work stands on the Runs it was shown (#156) ---

    @Test
    fun `the standing generation keeps the key names it has always had`() {
        // The whole reason the standing generation is prefixed "coach_": an install upgrading to this
        // must find its prescription exactly where it left it, or the upgrade itself is a rollback.
        assertEquals(
            "coach_run_seconds_long",
            CoachPrescriptionKeys.of(RunType.LONG, CoachWorkGeneration.STANDING).runSeconds.name
        )
        assertEquals(
            "coach_previous_run_seconds_long",
            CoachPrescriptionKeys.of(RunType.LONG, CoachWorkGeneration.PREVIOUS).runSeconds.name
        )
    }

    @Test
    fun `a new prescription from the coach keeps the one before it`() {
        val preferences = mutablePreferencesOf()

        preferences.writeCoachWork(RunType.LONG, prescription(run = 600), "Steady.", setOf(1L, 2L))
        preferences.writeCoachWork(RunType.LONG, prescription(run = 660), "Longer today.", setOf(2L, 3L))

        assertEquals(660, preferences.coachPrescriptions()[RunType.LONG]?.runDurationSeconds)
        assertEquals("Longer today.", preferences[PreferencesKeys.LATEST_COACH_MESSAGE])
        assertEquals(
            600,
            preferences.coachPrescriptions(CoachWorkGeneration.PREVIOUS)[RunType.LONG]?.runDurationSeconds
        )
        assertEquals("Steady.", preferences[PreferencesKeys.PREVIOUS_COACH_MESSAGE])
    }

    @Test
    fun `deleting a run the coaching stood on stands the prescription before it up in its place`() {
        // The bug: junk runs deleted, and the intervals the coach dialled back *because of them* —
        // with the debrief explaining them — still on the card. What replaces them is the coaching
        // earned by the runs still in history, not day one of the stage.
        val preferences = mutablePreferencesOf()
        preferences.writeCoachWork(RunType.LONG, prescription(run = 600), "Steady.", setOf(1L, 2L))
        preferences.writeCoachWork(RunType.LONG, prescription(run = 240), "Your HR read 0 BPM.", setOf(2L, 9L))

        assertTrue(preferences.rollBackCoachWorkFedBy(setOf(9L)))

        assertEquals(600, preferences.coachPrescriptions()[RunType.LONG]?.runDurationSeconds)
        assertEquals("Steady.", preferences[PreferencesKeys.LATEST_COACH_MESSAGE])
        // Nothing behind the promoted one: it is what stands now, and there is no third generation.
        assertEquals(
            CoachPrescriptions.NONE,
            preferences.coachPrescriptions(CoachWorkGeneration.PREVIOUS)
        )
        assertNull(preferences[PreferencesKeys.PREVIOUS_COACH_MESSAGE])
    }

    @Test
    fun `the promoted prescription keeps its own date, so an old one still ages out`() {
        // Promoting is standing an earlier prescription back up, not writing a new one — re-stamping
        // it would
        // quietly give a fortnight-old prescription another fortnight.
        val preferences = mutablePreferencesOf()
        val earlier = prescription(run = 600).copy(prescribedAtEpochMillis = now - 1_000_000L)
        preferences.writeCoachWork(RunType.LONG, earlier, "Steady.", setOf(1L))
        preferences.writeCoachWork(RunType.LONG, prescription(run = 240), "Dialled back.", setOf(9L))

        preferences.rollBackCoachWorkFedBy(setOf(9L))

        assertEquals(earlier, preferences.coachPrescriptions()[RunType.LONG])
    }

    @Test
    fun `a delete that fed neither prescription changes nothing`() {
        val preferences = mutablePreferencesOf()
        preferences.writeCoachWork(RunType.LONG, prescription(run = 600), "Steady.", setOf(1L))
        preferences.writeCoachWork(RunType.LONG, prescription(run = 660), "Longer.", setOf(2L))

        assertFalse(preferences.rollBackCoachWorkFedBy(setOf(77L)))

        assertEquals(660, preferences.coachPrescriptions()[RunType.LONG]?.runDurationSeconds)
        assertEquals("Longer.", preferences[PreferencesKeys.LATEST_COACH_MESSAGE])
        assertEquals(
            600,
            preferences.coachPrescriptions(CoachWorkGeneration.PREVIOUS)[RunType.LONG]?.runDurationSeconds
        )
    }

    @Test
    fun `a delete that fed both prescriptions leaves the plan running as written`() {
        // Two coaching generations reasoned from the same run: there is nothing left that the
        // remaining history supports, and the stage's own workout is the honest floor.
        val preferences = mutablePreferencesOf()
        preferences.writeCoachWork(RunType.LONG, prescription(run = 600), "Steady.", setOf(1L, 5L))
        preferences.writeCoachWork(RunType.LONG, prescription(run = 660), "Longer.", setOf(5L, 6L))

        assertTrue(preferences.rollBackCoachWorkFedBy(setOf(5L)))

        assertEquals(CoachPrescriptions.NONE, preferences.coachPrescriptions())
        assertEquals(
            CoachPrescriptions.NONE,
            preferences.coachPrescriptions(CoachWorkGeneration.PREVIOUS)
        )
        assertNull(preferences[PreferencesKeys.LATEST_COACH_MESSAGE])
        assertNull(preferences[PreferencesKeys.PREVIOUS_COACH_MESSAGE])
    }

    @Test
    fun `a delete that fed only the earlier prescription drops it and leaves the standing one`() {
        // Otherwise a later delete could promote coaching about runs that went in this one.
        val preferences = mutablePreferencesOf()
        preferences.writeCoachWork(RunType.LONG, prescription(run = 600), "Steady.", setOf(1L))
        preferences.writeCoachWork(RunType.LONG, prescription(run = 660), "Longer.", setOf(2L))

        assertTrue(preferences.rollBackCoachWorkFedBy(setOf(1L)))

        assertEquals(660, preferences.coachPrescriptions()[RunType.LONG]?.runDurationSeconds)
        assertEquals("Longer.", preferences[PreferencesKeys.LATEST_COACH_MESSAGE])
        assertEquals(
            CoachPrescriptions.NONE,
            preferences.coachPrescriptions(CoachWorkGeneration.PREVIOUS)
        )
    }

    @Test
    fun `coaching that recorded no provenance is taken back by any delete`() {
        // Every prescription written before #156 is one of these, which is the state the runner who
        // reported it was in. Assuming it survived would be the app claiming something it cannot know.
        val preferences = mutablePreferencesOf()
        preferences.writeCoachPrescription(RunType.LONG, prescription(run = 240))
        preferences[PreferencesKeys.LATEST_COACH_MESSAGE] = "Your HR read 0 BPM."

        assertTrue(preferences.rollBackCoachWorkFedBy(setOf(9L)))

        assertEquals(CoachPrescriptions.NONE, preferences.coachPrescriptions())
        assertNull(preferences[PreferencesKeys.LATEST_COACH_MESSAGE])
    }

    @Test
    fun `coaching reasoned from no run at all survives every delete`() {
        // An empty provenance is a recorded answer — the coach was shown no run, so no run leaving
        // history can invalidate it. Told apart from the unrecorded case above by the key existing.
        val preferences = mutablePreferencesOf()
        preferences.writeCoachWork(RunType.LONG, prescription(run = 600), "Welcome to the stage.", emptySet())

        assertFalse(preferences.rollBackCoachWorkFedBy(setOf(9L)))

        assertEquals(600, preferences.coachPrescriptions()[RunType.LONG]?.runDurationSeconds)
    }

    @Test
    fun `the work names every run both its generations stood on`() {
        // What the launch pass (#270) asks history about. Both generations, because the previous one
        // is a Prescription waiting to be promoted onto whatever it named.
        val preferences = mutablePreferencesOf()
        preferences.writeCoachWork(RunType.LONG, prescription(run = 600), "Steady.", setOf(1L, 2L))
        preferences.writeCoachWork(RunType.LONG, prescription(run = 660), "Longer.", setOf(2L, 3L))

        assertEquals(setOf(1L, 2L, 3L), preferences.coachWorkProvenance())
    }

    @Test
    fun `coaching that recorded no provenance gives the launch pass nothing to look into`() {
        // The opposite reading from the delete path's, and deliberately: a delete that is happening
        // guesses safe by taking the coaching away, while "is there anything to check" cannot be
        // answered at all here — so it must not become a reason to check. ADR 0013 covers it.
        val preferences = mutablePreferencesOf()
        preferences.writeCoachPrescription(RunType.LONG, prescription(run = 240))
        preferences[PreferencesKeys.LATEST_COACH_MESSAGE] = "Your HR read 0 BPM."

        assertEquals(emptySet<Long>(), preferences.coachWorkProvenance())
    }

    @Test
    fun `an unreadable stored id names no run`() {
        val preferences = mutablePreferencesOf()
        preferences.writeCoachWork(RunType.LONG, prescription(run = 600), "Steady.", setOf(4L))
        preferences[stringSetPreferencesKey("coach_source_runs")] = setOf("4", "not-a-number")

        assertEquals(setOf(4L), preferences.coachWorkProvenance())
    }

    @Test
    fun `an empty store names no run`() {
        assertEquals(emptySet<Long>(), mutablePreferencesOf().coachWorkProvenance())
    }

    @Test
    fun `an empty store is not written to on a delete`() {
        val preferences = mutablePreferencesOf()

        assertFalse(preferences.rollBackCoachWorkFedBy(setOf(9L)))
        assertFalse(preferences.rollBackCoachWorkFedBy(emptySet()))

        assertEquals(0, preferences.asMap().size)
    }

    @Test
    fun `the hold changes the numbers without becoming a new prescription`() {
        // Pared back to the stage's workout when the coach could not be reached (#248): the same
        // prescription said again, so the one behind it is still the last real evaluation and the
        // runs it stood on still take it back.
        val preferences = mutablePreferencesOf()
        preferences.writeCoachWork(RunType.LONG, prescription(run = 600), "Steady.", setOf(1L))
        preferences.writeCoachWork(RunType.LONG, prescription(run = 900), "Big one today.", setOf(2L))

        preferences.writeCoachPrescription(RunType.LONG, prescription(run = 660))

        // Unmoved by the hold: the previous prescription is still the earlier evaluation, not the
        // un-held one.
        assertEquals(
            600,
            preferences.coachPrescriptions(CoachWorkGeneration.PREVIOUS)[RunType.LONG]?.runDurationSeconds
        )
        assertEquals("Big one today.", preferences[PreferencesKeys.LATEST_COACH_MESSAGE])
        // And its provenance is the one it already had, so run 2 leaving still takes the held
        // prescription back to the one before it.
        assertTrue(preferences.rollBackCoachWorkFedBy(setOf(2L)))
        assertEquals(600, preferences.coachPrescriptions()[RunType.LONG]?.runDurationSeconds)
    }

    @Test
    fun `clearing takes the prescription before the standing one with it`() {
        // Testing mode, a plan change, a restore: whatever the standing numbers were wrong for, the
        // ones behind them are older work against the same thing and are no rollback target either.
        val preferences = mutablePreferencesOf()
        preferences.writeCoachWork(RunType.LONG, prescription(run = 600), "Steady.", setOf(1L))
        preferences.writeCoachWork(RunType.LONG, prescription(run = 660), "Longer.", setOf(2L))

        preferences.clearCoachPrescriptions()

        assertEquals(CoachPrescriptions.NONE, preferences.coachPrescriptions())
        assertEquals(
            CoachPrescriptions.NONE,
            preferences.coachPrescriptions(CoachWorkGeneration.PREVIOUS)
        )
        assertNull(preferences[PreferencesKeys.PREVIOUS_COACH_MESSAGE])
        // Graduating clears the prescriptions and keeps the standing debrief — "you have finished
        // this stage" is the one thing the coach had to say.
        assertEquals("Longer.", preferences[PreferencesKeys.LATEST_COACH_MESSAGE])
    }

    @Test
    fun `clearing the coach's work leaves no provenance behind either`() {
        val preferences = mutablePreferencesOf()
        preferences.writeCoachWork(RunType.LONG, prescription(run = 660), "Longer.", setOf(2L))

        preferences.clearCoachWork()

        assertEquals(0, preferences.asMap().size)
    }

    @Test
    fun `a graduation's debrief survives a later delete`() {
        // Graduating drops the prescriptions and keeps the coach's "you have finished this stage" —
        // the one debrief that stands without numbers under it. It is not coaching about a run, so a
        // delete has nothing to take back from it; read as work standing it would be wiped by the
        // next delete of any run at all, because a graduation leaves no provenance behind either.
        val preferences = mutablePreferencesOf()
        preferences.writeCoachWork(RunType.LONG, prescription(run = 660), "Longer.", setOf(2L))
        preferences.clearCoachPrescriptions()
        preferences[PreferencesKeys.LATEST_COACH_MESSAGE] = "You have finished this stage."

        assertFalse(preferences.rollBackCoachWorkFedBy(setOf(2L)))

        assertEquals("You have finished this stage.", preferences[PreferencesKeys.LATEST_COACH_MESSAGE])
    }

    @Test
    fun `a debrief with no name on it is nobody's`() {
        // Every install upgrading into #296 has one of these: text in the slot, no stamp beside it.
        // The app was already writing graduations (#290), missed Tests (#292) and finished Plans
        // (#294) into that slot before the stamp existed, so the text may be either writer's and
        // reading the absence as the coach would head the app's own words "AI Coach Debrief" —
        // the bug, to a runner who may never have turned a coach on.
        val preferences = mutablePreferencesOf()
        preferences[PreferencesKeys.LATEST_COACH_MESSAGE] = "You ran 5 km in 27:12. Stage 2 complete."

        assertEquals(DebriefAuthor.UNKNOWN, debriefAuthorOf(preferences))
    }

    @Test
    fun `unknown is never written and never comes back out of storage`() {
        // It has no stored spelling at all, so no write can put it down and no read can find it.
        assertNull(DebriefAuthor.UNKNOWN.stored)
        assertEquals(
            listOf(DebriefAuthor.COACH, DebriefAuthor.APP),
            DebriefAuthor.entries.filter { it.stored != null }
        )

        val preferences = mutablePreferencesOf()
        assertThrows(IllegalArgumentException::class.java) {
            preferences.writeStandingDebrief("Whoever wrote this.", DebriefAuthor.UNKNOWN)
        }
        assertEquals(0, preferences.asMap().size)
    }

    @Test
    fun `a name this app never wrote is read as the coach's`() {
        val preferences = mutablePreferencesOf()
        preferences.writeStandingDebrief("Shortened after Tuesday.", DebriefAuthor.COACH)
        preferences[stringPreferencesKey("coach_debrief_author")] = "gemini"

        assertEquals(DebriefAuthor.COACH, debriefAuthorOf(preferences))
    }

    @Test
    fun `the coach's next debrief takes the app's name off the standing one`() {
        // The graduation the app wrote is replaced by real coaching a run later. A stamp only the
        // app ever wrote would leave "Plan Update" over Gemini's words for good.
        val preferences = mutablePreferencesOf()
        preferences.writeStandingDebrief("You ran 5 km in 27:12. Stage 2 complete.", DebriefAuthor.APP)

        preferences.writeCoachWork(RunType.LONG, prescription(run = 660), "Longer.", setOf(2L))

        assertEquals("Longer.", preferences[PreferencesKeys.LATEST_COACH_MESSAGE])
        assertEquals(DebriefAuthor.COACH, debriefAuthorOf(preferences))
    }

    @Test
    fun `a rollback brings the promoted debrief's own name back with it`() {
        // A missed Test writes the app's words into the standing slot without touching the numbers
        // (#292), so the stamp says APP while the coach's latest Prescription stands. Deleting the
        // Run that Prescription was reasoned from promotes the coach's previous work — text and
        // name together, or the runner reads Gemini's "Steady." under the app's heading.
        val preferences = mutablePreferencesOf()
        preferences.writeCoachWork(RunType.LONG, prescription(run = 600), "Steady.", setOf(1L))
        preferences.writeCoachWork(RunType.LONG, prescription(run = 660), "Longer.", setOf(2L))
        preferences.writeStandingDebrief("You were 2:41 off.", DebriefAuthor.APP)

        assertTrue(preferences.rollBackCoachWorkFedBy(setOf(2L)))

        assertEquals("Steady.", preferences[PreferencesKeys.LATEST_COACH_MESSAGE])
        assertEquals(DebriefAuthor.COACH, debriefAuthorOf(preferences))
    }

    @Test
    fun `a rollback with nothing behind it takes the name away too`() {
        val preferences = mutablePreferencesOf()
        preferences.writeCoachWork(RunType.LONG, prescription(run = 660), "Longer.", setOf(2L))

        assertTrue(preferences.rollBackCoachWorkFedBy(setOf(2L)))

        // Nothing left at all: a stamp outliving the text it names is a heading over an empty slot,
        // waiting to be inherited by whoever writes next.
        assertEquals(0, preferences.asMap().size)
    }

    @Test
    fun `a run type the coach never wrote about is untouched by a rollback`() {
        // Only the Long slot is ever written today (ADR 0006), but a rollback restores a whole
        // generation, so this is what stops it inventing or erasing another kind's work.
        val preferences = mutablePreferencesOf()
        preferences.writeCoachPrescription(RunType.EASY, prescription(run = 1_260, walk = 0, repeats = 1))
        preferences.writeCoachWork(RunType.LONG, prescription(run = 600), "Steady.", setOf(1L))
        preferences.writeCoachWork(RunType.LONG, prescription(run = 660), "Longer.", setOf(2L))

        preferences.rollBackCoachWorkFedBy(setOf(2L))

        assertEquals(600, preferences.coachPrescriptions()[RunType.LONG]?.runDurationSeconds)
        assertEquals(1_260, preferences.coachPrescriptions()[RunType.EASY]?.runDurationSeconds)
    }
}
