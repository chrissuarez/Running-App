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
     * prescription the run and the card both follow (#113). A coach that could not be reached says
     * nothing, and the plan runs as written.
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
    appendLine("Run/Walk sessions may include 'runWalkMetrics' with these nullable fields:")
    appendLine("- severeBreakdownRatePercent")
    appendLine("- poorToleranceRatePercent")
    appendLine("- strainedCompletionRatePercent")
    appendLine("- strongCompletionRatePercent")
    appendLine("- cleanIntervalRatePercent")
    appendLine("- hrDriftSlopeBpmPerInterval")
    appendLine("- intervalCompletionRatioPercent")
    appendLine("- avgRecoverySecondsAfterTrigger")
    appendLine("- avgHrAtTrigger")
    appendLine("Treat null metric values as unknown evidence, never as zero.")
    appendLine("CRITICAL RULE: An 'Open Run' is an unplanned run with no interval structure. Do NOT set graduatedToNextStage to true based on Open Run sessions. Progression ONLY happens via 'Run/Walk' sessions.")
    appendLine("Adaptation guidance for Run/Walk sessions:")
    appendLine("- severeBreakdownRatePercent means the runner broke down almost immediately (<30% completion). This is a red-flag metric, not a success metric.")
    appendLine("- Judge overall interval quality primarily from intervalCompletionRatioPercent, strongCompletionRatePercent, cleanIntervalRatePercent, and how much of the session sat in poorToleranceRatePercent or strainedCompletionRatePercent.")
    appendLine("- Repeated severe breakdown or poor tolerance suggests the run interval is too aggressive; consider shortening run duration and/or increasing walk support.")
    appendLine("- Strong performance means most intervals are strongly completed, not merely that severe breakdown is zero.")
    appendLine("- Do not describe a session as perfect, stellar, or textbook if there were frequent HR-triggered walk breaks, low cleanIntervalRatePercent, or intervalCompletionRatioPercent below 90.")
    appendLine("- If clean performance is strong with stable or improving drift/recovery, consider safely extending run duration.")
    appendLine("- Rising HR drift across intervals with slower recovery suggests fatigue; prefer conservative progression over progression jumps.")
    appendLine("Use this combined context to generate the exact intervals for their NEXT run.")
    appendLine("If they meet the requirement easily, set graduatedToNextStage to true.")
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
