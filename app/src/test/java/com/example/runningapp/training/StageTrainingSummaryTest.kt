package com.example.runningapp.training

import com.example.runningapp.TrainingPlanProvider
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Stage card says about the training already recorded under it (#445) — the half of a
 * requirement written in weeks that the app measures, told to the runner instead of only the coach.
 */
class StageTrainingSummaryTest {

    private fun day(iso: String) = LocalDate.parse(iso)

    /** Three full weeks and nine qualifying Runs, first Run on Monday 3 August. */
    private fun threeWeeksNineRuns(): StageTrainingRecord = stageTrainingRecordOf(
        days = listOf(
            day("2026-08-03"), day("2026-08-05"), day("2026-08-08"),
            day("2026-08-10"), day("2026-08-12"), day("2026-08-15"),
            day("2026-08-17"), day("2026-08-19"), day("2026-08-22"),
        ),
        through = day("2026-08-24"),
    )

    @Test
    fun `names the weeks trained against the weeks the stage asks for`() {
        val summary = stageTrainingSummaryOf(threeWeeksNineRuns(), weeksRequired = 4)

        assertEquals("3 of 4 full weeks trained — 9 qualifying runs", summary.headline)
    }

    @Test
    fun `a stage whose bar names no weeks states the count alone`() {
        val summary = stageTrainingSummaryOf(threeWeeksNineRuns(), weeksRequired = null)

        // No weeks figure of any kind: the weeks trained, the row of weeks and the sentence handing
        // the rest to the coach are all answers to a bar written in weeks, and this bar names none.
        assertEquals("9 qualifying runs", summary.headline)
        assertTrue(summary.weeks.isEmpty())
        assertNull(summary.weeksCaption)
        assertNull(summary.judgementLine)
        // The Runs are still counted, and the rule that counts them still said.
        assertTrue(summary.countedLine.contains("shared with the coach"))
    }

    @Test
    fun `the desk test's two repeats are not read as two weeks`() {
        val stage = TrainingPlanProvider.getAllPlans()
            .flatMap { it.stages }
            .first { it.graduationRequirementText.contains("repeats") }

        val summary = stageTrainingSummaryOf(threeWeeksNineRuns(), stage.weeksRequirement)

        assertEquals("9 qualifying runs", summary.headline)
    }

    @Test
    fun `a record longer than the weeks it draws says so`() {
        // Fourteen weeks of training; the record keeps the last twelve. Left unsaid, the columns
        // would add up to less than the headline with nothing to explain it — and "the oldest on
        // the left" would be false.
        val days = (0..13).map { day("2026-05-04").plusWeeks(it.toLong()) }
        val record = stageTrainingRecordOf(days = days, through = day("2026-08-03"))

        val summary = stageTrainingSummaryOf(record, weeksRequired = 4)

        assertEquals(14, summary.headline.substringBefore(" qualifying").takeLastWhile {
            it.isDigit()
        }.toInt())
        assertEquals(12, summary.weeks.size)
        assertTrue(summary.weeksCaption!!.contains("most recent 12"))
        assertFalse(summary.weeksCaption!!.contains("oldest"))
    }

    @Test
    fun `a record short enough to be drawn whole names both ends`() {
        val summary = stageTrainingSummaryOf(threeWeeksNineRuns(), weeksRequired = 4)

        assertTrue(summary.weeksCaption!!.contains("oldest on the left"))
    }

    @Test
    fun `the weeks asked for become a bracket once they are trained`() {
        // Five full weeks against a bar of four. "5 of 4" would read as a fault in the app.
        val record = stageTrainingRecordOf(
            days = listOf(day("2026-07-20"), day("2026-08-24")),
            through = day("2026-08-24"),
        )

        val summary = stageTrainingSummaryOf(record, weeksRequired = 4)

        assertEquals(
            "5 full weeks trained (this stage asks for 4) — 2 qualifying runs",
            summary.headline
        )
    }

    @Test
    fun `a stage with nothing recorded says so in words and shows no figure`() {
        val summary = stageTrainingSummaryOf(StageTrainingRecord.NONE, weeksRequired = 4)

        assertEquals("No qualifying runs recorded in this stage yet.", summary.headline)
        // "Nothing recorded" and "measured at zero" are different facts, and a row of empty weeks
        // would draw the second one.
        assertTrue(summary.weeks.isEmpty())
        assertFalse(summary.headline.contains("0"))
    }

    @Test
    fun `the empty weeks are kept, because the gap is the question`() {
        // A Run, a fortnight off, then a Run.
        val record = stageTrainingRecordOf(
            days = listOf(day("2026-08-03"), day("2026-08-24")),
            through = day("2026-08-24"),
        )

        val summary = stageTrainingSummaryOf(record, weeksRequired = 4)

        assertEquals(listOf(1, 0, 0, 1), summary.weeks.map { it.qualifyingRuns })
    }

    @Test
    fun `one week and one run are named in the singular`() {
        val record = stageTrainingRecordOf(
            days = listOf(day("2026-08-03")),
            through = day("2026-08-10"),
        )

        assertEquals(
            "1 qualifying run",
            stageTrainingSummaryOf(record, weeksRequired = null).headline
        )
        assertEquals(
            "1 full week trained (this stage asks for 1) — 1 qualifying run",
            stageTrainingSummaryOf(record, weeksRequired = 1).headline
        )
    }

    @Test
    fun `the coach is named as the judge of the rest, and nothing is offered`() {
        val summary = stageTrainingSummaryOf(threeWeeksNineRuns(), weeksRequired = 4)

        assertNotNull(summary.judgementLine)
        assertTrue(summary.judgementLine!!.contains("coach"))
        // A statement, never an offer: the card may not read as a graduation about to be handed
        // over (ADR 0016).
        val everything = summary.headline + " " + summary.judgementLine + " " + summary.countedLine
        listOf("graduate", "unlock", "promote", "%").forEach {
            assertFalse(it, everything.lowercase().contains(it))
        }
    }

    @Test
    fun `the sharing rule the count uses is said on the screen`() {
        val summary = stageTrainingSummaryOf(threeWeeksNineRuns(), weeksRequired = 4)

        // A runner whose count does not move after a Run they kept private is owed the reason here
        // rather than left guessing.
        assertTrue(summary.countedLine.contains("shared with the coach"))
        assertTrue(summary.countedLine.contains("not marked as a walk"))
        assertTrue(summary.countedLine.contains("two minutes"))
    }

    @Test
    fun `stage 1's bar in numbers is the bar its own sentence names`() {
        val stage = TrainingPlanProvider.getAllPlans()
            .first { it.id == "5k_sub_25" }
            .stages
            .first()

        assertEquals(4, stage.weeksRequirement)
        assertTrue(stage.graduationRequirementText.contains("4 weeks"))
    }

    @Test
    fun `a stage whose bar is a time or a count of repeats names no weeks`() {
        // The Desk Test plan's "Complete 2 short run/walk repeats" is the one that must not be read
        // as two weeks, and the 5K bars are times.
        TrainingPlanProvider.getAllPlans()
            .flatMap { it.stages }
            .filterNot { it.graduationRequirementText.contains("weeks") }
            .forEach { assertNull(it.id, it.weeksRequirement) }
    }
}
