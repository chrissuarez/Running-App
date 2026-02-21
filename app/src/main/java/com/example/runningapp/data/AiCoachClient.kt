package com.example.runningapp.data

import com.example.runningapp.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

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
        modelName = "gemini-1.5-flash",
        apiKey = apiKey
    )

    suspend fun evaluateProgress(context: AiTrainingContext): AiCoachResponse {
        require(apiKey.isNotBlank()) { "Gemini API key is missing" }

        val prompt = buildString {
            appendLine("You are an expert running coach.")
            appendLine("Analyze the user's last 3 runs against their current stage requirement: ${context.graduationRequirement}.")
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

        val rawText = model.generateContent(prompt).text.orEmpty()
        val cleanedText = rawText
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val jsonText = extractJsonObject(cleanedText)

        return try {
            gson.fromJson(jsonText, AiCoachResponse::class.java)
        } catch (e: JsonSyntaxException) {
            throw IllegalStateException("Gemini returned invalid JSON: $rawText", e)
        }
    }

    private fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed

        val fenceRegex = Regex("```(?:json)?\\s*(\\{[\\s\\S]*})\\s*```")
        val fenced = fenceRegex.find(trimmed)?.groupValues?.getOrNull(1)?.trim()
        if (!fenced.isNullOrBlank()) return fenced

        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)

        throw IllegalStateException("No JSON object found in Gemini response: $raw")
    }
}
