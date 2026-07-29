package com.example.runningapp.data

import android.util.Log
import com.example.runningapp.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.gson.Gson

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

        val prompt = buildEvaluationPrompt(context, gson)

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

internal fun buildEvaluationPrompt(
    context: AiTrainingContext,
    gson: Gson = Gson()
): String = buildString {
    appendLine("You are an expert running coach.")
    appendLine("Analyze the user's last 3 runs against their current stage requirement: ${context.graduationRequirement}.")
    appendLine("The provided recent runs include timestamps. The run with the most recent timestamp is the workout the user JUST completed today.")
    appendLine("Base your coachMessage feedback primarily on how they performed in today's run. Make it feel like a post-run debrief.")
    appendLine("Look at the older runs to establish trends (e.g., is their heart rate consistently improving?).")
    appendLine("The recent runs data includes a 'sessionType' ('Run/Walk' for a structured plan workout, or 'Open Run' for an unplanned open-ended run).")
    appendLine("CRITICAL RULE: An 'Open Run' is an unplanned run with no interval structure. Do NOT set graduatedToNextStage to true based on Open Run sessions. Progression ONLY happens via 'Run/Walk' sessions.")
    // No Interval-quality metric is sent, and none is described here (#168) — see AiRecentRun.
    appendLine("Judge the run from its duration and average heart rate against the stage requirement.")
    // Duration is the whole run, warm-up and cool-down included, and no distance or pace is sent at
    // all — so a requirement stated as a distance in a time cannot be checked from this data. The
    // model would otherwise read a 26-minute run that covered 3K as a 5K pass, and one wrong true
    // advances the stored stage on the spot. #182 sends the evidence; this stops the guess.
    appendLine("The recent runs data carries no distance or pace, and durationSeconds is the whole run including its warm-up and cool-down.")
    appendLine("CRITICAL RULE: If the stage requirement asks for a distance, a pace, or a distance within a time, you CANNOT verify it from this data. Set graduatedToNextStage to false and say in coachMessage that the run's distance is not something this app can confirm yet.")
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
