package com.example.runningapp.ui.workout

import com.example.runningapp.CoachPrescription
import com.example.runningapp.CoachPrescriptions
import com.example.runningapp.RunType
import com.example.runningapp.UserSettings
import com.example.runningapp.WorkoutTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The acceptance criteria for #111, expressed against the card's state. */
class TodayCardModelsTest {

    private val aerobicFoundation =
        WorkoutTemplate("w1_s2", "Aerobic Foundation", 2, 300, 60, 5, warmUpSeconds = 480, coolDownSeconds = 180, runType = RunType.LONG)
    private val settings = UserSettings()

    private val now = 1_700_000_000_000L

    // Longer than the workout it is applied to, because a prescription asking for less work than
    // the plan applies nothing (#170) — see the shortening case below.
    private fun prescription(
        targetZone: Int = 2,
        run: Int = 360,
        walk: Int = 90,
        repeats: Int = 5,
        prescribedAt: Long = now
    ) = CoachPrescription(targetZone, run, walk, repeats, prescribedAt)

    /** What the coach has standing, by the Run Type each prescription was written for (#175). */
    private fun standing(vararg slots: Pair<RunType, CoachPrescription>) =
        CoachPrescriptions(slots.toMap())

    private fun card(
        stageTitle: String? = "Stage 1: Base Builder",
        workout: WorkoutTemplate? = aerobicFoundation,
        stageWorkouts: List<WorkoutTemplate> = listOfNotNull(workout),
        pickedWorkoutId: String? = null,
        settings: UserSettings = this.settings,
        prescriptions: CoachPrescriptions = CoachPrescriptions.NONE,
        nowEpochMillis: Long = now,
        runMode: String = "outdoor",
        skippedToday: Boolean = false,
        fiveKTestDue: Boolean = false
    ) = todayCardUiState(
        stageTitle,
        stageWorkouts,
        pickedWorkoutId,
        settings,
        prescriptions,
        nowEpochMillis,
        runMode,
        skippedToday,
        fiveKTestDue
    )

    @Test
    fun `the planned card shows stage, title, shape, target and the envelope with a total`() {
        val state = card()
        assertEquals("TODAY · STAGE 1: BASE BUILDER", state.eyebrow)
        assertEquals("Aerobic Foundation", state.title)
        assertEquals("5 × (5 min run / 1 min walk)", state.detailLine)
        assertEquals("Target: Moderate", state.targetPill)
        // 8 min warm-up + 5 × 6 min + 3 min cool-down = 41 min.
        assertEquals("8 min warm-up · 3 min cool-down · ≈ 41 min", state.envelopeLine)
    }

    @Test
    fun `a single continuous workout drops the repeat count and the walk`() {
        val thirtyMinuteRun = WorkoutTemplate("w2_s2", "The 30-Minute Run", 2, 1800, 0, 1, runType = RunType.EASY)
        assertEquals("30 min run", card(workout = thirtyMinuteRun).detailLine)
    }

    @Test
    fun `repeats without a walk still read as repeats`() {
        val fourByTen = WorkoutTemplate("x", "Cruise", 3, 600, 0, 4, warmUpSeconds = 0, coolDownSeconds = 0, runType = RunType.LONG)
        assertEquals("4 × 10 min run", card(workout = fourByTen).detailLine)
    }

    @Test
    fun `sub-minute intervals read in seconds and the total never rounds to zero`() {
        val deskTest = WorkoutTemplate("d", "10s Run / 10s Walk Test", 2, 10, 10, 2, 0, 0, RunType.LONG)
        val state = card(workout = deskTest)
        assertEquals("2 × (10 s run / 10 s walk)", state.detailLine)
        assertEquals("≈ 1 min", state.envelopeLine)
    }

    @Test
    fun `no plan is a solid Open run card, never an empty box`() {
        val state = card(stageTitle = null, workout = null)
        assertEquals("TODAY", state.eyebrow)
        assertEquals("Open run", state.title)
        assertEquals("Zone coaching on · splits every 1 km", state.detailLine)
        assertEquals("Target: Moderate", state.targetPill)
        assertNull(state.envelopeLine)
    }

    @Test
    fun `skipped today and no plan at all render identically except for the link`() {
        val skipped = card(skippedToday = true)
        val noPlan = card(stageTitle = null, workout = null)
        assertEquals(noPlan.copy(link = skipped.link), skipped)
        assertNotEquals(noPlan.link, skipped.link)
    }

    @Test
    fun `the link is skip when a plan is queued and a symmetric undo once skipped`() {
        assertEquals(TodayCardLinkKind.SKIP, card().link.kind)
        assertEquals("Skip today", card().link.label)

        val skipped = card(skippedToday = true)
        assertEquals(TodayCardLinkKind.UNDO, skipped.link.kind)
        assertEquals("Bring back Aerobic Foundation", skipped.link.label)
    }

    @Test
    fun `with no plan at all the link leads to the plans, not to an undo`() {
        val link = card(stageTitle = null, workout = null).link
        assertEquals(TodayCardLinkKind.CHOOSE_PLAN, link.kind)
        assertEquals("Choose a plan", link.label)
    }

    @Test
    fun `treadmill has no splits to promise`() {
        val state = card(stageTitle = null, workout = null, runMode = "treadmill")
        assertEquals("Zone coaching on", state.detailLine)
    }

    @Test
    fun `an open run with coaching off says so rather than promising coaching`() {
        val state = card(
            stageTitle = null,
            workout = null,
            settings = settings.copy(coachingEnabled = false),
            runMode = "treadmill"
        )
        assertEquals("Zone coaching off", state.detailLine)
    }

    @Test
    fun `splits are only promised when the announcements are on`() {
        val state = card(
            stageTitle = null,
            workout = null,
            settings = settings.copy(splitAnnouncementsEnabled = false)
        )
        assertEquals("Zone coaching on", state.detailLine)
    }

    @Test
    fun `an unadapted workout carries no coach line`() {
        assertNull(card().coachNote)
    }

    @Test
    fun `an adapted workout shows the adapted numbers and one line naming the change`() {
        val state = card(
            settings = settings.copy(
                latestCoachMessage = "Shortened after Tuesday — your heart rate drifted in the last two intervals."
            ),
            prescriptions = standing(RunType.LONG to prescription())
        )
        assertEquals("5 × (6 min run / 1 min 30 s walk)", state.detailLine)
        assertEquals(
            "Coach: Shortened after Tuesday — your heart rate drifted in the last two intervals.",
            state.coachNote
        )
        // Never the original numbers.
        assertTrue(state.detailLine.contains("6 min run"))
    }

    @Test
    fun `only the debrief's first sentence reaches the card`() {
        val state = card(
            settings = settings.copy(
                latestCoachMessage = "Shortened after Tuesday. Your heart rate drifted in the last " +
                    "two intervals, and the final one was cut short.\n\nWe will build back up next week."
            ),
            prescriptions = standing(RunType.LONG to prescription())
        )
        assertEquals("Coach: Shortened after Tuesday.", state.coachNote)
    }

    @Test
    fun `the coach's own decimals do not cut the sentence in half`() {
        val state = card(
            settings = settings.copy(
                latestCoachMessage = "Your pace was 5.30 min/km, so today eases off. And then more."
            ),
            prescriptions = standing(RunType.LONG to prescription())
        )
        assertEquals("Coach: Your pace was 5.30 min/km, so today eases off.", state.coachNote)
    }

    @Test
    fun `a debrief with no sentence end is shown whole rather than guessed at`() {
        val state = card(
            settings = settings.copy(latestCoachMessage = "Easing off today"),
            prescriptions = standing(RunType.LONG to prescription())
        )
        assertEquals("Coach: Easing off today", state.coachNote)
    }

    @Test
    fun `an adaptation without a message still names that something changed`() {
        assertEquals(
            "Coach: Today's intervals were adjusted for you.",
            card(prescriptions = standing(RunType.LONG to prescription())).coachNote
        )
    }

    @Test
    fun `the coach can drop today's target, and the pill follows it`() {
        val threshold = WorkoutTemplate("w3_s1", "Threshold Intervals", 4, 300, 120, 5, runType = RunType.QUALITY)
        val eased = card(workout = threshold, prescriptions = standing(RunType.QUALITY to prescription(targetZone = 2)))
        assertEquals("Target: Moderate", eased.targetPill)
    }

    @Test
    fun `a prescription asking for less work than the plan leaves the card as written`() {
        // The coach is floored at the stage's own workout when it writes (#170), but a prescription
        // stands for a fortnight and the plan's numbers can be rewritten under it (#173).
        val eased = card(prescriptions = standing(RunType.LONG to prescription(run = 180, walk = 60, repeats = 6)))
        assertEquals("5 × (5 min run / 1 min walk)", eased.detailLine)
        assertNull(eased.coachNote)
    }

    @Test
    fun `a stale prescription leaves the card showing the plan as written`() {
        val staleByOneDay = now - (com.example.runningapp.COACH_PRESCRIPTION_MAX_AGE_DAYS + 1) *
            24L * 60L * 60L * 1000L
        val state = card(prescriptions = standing(RunType.LONG to prescription(prescribedAt = staleByOneDay)))
        assertEquals("5 × (5 min run / 1 min walk)", state.detailLine)
        assertNull(state.coachNote)
    }

    @Test
    fun `the target pill follows the workout, not the global default`() {
        val threshold = WorkoutTemplate("w3_s1", "Threshold Intervals", 4, 300, 120, 5, runType = RunType.QUALITY)
        assertEquals("Target: Threshold", card(workout = threshold).targetPill)
    }

    @Test
    fun `a skipped day falls back to the global target, since the workout is not being run`() {
        val threshold = WorkoutTemplate("w3_s1", "Threshold Intervals", 4, 300, 120, 5, runType = RunType.QUALITY)
        assertEquals("Target: Moderate", card(workout = threshold, skippedToday = true).targetPill)
    }

    // #174 — the runner picks which of the stage's three Runs today is.

    private val long = WorkoutTemplate(
        "w1_long", "Endurance Walk-Run", 2, 600, 120, 3,
        warmUpSeconds = 480, coolDownSeconds = 180, runType = RunType.LONG
    )
    private val easy = WorkoutTemplate(
        "w1_easy", "Easy Continuous", 2, 1200, 0, 1,
        warmUpSeconds = 300, coolDownSeconds = 180, runType = RunType.EASY
    )
    private val quality = WorkoutTemplate(
        "w1_quality", "Strides", 2, 20, 90, 6,
        warmUpSeconds = 1200, coolDownSeconds = 180, runType = RunType.QUALITY
    )
    private val stageOne = listOf(long, easy, quality)

    private fun stageCard(
        pickedWorkoutId: String? = null,
        prescriptions: CoachPrescriptions = CoachPrescriptions.NONE,
        skippedToday: Boolean = false
    ) = card(
        stageWorkouts = stageOne,
        pickedWorkoutId = pickedWorkoutId,
        prescriptions = prescriptions,
        skippedToday = skippedToday
    )

    @Test
    fun `the card offers all three of the stage's run types`() {
        val offered = stageCard().workouts
        assertEquals(listOf("Long", "Easy", "Quality"), offered.map { it.runTypeLabel })
        assertEquals(listOf("w1_long", "w1_easy", "w1_quality"), offered.map { it.workoutId })
        assertEquals(listOf("Endurance Walk-Run", "Easy Continuous", "Strides"), offered.map { it.title })
    }

    @Test
    fun `each offered workout shows its own shape and total`() {
        val offered = stageCard().workouts.associateBy { it.runTypeLabel }
        // 8 min warm-up + 3 × 12 min + 3 min cool-down = 47 min.
        assertEquals("3 × (10 min run / 2 min walk) · ≈ 47 min", offered.getValue("Long").summaryLine)
        assertEquals("20 min run · ≈ 28 min", offered.getValue("Easy").summaryLine)
        assertEquals("6 × (20 s run / 1 min 30 s walk) · ≈ 34 min", offered.getValue("Quality").summaryLine)
    }

    @Test
    fun `the picked workout is the one the card is about`() {
        val picked = stageCard(pickedWorkoutId = "w1_quality")
        assertEquals("Strides", picked.title)
        assertEquals("6 × (20 s run / 1 min 30 s walk)", picked.detailLine)
        assertEquals(listOf(false, false, true), picked.workouts.map { it.picked })
    }

    @Test
    fun `with nothing picked the stage's first workout is today's`() {
        val untouched = stageCard()
        assertEquals("Endurance Walk-Run", untouched.title)
        assertEquals(listOf(true, false, false), untouched.workouts.map { it.picked })
    }

    @Test
    fun `a pick from a stage that is no longer attached falls back rather than emptying the card`() {
        val stale = stageCard(pickedWorkoutId = "w2_s1")
        assertEquals("Endurance Walk-Run", stale.title)
        assertEquals(listOf(true, false, false), stale.workouts.map { it.picked })
    }

    @Test
    fun `every offered workout shows the numbers its own slot would run it at`() {
        // Each row asks the coach for its own Run Type (#175) and then asks the floor (#170) for
        // itself. Here the Quality slot stretches Strides; the Long slot asks for less running than
        // the Long Run's own workout, so that row stays as the plan wrote it; Easy has no slot at all.
        val stretched = stageCard(
            prescriptions = standing(
                RunType.QUALITY to prescription(run = 200, walk = 60, repeats = 4),
                RunType.LONG to prescription(run = 180, walk = 60, repeats = 6)
            )
        )
        val offered = stretched.workouts.associateBy { it.runTypeLabel }
        assertEquals(
            "4 × (3 min 20 s run / 1 min walk) · ≈ 40 min",
            offered.getValue("Quality").summaryLine
        )
        assertEquals("3 × (10 min run / 2 min walk) · ≈ 47 min", offered.getValue("Long").summaryLine)
        assertEquals("20 min run · ≈ 28 min", offered.getValue("Easy").summaryLine)
    }

    @Test
    fun `a prescription for one run type moves no other row, whatever it asks for`() {
        // The hazard #175 closes, seen on the card: ten-minute run Intervals written for the Long Run
        // would otherwise be offered as today's stride session.
        val forTheLongRun = standing(RunType.LONG to prescription(run = 660, walk = 120, repeats = 3))
        val offered = stageCard(prescriptions = forTheLongRun).workouts.associateBy { it.runTypeLabel }

        assertEquals("3 × (11 min run / 2 min walk) · ≈ 50 min", offered.getValue("Long").summaryLine)
        assertEquals("6 × (20 s run / 1 min 30 s walk) · ≈ 34 min", offered.getValue("Quality").summaryLine)
        assertEquals("20 min run · ≈ 28 min", offered.getValue("Easy").summaryLine)
    }

    @Test
    fun `the picked row's numbers are the card's own, slot for slot`() {
        // Nothing may promise a Workout at numbers the Run then resolves differently (#111): the
        // heading and the row it was picked from read the same slot.
        val picked = stageCard(
            pickedWorkoutId = "w1_quality",
            prescriptions = standing(RunType.QUALITY to prescription(run = 25, walk = 90, repeats = 6))
        )
        assertEquals("6 × (25 s run / 1 min 30 s walk)", picked.detailLine)
        assertEquals(
            "6 × (25 s run / 1 min 30 s walk) · ≈ 35 min",
            picked.workouts.single { it.picked }.summaryLine
        )
    }

    @Test
    fun `an open run offers no pick, because there is no stage to pick from`() {
        assertTrue(card(stageTitle = null, workout = null).workouts.isEmpty())
        assertTrue(stageCard(skippedToday = true).workouts.isEmpty())
    }

    // --- The line a Workout's numbers cannot say for themselves (#291) -------------------------

    private val fiveKTest = WorkoutTemplate(
        id = "w2_s3",
        title = "5K Test",
        targetZone = 4,
        runDurationSeconds = 1800,
        walkDurationSeconds = 0,
        totalRepeats = 1,
        warmUpSeconds = 0,
        coolDownSeconds = 0,
        runType = RunType.QUALITY,
        instruction = "Warm up for about 10 minutes before you start this.",
        isTest = true
    )

    @Test
    fun `a workout carrying an instruction shows it`() {
        // Without the line the no-envelope decision is invisible and the runner presses START cold.
        assertEquals(
            "Warm up for about 10 minutes before you start this.",
            card(workout = fiveKTest).instructionLine
        )
    }

    @Test
    fun `a workout whose numbers say the whole of it shows no instruction`() {
        assertNull(card().instructionLine)
        assertNull(card(stageTitle = null, workout = null).instructionLine)
    }

    @Test
    fun `a prescription does not rewrite the instruction`() {
        // The coach prescribes work, not the whole Workout (#113), so the line stays the plan's.
        val state = card(
            workout = fiveKTest,
            prescriptions = standing(RunType.QUALITY to prescription(run = 1900, walk = 0, repeats = 1))
        )

        assertEquals("Warm up for about 10 minutes before you start this.", state.instructionLine)
    }

    // --- The card says when a 5K test is due (#292) ------------------------------------------

    /** A Stage that offers a Test as well as an ordinary Workout, which is how they all do. */
    private val stageWithATest = listOf(aerobicFoundation, fiveKTest)

    @Test
    fun `a due test is offered on the card`() {
        val state = card(stageWorkouts = stageWithATest, fiveKTestDue = true)

        assertEquals("A 5K test is due. Pick it below whenever you fancy it.", state.testDueLine)
    }

    @Test
    fun `nothing is said while the test is not due`() {
        assertNull(card(stageWorkouts = stageWithATest, fiveKTestDue = false).testDueLine)
    }

    @Test
    fun `a stage with no test never says one is due`() {
        // Nothing else can make the flag true, but the card is not the place that rule is kept.
        assertNull(card(fiveKTestDue = true).testDueLine)
    }

    @Test
    fun `the prompt goes once the test is today's pick`() {
        // The card is then the whole prompt; telling the runner to pick what they have picked
        // reads as a second, unmet ask.
        val state = card(
            stageWorkouts = stageWithATest,
            pickedWorkoutId = fiveKTest.id,
            fiveKTestDue = true
        )

        assertEquals("5K Test", state.title)
        assertNull(state.testDueLine)
    }

    @Test
    fun `a skipped day says nothing about the test`() {
        // The open-run card offers no Workout to pick the Test from, so "pick it below" would
        // point at nothing.
        val state = card(stageWorkouts = stageWithATest, skippedToday = true, fiveKTestDue = true)

        assertEquals("Open run", state.title)
        assertNull(state.testDueLine)
    }

    @Test
    fun `the prompt does not displace the instruction or the coach's note`() {
        val state = card(
            stageWorkouts = stageWithATest,
            settings = UserSettings(latestCoachMessage = "Nice work. Ten minutes more next time."),
            prescriptions = standing(RunType.LONG to prescription()),
            fiveKTestDue = true
        )

        assertNotNull(state.testDueLine)
        assertEquals("Coach: Nice work.", state.coachNote)
    }
}
