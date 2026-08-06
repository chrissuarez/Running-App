package com.example.runningapp.data

import com.example.runningapp.training.FormVerdict
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCoachClientTest {

    private val oneRunWalkSession = AiTrainingContext(
        currentStageTitle = "Base Builder",
        graduationRequirement = "Complete run-walk sessions consistently",
        recentRuns = listOf(
            AiRecentRun(
                durationSeconds = 1800,
                avgHr = 125,
                sessionType = "Run/Walk",
                timestamp = 1_742_000_000_000,
                runMode = "outdoor",
                distanceKm = 5.4,
                fastest5kSeconds = 1620
            )
        )
    )

    @Test
    fun `no Interval-quality metric reaches the coach, in the data or in the reading of it`() {
        val prompt = buildEvaluationPrompt(oneRunWalkSession)

        listOf(
            "severeBreakdown",
            "poorTolerance",
            "strainedCompletion",
            "strongCompletion",
            "cleanInterval",
            "hrDrift",
            "intervalCompletionRatio",
            "avgRecoverySecondsAfterTrigger",
            "avgHrAtTrigger",
            "runWalkMetrics"
        ).forEach { metric ->
            assertFalse("prompt still mentions $metric", prompt.contains(metric, ignoreCase = true))
        }
    }

    @Test
    fun `no Run is described to the coach as a breakdown, a tolerance failure or a strain`() {
        val prompt = buildEvaluationPrompt(oneRunWalkSession)

        // The words CONTEXT.md bans for a Trigger, not the loose stems: "strain" alone would fail on
        // an innocent "constraint" one day and the failure would read as a real finding.
        listOf("breakdown", "poor tolerance", "strained").forEach { word ->
            assertFalse("prompt still mentions $word", prompt.contains(word, ignoreCase = true))
        }
    }

    @Test
    fun `a Stage asking for a 5K time is judged from the measured 5K and nothing else`() {
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(graduationRequirement = "Successfully complete a 5K under 30 minutes.")
        )

        assertTrue(prompt.contains("\"fastest5kSeconds\":1620"))
        assertTrue(
            prompt.contains(
                "whole run including its warm-up and cool-down, so on a GPS-recorded run it is NOT a 5K time"
            )
        )
        assertTrue(
            prompt.contains(
                "If the stage requirement asks for a 5K in a time, judge it ONLY from fastest5kSeconds"
            )
        )
    }

    @Test
    fun `a treadmill Run's stated distance can answer a requirement of exactly that distance`() {
        // The one thing two numbers settle (#231, ADR 0008): a stated distance and a whole-Run
        // duration are a time over the whole Run. 5 km in 24:30 graduates a 5K in 24:59.
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                graduationRequirement = "Run a 5K in 24:59 or faster.",
                recentRuns = oneRunWalkSession.recentRuns.map {
                    it.copy(runMode = "treadmill", distanceKm = 5.0, fastest5kSeconds = null, durationSeconds = 1470)
                }
            )
        )

        assertTrue(prompt.contains("\"runMode\":\"treadmill\""))
        assertTrue(prompt.contains("\"distanceKm\":5.0"))
        assertTrue(
            prompt.contains(
                "its distanceKm and durationSeconds establish a time for the WHOLE run and nothing shorter"
            )
        )
    }

    @Test
    fun `a treadmill Run longer than the requirement is declined rather than guessed at`() {
        // 6 km in 30:00 may hold a sub-25 5K and may not, and the splits to say which were never
        // handed over. Ruling either way is deriving a best effort from an average pace, which is
        // what ADR 0008 refuses — with a graduation that cannot be taken back behind it.
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                graduationRequirement = "Run a 5K in 24:59 or faster.",
                recentRuns = oneRunWalkSession.recentRuns.map {
                    it.copy(runMode = "treadmill", distanceKm = 6.0, fastest5kSeconds = null, durationSeconds = 1800)
                }
            )
        )

        assertTrue(
            prompt.contains(
                "If that treadmill run went FURTHER than the requirement's distance, you cannot tell " +
                    "how fast the requirement's distance alone was covered"
            )
        )
        assertTrue(
            prompt.contains("Never divide a distance by a duration to estimate a pace or a shorter-distance time.")
        )
    }

    @Test
    fun `a 5K that was never measured cannot graduate a Stage, and the coach is told why`() {
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                graduationRequirement = "Run a 5K in 24:59 or faster.",
                recentRuns = oneRunWalkSession.recentRuns.map {
                    it.copy(runMode = "treadmill", distanceKm = null, fastest5kSeconds = null)
                }
            )
        )

        // Sent as an explicit null rather than left out: a field that is simply missing is a field
        // the model can read as an oversight, and this one is the whole of the evidence.
        assertTrue(prompt.contains("\"fastest5kSeconds\":null"))
        assertTrue(prompt.contains("\"distanceKm\":null"))
        assertTrue(prompt.contains("If fastest5kSeconds is null, set graduatedToNextStage to false"))
        // A treadmill Run has no distance to be given, an outdoor one does — so the run mode is sent
        // and the coach says which of the two it is looking at rather than guessing.
        assertTrue(prompt.contains("treadmill run with no distance recorded when runMode is 'treadmill'"))
        assertTrue(prompt.contains("\"runMode\":\"treadmill\""))
    }

    @Test
    fun `a requirement the data cannot answer is still refused`() {
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(graduationRequirement = "Run 10K at 5:00 /km.")
        )

        assertTrue(
            prompt.contains(
                "If the stage requirement asks for any other distance or pace that fastest5kSeconds " +
                    "does not answer, set graduatedToNextStage to false"
            )
        )
    }

    @Test
    fun `duration, average heart rate, distance and Stage all reach the coach`() {
        val prompt = buildEvaluationPrompt(oneRunWalkSession)

        assertTrue(prompt.contains("Base Builder"))
        assertTrue(prompt.contains("Complete run-walk sessions consistently"))
        assertTrue(prompt.contains("\"durationSeconds\":1800"))
        assertTrue(prompt.contains("\"avgHr\":125"))
        assertTrue(prompt.contains("\"sessionType\":\"Run/Walk\""))
        assertTrue(prompt.contains("\"runMode\":\"outdoor\""))
        assertTrue(prompt.contains("\"distanceKm\":5.4"))
    }

    @Test
    fun `the walk-break count is neither sent nor asked about`() {
        val prompt = buildEvaluationPrompt(
            AiTrainingContext(
                currentStageTitle = "Base Builder",
                graduationRequirement = "Complete run-walk sessions consistently",
                recentRuns = listOf(
                    AiRecentRun(
                        durationSeconds = 1800,
                        avgHr = 125,
                        sessionType = "Run/Walk",
                        timestamp = 1_742_000_000_000,
                        runMode = "outdoor",
                        distanceKm = 5.4,
                        fastest5kSeconds = 1620
                    )
                )
            )
        )

        assertFalse(prompt.contains("walkBreak", ignoreCase = true))
        assertFalse(prompt.contains("HR-triggered", ignoreCase = true))
    }

    @Test
    fun `the coach is told what the runner is carrying, and what the weeks behind it came to`() {
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                fitnessAndForm = AiFitnessAndForm(
                    fitness = 42,
                    fatigue = 61,
                    form = -19,
                    verdict = FormVerdict.FATIGUED,
                    weeklyEffortScores = listOf(210, null, 340, 120),
                    todaysRunIsInTheNumbers = true
                )
            )
        )

        assertTrue(prompt.contains("Fitness 42, Fatigue 61, Form -19 (fatigued)."))
        assertTrue(prompt.contains("210, not measured, 340, 120."))
        // Said in the prompt rather than left to the model's own idea of the bands.
        assertTrue(prompt.contains("+10 is fresh, below -10 is fatigued"))
        // A runner who began the day fresh can have finished the Run carrying more than they have
        // absorbed, so the prescription answers to the pair today's Run moved, not to Form.
        assertTrue(prompt.contains("Fatigue above Fitness is a runner to hold, whatever Form reads."))
        // Fatigue buys a hold, not a lighter day: the #170 floor discards a lighter main set, so a
        // coach told to ease off would promise one the runner never gets.
        assertTrue(prompt.contains("When they are fatigued the answer is to hold"))
        assertTrue(prompt.contains("Never promise them a lighter, shorter or easier next run."))
        // The fence: a tired week must not cost a runner a Stage they have already earned.
        assertTrue(prompt.contains("These numbers must never change graduatedToNextStage."))
    }

    @Test
    fun `the coach is told Form is yesterday's pair, not the difference of the two numbers sent`() {
        // The real triple from the #66 device test: 10 - 27 is -17, and Form was -18. Form is read
        // before the day's training lands, so it is yesterday's answer — the three numbers do not
        // subtract, and a coach told they do would trust its own arithmetic over the Progress screen.
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                fitnessAndForm = AiFitnessAndForm(
                    fitness = 10,
                    fatigue = 27,
                    form = -18,
                    verdict = FormVerdict.FATIGUED,
                    weeklyEffortScores = listOf(47, 224, 199, 66),
                    todaysRunIsInTheNumbers = true
                )
            )
        )

        assertTrue(prompt.contains("Form is how fresh they were at the start of today"))
        assertTrue(prompt.contains("will not equal their difference"))
        // "The start of today", not "before today's run": a Run begun at 23:40 banks its effort on
        // the day it started, so it is already inside the pair today's Form is read from.
        assertFalse(prompt.contains("before today's run"))
        // The verdict is read off the raw Form and the figures are rounded, exactly as the Progress
        // screen pairs them — so a raw 10.2 prints "10 (fresh)" against a stated line of +10.
        assertTrue(prompt.contains("rounded to whole points"))
        // The curves are weighted, not flat means — the wording the Progress screen's own model uses.
        assertTrue(prompt.contains("weighted so the recent days count for most"))
        // And the claim the fix removes must not creep back.
        assertFalse(prompt.contains("Form is Fitness minus Fatigue"))
    }

    @Test
    fun `a Run that heard no beats is named as missing from the numbers, not left as rest`() {
        // No heart rate is no Effort Score, so the curves never see the Run — and a hard strapless
        // hour that reads as an hour of rest is the one reading that buys a harder next Run.
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                fitnessAndForm = AiFitnessAndForm(
                    fitness = 30,
                    fatigue = 12,
                    form = 18,
                    verdict = FormVerdict.FRESH,
                    weeklyEffortScores = listOf(210, 120),
                    todaysRunIsInTheNumbers = false
                )
            )
        )

        assertTrue(prompt.contains("none of the three numbers above contain it"))
        assertTrue(prompt.contains("Treat today's cost as unmeasured rather than as nothing"))
        assertTrue(prompt.contains("do not prescribe a harder next run on the strength of them"))
    }

    @Test
    fun `a Run with a Score says nothing about being missing from the numbers`() {
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                fitnessAndForm = AiFitnessAndForm(
                    fitness = 30,
                    fatigue = 12,
                    form = 18,
                    verdict = FormVerdict.FRESH,
                    weeklyEffortScores = listOf(210, 120),
                    todaysRunIsInTheNumbers = true
                )
            )
        )

        assertFalse(prompt.contains("recorded no heart rate, so it has no Effort Score"))
    }

    @Test
    fun `a week holding both measured and strapless Runs is told as a floor, not a total`() {
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                fitnessAndForm = AiFitnessAndForm(
                    fitness = 30,
                    fatigue = 12,
                    form = 18,
                    verdict = FormVerdict.FRESH,
                    weeklyEffortScores = listOf(210, 120),
                    todaysRunIsInTheNumbers = true
                )
            )
        )

        // A week's number counts only what wore a Strap, so a mixed week understates itself — said
        // outright, because a coach reading it as the whole week prescribes on a week that was bigger.
        assertTrue(prompt.contains("counts only the runs that recorded heart rate"))
        assertTrue(prompt.contains("a floor under what was actually run, never a ceiling"))
    }

    @Test
    fun `with no scored history the coach is told nothing about fatigue at all`() {
        val prompt = buildEvaluationPrompt(oneRunWalkSession)

        listOf("Fitness", "Fatigue", "Form ", "Effort Score", "fresh").forEach { word ->
            assertFalse("prompt mentions $word with no scored history", prompt.contains(word))
        }
    }

    @Test
    fun `a week nobody measured is named as such and never sent as a zero`() {
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                fitnessAndForm = AiFitnessAndForm(
                    fitness = 30,
                    fatigue = 12,
                    form = 18,
                    verdict = FormVerdict.FRESH,
                    weeklyEffortScores = listOf(null, null),
                    todaysRunIsInTheNumbers = true
                )
            )
        )

        assertTrue(prompt.contains("not measured, not measured."))
        // A week nobody measured is told apart from a week of rest, which is sent as a 0 — opposite
        // news for a coach reading fatigue.
        assertTrue(prompt.contains("0 is a week of rest"))
        assertTrue(prompt.contains("training you cannot see"))
    }

    @Test
    fun `the schema offers the target zone as the coach's, and only as an option`() {
        val prompt = buildEvaluationPrompt(
            AiTrainingContext(
                currentStageTitle = "Base Builder",
                graduationRequirement = "Complete run-walk sessions consistently",
                recentRuns = emptyList()
            )
        )

        assertTrue(prompt.contains("\"nextTargetZone\": Int (optional, 1-5)"))
        assertTrue(prompt.contains("Omit nextTargetZone to leave the workout's own target zone alone."))
    }
}
