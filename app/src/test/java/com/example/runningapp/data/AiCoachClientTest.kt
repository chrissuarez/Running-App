package com.example.runningapp.data

import com.example.runningapp.RunType
import com.example.runningapp.WorkoutTemplate
import com.example.runningapp.training.FormVerdict
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCoachClientTest {

    /** Weeks every Run of which was measured — the plain case, so a test can say scores alone. */
    private fun efforts(vararg scores: Int?) = scores.map { AiWeeklyEffort(it, partlyMeasured = false) }

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

    private val longRunWorkout = WorkoutTemplate(
        id = "base_long",
        title = "Long run",
        targetZone = 2,
        runDurationSeconds = 180,
        walkDurationSeconds = 60,
        totalRepeats = 6,
        runType = RunType.LONG
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
    fun `the coach is told the runs it has are the Stage's own, and that there may be none`() {
        // #234: the Runs of an earlier Stage are not in the list, so a Stage just moved into shows
        // one Run or none. Told nothing, a coach asked to read "the last 3 runs" would take that
        // for a runner who had stopped — and, worse, could take an old Stage's work for this one's.
        val prompt = buildEvaluationPrompt(oneRunWalkSession.copy(recentRuns = emptyList()))

        assertTrue(prompt.contains("only the runs recorded under the current stage"))
        assertTrue(
            prompt.contains("Runs from an earlier stage are not shown to you and are not evidence for this one")
        )
        assertTrue(
            prompt.contains("If no recent runs are provided, there is no evidence for this stage's requirement")
        )
        assertTrue(prompt.contains("set graduatedToNextStage to false, and say in coachMessage that this stage is only just beginning"))
    }

    @Test
    fun `the coach is made to name the Runs it graduated on`() {
        // #287: every other rule tells the coach what may not be evidence, which leaves a reply
        // that is true about the Walk's numbers while a failed structured Run sits in the same
        // list. Made to name the runs the requirement is met by, the reply carries something the
        // code can check — and the schema has to offer the field, or there is nowhere to say it.
        val prompt = buildEvaluationPrompt(oneRunWalkSession)

        assertTrue(
            prompt.contains(
                "you MUST also set graduationEvidenceRunTimestamps to the list of exact " +
                    "'timestamp' values, copied digit for digit, of the runs above that your " +
                    "decision rests on"
            )
        )
        assertTrue(prompt.contains("a 'Walk' or an 'Open Run' can never be named"))
        assertTrue(
            prompt.contains(
                "a run that meets the requirement standing beside a different run that does not is " +
                    "not evidence, only the run that met it is"
            )
        )
        assertTrue(prompt.contains("\"graduationEvidenceRunTimestamps\": [Long]"))
    }

    @Test
    fun `a requirement no single Run can meet is named by the several that met it`() {
        // #287 round 2: the first stage of the beginner plan asks for "4 weeks of consistent Zone 2
        // training", which no one run has ever met. Told to name exactly one run, a coach obeying
        // the rule could never graduate that stage — the plan would stop on its first step. So the
        // rule asks for the runs the evidence is actually made of, however many that is.
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                graduationRequirement = "Complete 4 weeks of consistent Zone 2 training."
            )
        )

        assertTrue(
            prompt.contains(
                "one run where the requirement is met by one, several where it takes several"
            )
        )
        // And the refusal is still available: naming nothing is what a coach that cannot point at a
        // qualifying run has to do, rather than naming a run it is not relying on.
        assertTrue(
            prompt.contains(
                "If not one 'Run/Walk' run above is something your decision rests on, set " +
                    "graduatedToNextStage to false"
            )
        )
    }

    @Test
    fun `naming the evidence is not asked to reach further than the three runs shown`() {
        // The dead end one step out from "name exactly one" (#287, review round 2): at most three
        // runs are ever sent, four weeks of training is more than three runs, and a coach reading
        // "name what you are relying on" as "account for every week" would refuse a stage plainly
        // earned. Three runs is what this app has always judged a graduation on, so the rule says
        // so outright rather than leaving the coach to infer a standard nothing can meet.
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                graduationRequirement = "Complete 4 weeks of consistent Zone 2 training."
            )
        )

        assertTrue(
            prompt.contains(
                "there are at most three of them, and a requirement covering more training than " +
                    "they show is still judged on them"
            )
        )
        // And the fence that keeps the weekly totals out of the judgement is still the reason it
        // has to be the runs: they are the only evidence, and the only thing nameable.
        assertTrue(prompt.contains("These recent runs are the only evidence there is and the only thing you may name"))
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
                    weeklyEfforts = efforts(210, null, 340, 120),
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
        assertTrue(prompt.contains("When Fatigue is above Fitness the next run's intervals are the stage's own workout"))
        assertTrue(prompt.contains("Never promise them a lighter, shorter or easier run than that workout"))
        // The hold is stated as an outcome, because the write now keeps it (#248) — which is what
        // lets the coach tell the runner the intervals are unchanged.
        assertTrue(prompt.contains("whatever three numbers you return, that workout's own are what the runner is given"))
        assertTrue(prompt.contains("the next run's intervals are the stage's workout unchanged"))
        // Said no wider than the write keeps: the hold takes the three durations and leaves the
        // target zone alone, so the zone is asked for rather than promised.
        assertTrue(prompt.contains("Do not raise nextTargetZone on a runner you are holding"))
        assertFalse(prompt.contains("the next run is the stage's workout unchanged"))
        // A graduation clears the prescriptions, so there is no held workout to have been unchanged.
        assertTrue(prompt.contains("If you are graduating them, say nothing about holding the workout"))
        // Everywhere else nothing may be promised that this side cannot keep: the 110% ceiling can
        // still trim a harder prescription on its way through.
        assertTrue(prompt.contains("When they are not carrying that load, never promise a specific set of intervals."))
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
                    weeklyEfforts = efforts(47, 224, 199, 66),
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
    fun `a Run outside the numbers is named as missing from them, not left as rest`() {
        // A hard hour the curves never saw, reading to the coach as an hour of rest, is the one
        // reading that buys a harder next Run. Why they never saw it is not said — no beats to
        // score and a date they declined are the same news, and the same move.
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                fitnessAndForm = AiFitnessAndForm(
                    fitness = 30,
                    fatigue = 12,
                    form = 18,
                    verdict = FormVerdict.FRESH,
                    weeklyEfforts = efforts(210, 120),
                    todaysRunIsInTheNumbers = false
                )
            )
        )

        assertTrue(prompt.contains("Today's run is not inside the three numbers above"))
        // Why it is outside them is not claimed: a future-dated Run is excluded too, and is sent
        // with its average heart rate showing in the JSON below.
        assertFalse(prompt.contains("recorded no heart rate, so it has no Effort Score"))
        assertTrue(prompt.contains("Treat today's cost as unmeasured rather than as nothing"))
        assertTrue(prompt.contains("do not prescribe a harder next run on the strength of them"))
        // And the timing is told once: the line naming which reading governs must not turn round and
        // call the same pair post-run.
        assertFalse(prompt.contains("those two are after today's run"))
    }

    @Test
    fun `a Run the numbers do contain says nothing about being missing from them`() {
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                fitnessAndForm = AiFitnessAndForm(
                    fitness = 30,
                    fatigue = 12,
                    form = 18,
                    verdict = FormVerdict.FRESH,
                    weeklyEfforts = efforts(210, 120),
                    todaysRunIsInTheNumbers = true
                )
            )
        )

        assertFalse(prompt.contains("Today's run is not inside the three numbers above"))
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
                    weeklyEfforts = efforts(210, 120),
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
                    weeklyEfforts = efforts(null, null),
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
    fun `a week measured in part is marked on the number it belongs to`() {
        // The number is the scored runs' alone, and beside three whole weeks it reads as the light
        // week the runner never had (#247).
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                fitnessAndForm = AiFitnessAndForm(
                    fitness = 30,
                    fatigue = 12,
                    form = 18,
                    verdict = FormVerdict.FRESH,
                    weeklyEfforts = listOf(
                        AiWeeklyEffort(210, partlyMeasured = false),
                        AiWeeklyEffort(140, partlyMeasured = true),
                        AiWeeklyEffort(null, partlyMeasured = false),
                        AiWeeklyEffort(0, partlyMeasured = false),
                    ),
                    todaysRunIsInTheNumbers = true
                )
            )
        )

        assertTrue(prompt.contains("210, 140 (part not measured), not measured, 0."))
        // Marked, and said what the mark means — a week harder than its number, never a light one.
        assertTrue(prompt.contains("those weeks are the ones marked \"part not measured\""))
        assertTrue(prompt.contains("harder than their number"))
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

    @Test
    fun `the coach is shown the Workout its numbers replace`() {
        // Without this the coach adjusts intervals it has never seen (#246), and the floor (#170)
        // and the ceiling measure the answer against numbers it was never told.
        val prompt = buildEvaluationPrompt(oneRunWalkSession.copy(stageWorkout = longRunWorkout))

        assertTrue(prompt.contains("180s of running then 60s of walking, 6 times, targeting Zone 2"))
    }

    @Test
    fun `the envelope is not sent, because there is no field to answer it with`() {
        // Warm-up and cool-down are the Workout's own and the schema has no field for either, so a
        // coach handed them has two numbers and no rule attached to them. Its own numbers, not the
        // WorkoutTemplate defaults, so a hard-coded 480 could not pass this.
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                stageWorkout = longRunWorkout.copy(warmUpSeconds = 900, coolDownSeconds = 240)
            )
        )

        assertFalse(prompt.contains("900s"))
        assertFalse(prompt.contains("240s"))
    }

    @Test
    fun `keeping the Workout as it is is a sayable answer`() {
        val prompt = buildEvaluationPrompt(oneRunWalkSession.copy(stageWorkout = longRunWorkout))

        assertTrue(
            prompt.contains(
                "returning those same three numbers is how you say to keep this workout as it is"
            )
        )
    }

    @Test
    fun `the coach is told where the floor is, in the numbers it is measured in`() {
        val prompt = buildEvaluationPrompt(oneRunWalkSession.copy(stageWorkout = longRunWorkout))

        assertTrue(prompt.contains("at least as much work as that workout"))
        assertTrue(prompt.contains("discarded"))
    }

    @Test
    fun `the Workout is never evidence about a Run`() {
        // The one way this block could do harm: a plan's numbers read as something the runner did.
        // Graduation is judged from the recent runs alone (#246).
        val prompt = buildEvaluationPrompt(oneRunWalkSession.copy(stageWorkout = longRunWorkout))

        assertTrue(
            prompt.contains(
                "It is what you prescribe against, never evidence about any run"
            )
        )
        assertTrue(prompt.contains("must never change graduatedToNextStage"))
    }

    @Test
    fun `with no Workout attached the coach is told nothing about one`() {
        val prompt = buildEvaluationPrompt(oneRunWalkSession)

        assertFalse(prompt.contains("The stage's own workout for this kind of run"))
        assertFalse(prompt.contains("targeting Zone"))
        assertFalse(prompt.contains("keep this workout as it is"))
        assertFalse(prompt.contains("That workout is a floor"))
        assertFalse(prompt.contains("never evidence about any run"))
    }

    /**
     * The same reader [AiCoachClient.evaluateProgress] hands the model's text to. What is being
     * tested is not Gson but the shape of the answers a model actually sends when a list is asked
     * for, because the one that throws takes the whole reply with it (#287).
     */
    private fun parse(json: String): AiCoachResponse? =
        Gson().fromJson(json, AiCoachResponse::class.java)

    private val graduatingReply = """
        {"nextRunDurationSeconds":60,"nextWalkDurationSeconds":30,"nextRepeats":6,
         "graduatedToNextStage":true,%s"coachMessage":"Done."}
    """.trimIndent()

    @Test
    fun `several named timestamps are read as several`() {
        val response = parse(graduatingReply.format("\"graduationEvidenceRunTimestamps\":[1000,2000],"))

        assertEquals(listOf(1_000L, 2_000L), response?.graduationEvidenceRunTimestamps)
    }

    @Test
    fun `one named timestamp sent bare is read as a list of one`() {
        // A model asked for a list will sometimes send the value. Gson's own list reader throws on
        // it, and the throw does not land on this field — it lands on the whole parse, so the run
        // would get no debrief and no prescription at all. A worse answer than the refusal.
        val response = parse(graduatingReply.format("\"graduationEvidenceRunTimestamps\":1000,"))

        assertEquals(listOf(1_000L), response?.graduationEvidenceRunTimestamps)
    }

    @Test
    fun `a timestamp that is not a number names nothing`() {
        // Not "the two that could be read": a graduation resting on three runs, one of them
        // unreadable, is not a graduation resting on two. What could not be read was not named, and
        // the refusal is decided from null in evaluateAndAdjustPlan.
        val partly = parse(graduatingReply.format("\"graduationEvidenceRunTimestamps\":[1000,\"yesterday\"],"))
        val single = parse(graduatingReply.format("\"graduationEvidenceRunTimestamps\":\"yesterday\","))
        val object_ = parse(graduatingReply.format("\"graduationEvidenceRunTimestamps\":{\"run\":1000},"))

        assertNull(partly?.graduationEvidenceRunTimestamps)
        assertNull(single?.graduationEvidenceRunTimestamps)
        assertNull(object_?.graduationEvidenceRunTimestamps)
        // The reply itself survives all three — the debrief is still delivered, only the graduation
        // is refused.
        assertEquals("Done.", partly?.coachMessage)
    }

    @Test
    fun `the field being absent or empty or null names nothing`() {
        val absent = parse(graduatingReply.format(""))
        val empty = parse(graduatingReply.format("\"graduationEvidenceRunTimestamps\":[],"))
        val explicitNull = parse(graduatingReply.format("\"graduationEvidenceRunTimestamps\":null,"))

        assertNull(absent?.graduationEvidenceRunTimestamps)
        assertEquals(emptyList<Long>(), empty?.graduationEvidenceRunTimestamps)
        assertNull(explicitNull?.graduationEvidenceRunTimestamps)
    }
}
