package com.example.runningapp.data

import android.util.Log
import com.example.runningapp.BuildConfig
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
    appendLine("Current stage title: ${context.currentStageTitle}")
    appendLine("Recent runs (JSON):")
    appendLine(gson.toJson(context.recentRuns))
}
