package com.example.runningapp.data

import android.util.Log
import com.example.runningapp.BuildConfig
import com.example.runningapp.WorkoutTemplate
import com.google.ai.client.generativeai.GenerativeModel
import com.google.gson.Gson
import com.google.gson.GsonBuilder

data class AiCoachResponse(
    val nextRunDurationSeconds: Int,
    val nextWalkDurationSeconds: Int,
    val nextRepeats: Int,
    /**
     * The zone the next run should aim at, so the coach can say "today is easier, drop to Z2" and
     * not only "run for longer" (#113). Null — including when the model omits the field — means it
     * left the target alone, and the workout's own zone stands.
     */
    val nextTargetZone: Int? = null,
    val graduatedToNextStage: Boolean,
    val coachMessage: String
)

class AiCoachClient {

    private val gson = Gson()
    private val apiKey = BuildConfig.GEMINI_API_KEY
    private val model = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = apiKey
    )

    /**
     * What the coach wants run next, or null when it could not be asked.
     *
     * Null rather than a stand-in response: the fallback this replaces prescribed 60s/30s × 6 —
     * numbers no coach chose — under a message saying the coach was unavailable. Harmless-looking
     * while those numbers were settings nobody read as a promise; not harmless now that they are a
     * prescription the run and the card both follow (#113).
     *
     * Null means the coach said nothing, not that it withdrew what it said before: a standing
     * prescription is left alone and keeps applying until something supersedes it or it ages out
     * (see `COACH_PRESCRIPTION_MAX_AGE_DAYS`). Erasing it here would let one unreachable evaluation
     * — a gym with no signal — throw a runner back to the plan's generic numbers, discarding the
     * last thing the coach actually said. With no standing prescription, the plan runs as written.
     */
    suspend fun evaluateProgress(context: AiTrainingContext): AiCoachResponse? {
        require(apiKey.isNotBlank()) { "Gemini API key is missing" }

        val prompt = buildEvaluationPrompt(context)

        return try {
            val response = model.generateContent(prompt)
            val cleanJson = response.text
                ?.replace("```json", "")
                ?.replace("```", "")
                ?.trim()
                ?: "{}"
            gson.fromJson(cleanJson, AiCoachResponse::class.java)
        } catch (e: Exception) {
            Log.e("AiCoach", "Failed to evaluate progress with Gemini", e)
            null
        }
    }
}

/**
 * Nulls are written out rather than dropped, which is Gson's default. A run with no measured 5K has
 * to say so: a field that is simply absent reads as an oversight, and this one is the whole of the
 * evidence a distance-and-time requirement is judged on (#182).
 */
private val recentRunsGson: Gson = GsonBuilder().serializeNulls().create()

internal fun buildEvaluationPrompt(
    context: AiTrainingContext,
    gson: Gson = recentRunsGson
): String = buildString {
    appendLine("You are an expert running coach.")
    appendLine("Analyze the user's last 3 runs against their current stage requirement: ${context.graduationRequirement}.")
    appendLine("The provided recent runs include timestamps. The run with the most recent timestamp is the workout the user JUST completed today.")
    appendLine("Base your coachMessage feedback primarily on how they performed in today's run. Make it feel like a post-run debrief.")
    appendLine("Look at the older runs to establish trends (e.g., is their heart rate consistently improving?).")
    appendLine("The recent runs data includes a 'sessionType' ('Run/Walk' for a structured plan workout, or 'Open Run' for an unplanned open-ended run).")
    appendLine("CRITICAL RULE: An 'Open Run' is an unplanned run with no interval structure. Do NOT set graduatedToNextStage to true based on Open Run sessions. Progression ONLY happens via 'Run/Walk' sessions.")
    // No Interval-quality metric is sent, and none is described here (#168) — see AiRecentRun.
    appendLine("Judge a duration-and-heart-rate requirement from the run's duration and average heart rate.")
    // The evidence a 5K-in-a-time requirement needs, and the rule that stops it being answered from
    // anything else (#182). durationSeconds is the whole run — an eight-minute warm-up walk and a
    // three-minute cool-down are inside it — so judging a 5K by it fails both ways: a 26-minute run
    // that covered 3K reads as a pass, and a genuine 24-minute 5K reads as 35 minutes and fails.
    // One wrong true advances the stored stage on the spot, so fastest5kSeconds is the only field
    // allowed to answer, and its absence is stated as an absence rather than left to inference.
    appendLine("The recent runs data also includes 'runMode' ('outdoor' for a GPS-recorded run, 'treadmill' for one with no GPS), 'distanceKm' (how far the run went — measured by GPS outdoors, or read off the treadmill console and stated by the runner; null when the run has no distance at all), and 'fastest5kSeconds' (the quickest continuous 5K inside that run, measured from its GPS track, null when the run never covered 5K in one continuous stretch of recording).")
    appendLine("durationSeconds is the whole run including its warm-up and cool-down, so on a GPS-recorded run it is NOT a 5K time and must never be compared to one; the single exception is the treadmill case set out below.")
    appendLine("CRITICAL RULE: If the stage requirement asks for a 5K in a time, judge it ONLY from fastest5kSeconds, EXCEPT in the one case in the next rule. If fastest5kSeconds is null, set graduatedToNextStage to false, and say in coachMessage that this run does not contain a measured 5K — because it was a treadmill run with no distance recorded when runMode is 'treadmill', or because the run did not cover a continuous 5K otherwise.")
    // The one thing a treadmill Run's two numbers can settle, and the fence around it (#231, ADR
    // 0008). A stated distance plus a whole-run duration is a time over the whole run and nothing
    // shorter: a 6 km run in 30:00 may hold a sub-25 5K and may not, and a coach ruling on it either
    // way would be deriving a best effort from an average pace — the derivation the ADR rejects,
    // with evaluateAndAdjustPlan advancing the stored Stage the moment it answered yes.
    appendLine("EXCEPTION, for a treadmill run only (runMode 'treadmill') with a non-null distanceKm: its distanceKm and durationSeconds establish a time for the WHOLE run and nothing shorter. If the requirement asks for exactly that distance, you may judge it from durationSeconds — a 5.0 km treadmill run in 24:30 meets 'a 5K in 24:59'.")
    appendLine("CRITICAL RULE: If that treadmill run went FURTHER than the requirement's distance, you cannot tell how fast the requirement's distance alone was covered: set graduatedToNextStage to false and say in coachMessage that the run was longer than the requirement, so its overall time does not establish the requirement's time. Never divide a distance by a duration to estimate a pace or a shorter-distance time.")
    appendLine("CRITICAL RULE: If the stage requirement asks for any other distance or pace that fastest5kSeconds does not answer, set graduatedToNextStage to false and say in coachMessage that you cannot confirm that requirement from this run's data.")
    appendLine("Use this combined context to generate the exact intervals for their NEXT run.")
    appendLine("If they meet the requirement easily, and the data can actually establish that they met it, set graduatedToNextStage to true.")
    appendLine("Otherwise, adjust their run/walk intervals safely to build endurance.")
    appendLine("You may also set nextTargetZone (1-5) to prescribe an easier or harder target for that run.")
    appendLine("Omit nextTargetZone to leave the workout's own target zone alone.")
    appendLine("Return ONLY a valid, raw JSON object.")
    appendLine("Do not include markdown formatting like ```json.")
    appendLine("Your response must be parseable directly into this schema:")
    appendLine("{")
    appendLine("  \"nextRunDurationSeconds\": Int,")
    appendLine("  \"nextWalkDurationSeconds\": Int,")
    appendLine("  \"nextRepeats\": Int,")
    appendLine("  \"nextTargetZone\": Int (optional, 1-5),")
    appendLine("  \"graduatedToNextStage\": Boolean,")
    appendLine("  \"coachMessage\": String")
    appendLine("}")
    context.stageWorkout?.let { appendStageWorkout(it) }
    context.fitnessAndForm?.let { appendFitnessAndForm(it) }
    appendLine("Current stage title: ${context.currentStageTitle}")
    appendLine("Recent runs (JSON):")
    appendLine(gson.toJson(context.recentRuns))
}

/**
 * The Workout the coach's three numbers replace, told to it before it picks them (#246).
 *
 * Both clamps are measured against this Workout — the floor discards anything asking for less work
 * than it (#170) and the ceiling trims the other side — so a coach that never saw it could only
 * land inside that band by luck. Shown, "keep this as it is" and "add a little to this" become
 * things it can say on purpose, rather than guesses the clamps clean up after.
 *
 * The floor is stated in the same two measures the code applies (`clearedBy`): total seconds and
 * running seconds, both of which have to clear. A one-line "at least as much work" would leave the
 * coach free to satisfy it the way the floor exists to refuse — six 30s runs padded out with long
 * walks matching a six-by-three-minute Workout second for second on a sixth of the running.
 *
 * The warm-up and cool-down are deliberately not sent. The schema has no field for either, so they
 * are two numbers the coach could not act on — and the one rule that counts them, the 110% ceiling,
 * is not described here at all. Handed a figure with no rule attached to it, a model is left to
 * invent one.
 *
 * Last, and said as hard as the fatigue block says its own version: this is the plan's intention,
 * never a record of anything the runner did. A Workout read as evidence would graduate a Stage on
 * the strength of numbers nobody ran — and a graduation cannot be taken back.
 */
private fun StringBuilder.appendStageWorkout(workout: WorkoutTemplate) {
    appendLine(
        "The stage's own workout for this kind of run, which is what your intervals adjust: " +
            "${workout.runDurationSeconds}s of running then ${workout.walkDurationSeconds}s of " +
            "walking, ${workout.totalRepeats} times, targeting Zone ${workout.targetZone}."
    )
    appendLine(
        "nextRunDurationSeconds, nextWalkDurationSeconds and nextRepeats replace exactly those " +
            "three numbers, so returning those same three numbers is how you say to keep this " +
            "workout as it is."
    )
    appendLine(
        "That workout is a floor. Prescribe at least as much work as that workout, measured two " +
            "ways: repeats × (run + walk) seconds, and repeats × run seconds. If either comes to " +
            "less than the workout's own, your intervals are discarded and the workout's three " +
            "numbers stand — so a shorter run interval cannot be bought back with a longer walk."
    )
    appendLine(
        "CRITICAL RULE: this workout is the plan's intention, not a record of anything the runner " +
            "did. It is what you prescribe against, never evidence about any run, and must never " +
            "change graduatedToNextStage — that is judged from the recent runs alone."
    )
}

/**
 * What the runner is carrying, told to the coach so the next prescription can answer it (#66).
 *
 * The numbers are explained, not just stated: a bare "Form -14" is a figure from someone else's
 * model, while the sentence below says what it was measured over and where its lines are, so the
 * coach reads the same meaning the runner does on the Progress screen.
 *
 * Intervals only, and said so twice over. Graduation is a judgement about evidence — did this Run
 * meet the requirement — and a tired week is not evidence about a Run. Left unfenced, a model given
 * a fatigue reading will hold a runner back from a Stage they have already earned, which is the one
 * decision here that writes itself into the stored plan.
 */
private fun StringBuilder.appendFitnessAndForm(state: AiFitnessAndForm) {
    appendLine(
        "The runner's current training state, from the Effort Scores of their past runs: " +
            "Fitness ${state.fitness}, Fatigue ${state.fatigue}, Form ${state.form} (${state.verdict.word})."
    )
    appendLine(
        "Fitness is their Effort Scores averaged over the last 42 days and Fatigue the same over " +
            "the last 7, both weighted so the recent days count for most, so Fatigue above Fitness " +
            "means they are carrying more work than they have absorbed."
    )
    // Said outright, because the three numbers above do not satisfy it: Form is read before the
    // day's effort is added, so it is the pair as today opened and not as it closes. A model told
    // "Form is Fitness minus Fatigue" and handed 10, 27 and -18 has been given arithmetic that does
    // not hold, and the cheapest way for it to resolve that is to trust its own subtraction over
    // the number the runner is looking at.
    //
    // Said as "the start of today" rather than "before today's run", which is the same sentence
    // only until a Run crosses midnight: effort is banked on the day a Run *started*, so one begun
    // at 23:40 is already inside the pair today's Form is read from.
    //
    // The rounding is owned up to for the same reason. Verdict and figure are the Progress screen's
    // own pairing — the word off the raw Form, the number rounded — so a raw 10.2 prints "10
    // (fresh)" against a stated line of +10. Matching the screen is the point; leaving the coach to
    // reconcile it is not.
    appendLine(
        "Form is how fresh they were at the start of today: Fitness less Fatigue as the pair stood " +
            "before any of today's training was counted. The two numbers above are that same pair " +
            "after it, so Form will not equal their difference — use the Form figure as given. " +
            "Above +10 is fresh, below -10 is fatigued, and between the two is neutral. The word " +
            "in brackets is that verdict, read off the unrounded Form; all three numbers are " +
            "rounded to whole points, so one can print a point the wrong side of a line."
    )
    // Skipped rather than written empty on the case that should not arise — a scored run is a
    // finished run, so a curve exists only where a week does. An empty list here would otherwise
    // print a sentence promising totals and then listing none.
    if (state.weeklyEffortScores.isNotEmpty()) {
        appendLine(
            "Weekly Effort Score totals, oldest week first, the last one being the week in progress: " +
                state.weeklyEffortScores.joinToString { it?.toString() ?: "not measured" } + "."
        )
        appendLine(
            "0 is a week of rest — no running, or none hard enough to score. \"not measured\" is a " +
                "week that was run with no heart rate recorded, so it is training you cannot see " +
                "rather than rest. A week's number counts only the runs that recorded heart rate, " +
                "so a week holding both kinds is a floor under what was actually run, never a " +
                "ceiling."
        )
    }
    // The case where the numbers understate the day rather than lag it: the Run is outside them
    // altogether. Left unsaid, a hard hour reads to the coach as an hour of rest — the one reading
    // that turns a hard day into permission to prescribe a harder one.
    //
    // Why it is outside them is not said, because there is more than one why — no heart rate to
    // score, or a date the curves declined — and the coach's move is the same for both. A sentence
    // naming one cause would be wrong about the other in front of a JSON block that contradicts it:
    // a future-dated Run is sent with its average heart rate showing.
    if (!state.todaysRunIsInTheNumbers) {
        appendLine(
            "Today's run is not inside the three numbers above: they are the load as it stood " +
                "before it. Treat today's cost as unmeasured rather than as nothing, and do not " +
                "prescribe a harder next run on the strength of them."
        )
    }
    // Which reading governs, said because the block prints two of them. Form is where today began,
    // and a runner who began it fresh can have finished the Run carrying more than they have
    // absorbed — so the pair the prescription answers to is the one nearest to now. How near that
    // is depends on the Run: it is after it wherever there was a Score to move it, and no later
    // than the day before otherwise, which is the sentence's own caveat rather than the one above
    // repeated. Two tellings of the same timing, free to contradict each other, is exactly what the
    // rest of this block exists to avoid.
    appendLine(
        "Ask Fitness and Fatigue, not Form, what the next run should be: " +
            if (state.todaysRunIsInTheNumbers) {
                "those two are after today's run and Form is where today started."
            } else {
                "of the three they are the closest to now, though as said above none of them " +
                    "contains today's run."
            } + " Fatigue above Fitness is a runner to hold, whatever Form reads."
    )
    // Fatigue buys a hold, not a lighter day, because this app has no lever for one. A prescription
    // under the stage's own workout is discarded (#170), and a lower target zone is no way round it
    // either: Zone 1 is snapped back to Zone 2 (#117) and every workout the coach adjusts already
    // targets Zone 2. So the instruction asks for the hold and, more importantly, for it to be said
    // — an unqualified "ease off" would leave the runner reading a promise of a lighter day over a
    // main set the floor had just put back to the stage's.
    //
    // What the message may claim is fenced to what this side can keep. The floor guarantees the
    // work will not be lighter and nothing here guarantees it will not be heavier, so the coach is
    // asked not to add rather than to announce a plan unchanged — a promise only a hold enforced on
    // the write could make, which is #248.
    appendLine(
        "Let this shape the next run. When they are fresh you may prescribe more: longer intervals " +
            "or more repeats. When they are fatigued the answer is to hold, because the stage's " +
            "own workout is a floor — a prescription asking for less work than it is discarded and " +
            "the stage's numbers stand — so do not try to prescribe a lighter one. Add nothing to " +
            "it either, and say in coachMessage that this is not a week to be adding work to while " +
            "they absorb what they are carrying. Never promise them a lighter, shorter or easier " +
            "next run, and never promise them a specific set of intervals."
    )
    appendLine(
        "CRITICAL RULE: These numbers must never change graduatedToNextStage. Graduation is judged " +
            "only from the recent runs' evidence against the stage requirement, exactly as above."
    )
}
