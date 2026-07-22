package com.example.runningapp.ui.workout

import com.example.runningapp.CoachPrescription
import com.example.runningapp.UserSettings
import com.example.runningapp.WorkoutTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The acceptance criteria for #111, expressed against the card's state. */
class TodayCardModelsTest {

    private val aerobicFoundation =
        WorkoutTemplate("w1_s2", "Aerobic Foundation", 2, 300, 60, 5, warmUpSeconds = 480, coolDownSeconds = 180)
    private val settings = UserSettings()

    private val now = 1_700_000_000_000L

    private fun prescription(
        targetZone: Int = 2,
        run: Int = 240,
        walk: Int = 90,
        repeats: Int = 4,
        prescribedAt: Long = now
    ) = CoachPrescription(targetZone, run, walk, repeats, prescribedAt)

    private fun card(
        stageTitle: String? = "Stage 1: Base Builder",
        workout: WorkoutTemplate? = aerobicFoundation,
        settings: UserSettings = this.settings,
        prescription: CoachPrescription? = null,
        nowEpochMillis: Long = now,
        runMode: String = "outdoor",
        skippedToday: Boolean = false
    ) = todayCardUiState(
        stageTitle, workout, settings, prescription, nowEpochMillis, runMode, skippedToday
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
        val thirtyMinuteRun = WorkoutTemplate("w2_s2", "The 30-Minute Run", 2, 1800, 0, 1)
        assertEquals("30 min run", card(workout = thirtyMinuteRun).detailLine)
    }

    @Test
    fun `repeats without a walk still read as repeats`() {
        val fourByTen = WorkoutTemplate("x", "Cruise", 3, 600, 0, 4, warmUpSeconds = 0, coolDownSeconds = 0)
        assertEquals("4 × 10 min run", card(workout = fourByTen).detailLine)
    }

    @Test
    fun `sub-minute intervals read in seconds and the total never rounds to zero`() {
        val deskTest = WorkoutTemplate("d", "10s Run / 10s Walk Test", 2, 10, 10, 2, 0, 0)
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
            prescription = prescription()
        )
        assertEquals("4 × (4 min run / 1 min 30 s walk)", state.detailLine)
        assertEquals(
            "Coach: Shortened after Tuesday — your heart rate drifted in the last two intervals.",
            state.coachNote
        )
        // Never the original numbers.
        assertTrue(state.detailLine.contains("4 min run"))
    }

    @Test
    fun `an adaptation without a message still names that something changed`() {
        assertEquals(
            "Coach: Today's intervals were adjusted for you.",
            card(prescription = prescription()).coachNote
        )
    }

    @Test
    fun `the coach can drop today's target, and the pill follows it`() {
        val threshold = WorkoutTemplate("w3_s1", "Threshold Intervals", 4, 300, 120, 5)
        val eased = card(workout = threshold, prescription = prescription(targetZone = 2))
        assertEquals("Target: Moderate", eased.targetPill)
    }

    @Test
    fun `a stale prescription leaves the card showing the plan as written`() {
        val staleByOneDay = now - (com.example.runningapp.COACH_PRESCRIPTION_MAX_AGE_DAYS + 1) *
            24L * 60L * 60L * 1000L
        val state = card(prescription = prescription(prescribedAt = staleByOneDay))
        assertEquals("5 × (5 min run / 1 min walk)", state.detailLine)
        assertNull(state.coachNote)
    }

    @Test
    fun `the target pill follows the workout, not the global default`() {
        val threshold = WorkoutTemplate("w3_s1", "Threshold Intervals", 4, 300, 120, 5)
        assertEquals("Target: Threshold", card(workout = threshold).targetPill)
    }

    @Test
    fun `a skipped day falls back to the global target, since the workout is not being run`() {
        val threshold = WorkoutTemplate("w3_s1", "Threshold Intervals", 4, 300, 120, 5)
        assertEquals("Target: Moderate", card(workout = threshold, skippedToday = true).targetPill)
    }
}
