package com.example.runningapp.data

import android.util.Log
import com.example.runningapp.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.gson.Gson

data class AiCoachResponse(
    val nextRunDurationSeconds: Int,
    val nextWalkDurationSeconds: Int,
    val nextRepeats: Int,
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

    suspend fun evaluateProgress(context: AiTrainingContext): AiCoachResponse {
        require(apiKey.isNotBlank()) { "Gemini API key is missing" }

        val prompt = buildString {
            appendLine("You are an expert running coach.")
            appendLine("Analyze the user's last 3 runs against their current stage requirement: ${context.graduationRequirement}.")
            appendLine("The provided recent runs include timestamps. The run with the most recent timestamp is the workout the user JUST completed today.")
            appendLine("Base your coachMessage feedback primarily on how they performed in today's run. Make it feel like a post-run debrief.")
            appendLine("Look at the older runs to establish trends (e.g., is their heart rate consistently improving?).")
            appendLine("Use this combined context to generate the exact intervals for their NEXT run.")
            appendLine("If they meet the requirement easily, set graduatedToNextStage to true.")
            appendLine("Otherwise, adjust their run/walk intervals safely to build endurance.")
            appendLine("Return ONLY a valid, raw JSON object.")
            appendLine("Do not include markdown formatting like ```json.")
            appendLine("Your response must be parseable directly into this schema:")
            appendLine("{")
            appendLine("  \"nextRunDurationSeconds\": Int,")
            appendLine("  \"nextWalkDurationSeconds\": Int,")
            appendLine("  \"nextRepeats\": Int,")
            appendLine("  \"graduatedToNextStage\": Boolean,")
            appendLine("  \"coachMessage\": String")
            appendLine("}")
            appendLine("Current stage title: ${context.currentStageTitle}")
            appendLine("Recent runs (JSON):")
            appendLine(gson.toJson(context.recentRuns))
        }

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
            AiCoachResponse(
                nextRunDurationSeconds = 60,
                nextWalkDurationSeconds = 30,
                nextRepeats = 6,
                graduatedToNextStage = false,
                coachMessage = "Coach update unavailable right now. Keep going."
            )
        }
    }
}
