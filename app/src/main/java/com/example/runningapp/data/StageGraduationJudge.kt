package com.example.runningapp.data

import android.util.Log
import com.example.runningapp.BuildConfig
import com.example.runningapp.training.StageTrainingRecord
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One Run put to the judge, and the id it comes back under (#514).
 *
 * The id travels with the question and is never in the state the model reads, so the answer to
 * "does run 47 meet this" is returned under the app's own key rather than copied out of a prompt.
 * That copy is the whole of what this replaces: a graduation used to rest on the model reproducing
 * a `timestamp` digit for digit, where a miscopied digit and a refusal were the same answer.
 */
data class GraduationCandidate(
    val runId: Long,
    val run: AiRecentRun,
)

/**
 * What the judge is asked: the Stage's requirement, how much training the Stage has held, and the
 * Runs that are allowed to answer it (#514).
 *
 * [candidates] is exactly the set [AiTrainingContext.requirementEvidenceRuns] holds — structured
 * Runs recorded under this Stage that the runner did not mark a Walk and did not keep from the
 * coach. A Walk or an unplanned Open Run is never asked about, so there is no rule needed to forbid
 * naming one: the question is never put.
 */
data class GraduationQuestion(
    val requirement: String,
    val stageTraining: StageTrainingRecord,
    val candidates: List<GraduationCandidate>,
)

/**
 * Whether the Runs of a Stage meet its requirement — the one judgement a graduation rests on.
 *
 * An interface rather than the client itself so the decision has a seam a test can stand in at:
 * the app's behaviour on "the judge says no", "the judge says yes" and "the judge could not be
 * reached" is the part worth testing, and none of it needs a network.
 */
interface StageGraduationJudge {

    /**
     * Whether there is a judge here to ask at all.
     *
     * A build with no key is not a build that is offline (#76). Asked before anything is attempted,
     * so "we cannot ask" and "we asked and got nothing" stay two different answers — and they end
     * differently: with no key the coach still writes the debrief and the prescription and simply
     * never graduates, where an unreachable judge discards the whole evaluation.
     */
    val canBeAsked: Boolean

    /**
     * Which of [GraduationQuestion.candidates] meet the requirement on their own numbers, or null
     * when no judgement was reached.
     *
     * Null is not an empty set. An empty set is a judgement — these Runs do not meet it — and the
     * Stage stands. Null is no judgement at all, and a graduation cannot be taken back, so the
     * caller throws the whole evaluation away rather than reading it as a no.
     *
     * An implementation that [canBeAsked] is false for returns null here too, because it reached no
     * judgement either. The two are still different answers and the caller still tells them apart
     * — it asks [canBeAsked] first, and a build with no key graduates nothing while the coach goes
     * on writing (#76). This is the safe order: a judge that is asked when it should not have been
     * refuses rather than inventing a no.
     */
    suspend fun runsAnsweringRequirement(question: GraduationQuestion): Set<Long>?
}

/**
 * How sure the judge has to be before a Run counts as meeting the requirement (#514).
 *
 * Jev returns a calibrated probability rather than a yes, so this is a real number and not a vibe.
 * It sits high because the two errors are not equal: a graduation granted wrongly is granted for
 * good — nothing re-judges one and `SettingsRepository.graduateStage` only ever writes forward —
 * while a graduation refused wrongly costs one more Run, and the next evaluation asks again.
 *
 * A starting value, to be moved against Chris's own runs rather than argued about here.
 */
internal const val GRADUATION_NOUL_THRESHOLD = 0.8

private const val TYPESAFE_ENDPOINT = "https://api.typesafe.ai/v1/systemone"
private const val TYPESAFE_MODEL = "jev-latest"

/** The prefix that turns a Run's database id into a question id, and back. */
private const val QUESTION_PREFIX = "run_"

/**
 * The judge, asked over TypeSafe's System One (#514).
 *
 * One request, one question per candidate Run, all of them over the same state: they are
 * independent judgements about different Runs, so they run in parallel and none of them can see
 * another's answer. That independence is the point — a Run either meets the requirement on its own
 * numbers or it does not, and a Walk sitting beside it cannot lend it anything, because the Walk
 * was never in the request.
 */
class TypeSafeGraduationJudge(
    private val apiKey: String = BuildConfig.TYPESAFE_API_KEY,
    private val endpoint: String = TYPESAFE_ENDPOINT,
) : StageGraduationJudge {

    override val canBeAsked: Boolean get() = apiKey.isNotBlank()

    override suspend fun runsAnsweringRequirement(question: GraduationQuestion): Set<Long>? {
        if (!canBeAsked) return null
        if (question.candidates.isEmpty()) return emptySet()

        return withContext(Dispatchers.IO) {
            try {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                }
                try {
                    OutputStreamWriter(connection.outputStream).use { it.write(buildGraduationRequest(question)) }
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        Log.w("AiCoach", "TypeSafe returned HTTP ${connection.responseCode}")
                        return@withContext null
                    }
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    parseGraduationAnswers(body, question.candidates.map { it.runId }.toSet())
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.e("AiCoach", "Failed to ask TypeSafe whether the stage requirement is met", e)
                null
            }
        }
    }
}

/**
 * The request body, built rather than templated so the question ids and the state paths cannot
 * drift apart: each question is about `runs.<id>` and is named `run_<id>`.
 *
 * One narrow judgement per question, which is the whole shape of the change. The model is not asked
 * to pick a Run, to count Runs, or to decide whether the Stage graduates — it is asked, once per
 * Run, whether that Run's own numbers meet the requirement. Which Runs may be asked about, how many
 * of them it takes, and what to do with the answers are all the app's, in Kotlin, where they were
 * always meant to be.
 */
internal fun buildGraduationRequest(question: GraduationQuestion): String {
    // A Stage with no qualifying Run behind it sends no record — nothing rather than a record of
    // zeroes, which is a thing to reason from where an absence is not. So the sentence pointing at
    // it has to go with it: an instruction naming `stageTrainingRecord` on a request that has no
    // such key is a path the model is sent to look down and finds nothing at.
    val consistencyClause = when {
        question.stageTraining.isEmpty -> ""
        else -> ", together with `stageTrainingRecord` where the requirement is about how " +
            "consistently the runner has trained"
    }
    val runs = JsonObject().apply {
        question.candidates.forEach { candidate ->
            add(candidate.runId.toString(), candidate.run.asJudgeState())
        }
    }
    val state = JsonObject().apply {
        addProperty("requirement", question.requirement)
        // The Stage's own count of its qualifying Runs, so a requirement written in weeks is not
        // judged through a three-Run keyhole (#289). A count and never a measurement: it says how
        // many Runs fell in each week and nothing about how far or how fast any of them went.
        if (!question.stageTraining.isEmpty) {
            add("stageTrainingRecord", question.stageTraining.asJudgeState())
        }
        add("runs", runs)
    }
    val questions = JsonObject().apply {
        question.candidates.forEach { candidate ->
            add(
                QUESTION_PREFIX + candidate.runId,
                JsonObject().apply {
                    addProperty("type", "noul")
                    addProperty(
                        "instructions",
                        "The runner is working toward this training stage's requirement: " +
                            "`requirement`. Judge the single run at `runs.${candidate.runId}` " +
                            "against it. Use only that run's own recorded numbers$consistencyClause. " +
                            "Do not use any other run in `runs`."
                    )
                    add(
                        "criteria",
                        JsonObject().apply {
                            addProperty(
                                "true",
                                "This run's own numbers, read with the stage's training record, " +
                                    "show the requirement has been met."
                            )
                            addProperty(
                                "false",
                                "This run does not show the requirement has been met, or the " +
                                    "numbers recorded for it cannot answer the requirement at all."
                            )
                        }
                    )
                }
            )
        }
    }
    return JsonObject().apply {
        add("state", state)
        addProperty("model", TYPESAFE_MODEL)
        add("questions", questions)
    }.toString()
}

/**
 * One Run as the judge's state: its measurements, and nothing a person wrote (#514).
 *
 * The fields are listed here rather than serialized off the class, because the two readers of an
 * [AiRecentRun] want different things. The debrief prompt wants the Run as the runner experienced
 * it — their note, quoted and fenced as their words, and the weather it was run in — and it reads
 * that prose to write prose. This judge decides a graduation that is granted for good, so it is
 * handed only the numbers the requirement may be measured against. A note is free text the runner
 * chooses, and there is no fence to put around it here: the answer this state produces is not read
 * by a person who can weigh it, it is a probability the app acts on. So the note does not travel,
 * and a Run that says "this met the requirement" says it to the debrief and to nobody else.
 *
 * Nulls are written out rather than dropped (#182). A Run with no measured 5K has to say so: a
 * field that is simply absent reads as an oversight, and this one is the whole of the evidence a
 * distance-and-time requirement is judged on.
 */
private fun AiRecentRun.asJudgeState(): JsonObject = JsonObject().apply {
    addProperty("durationSeconds", durationSeconds)
    addProperty("avgHr", avgHr)
    addProperty("sessionType", sessionType)
    addProperty("runMode", runMode)
    addProperty("timestamp", timestamp)
    addProperty("distanceKm", distanceKm)
    addProperty("fastest5kSeconds", fastest5kSeconds)
    addProperty("perceivedEffort", perceivedEffort)
}

/**
 * The Stage's training record as state, with the two things it cannot say said as fields rather
 * than as rules (#289).
 *
 * Both were `CRITICAL RULE` lines in the evaluation prompt, and both existed because prose invites
 * a reading it never meant. `fullWeeksOfTrainingCompleted` is the answer to "how many weeks", so
 * there is no longer a row-counting mistake to forbid — the rows are still here, named as calendar
 * weeks, but the number a weeks-based requirement wants is given outright. And `measures` says what
 * the record is: a list of dates, carrying no heart rate, no zone, no distance and no duration, so
 * a Run above Zone 2 and a Run of two minutes are each one tick.
 */
private fun StageTrainingRecord.asJudgeState(): JsonObject = JsonObject().apply {
    addProperty("qualifyingRuns", qualifyingRuns)
    addProperty("firstRunOn", firstRunOn?.toString())
    addProperty("daysSinceFirstRun", daysSinceFirstRun)
    addProperty("fullWeeksOfTrainingCompleted", weeksTrained)
    add(
        "calendarWeeks",
        JsonObject().apply { weeks.forEach { addProperty(it.startingOn.toString(), it.qualifyingRuns) } }
    )
    addProperty("calendarWeeksAreATail", weeksAreATail)
    addProperty(
        "whatAQualifyingRunIs",
        "A structured plan run recorded under this stage that the runner did not mark as a walk. " +
            "This is the app's own count of every one of them across the whole stage, not an " +
            "estimate. A calendar week showing 0 is a week they did not train in this stage."
    )
    addProperty(
        "measures",
        "Nothing. This record counts runs and measures none of them: it carries no heart rate, no " +
            "zone, no distance and no duration. Never assume a run counted here was run in any " +
            "particular zone, at any particular effort or over any particular distance. Use " +
            "fullWeeksOfTrainingCompleted for how many weeks of training there have been, never " +
            "the number of calendar week rows: a first run late in a week starts a new row days " +
            "later, so four rows can be on the list little more than two weeks in."
    )
}

/**
 * Which Runs came back over the threshold, or null when the reply cannot be read (#514).
 *
 * Every doubt is settled the way every doubt on this path is settled — refuse — but refusing is two
 * different things here and the difference is the whole of the change. A Run whose answer is below
 * the threshold is simply left out of the set: that is a judgement, and the other Runs' judgements
 * still stand. A reply that is not readable at all returns null, and nothing is graduated on it.
 *
 * An answer under a key nobody asked about is ignored rather than fatal: it cannot graduate
 * anything, because [asked] is what the caller resolves the result against, and it is the app's own
 * list of ids.
 */
internal fun parseGraduationAnswers(json: String, asked: Set<Long>): Set<Long>? {
    val answers = try {
        JsonParser.parseString(json)?.asJsonObject?.getAsJsonObject("answers")
    } catch (e: Exception) {
        Log.w("AiCoach", "TypeSafe sent a reply that is not a System One answer set", e)
        null
    } ?: return null

    val readable = answers.entrySet().mapNotNull { (key, value) ->
        val runId = key.removePrefix(QUESTION_PREFIX).toLongOrNull() ?: return@mapNotNull null
        if (runId !in asked) return@mapNotNull null
        val noul = runCatching { value.asJsonObject.get("noul").asDouble }.getOrNull()
            ?: return@mapNotNull null
        runId to noul
    }
    // A reply that answers none of what was asked is no judgement, not a judgement of no. An empty
    // `answers` object, or one whose every entry is unreadable, says nothing about these Runs — and
    // "the judge said no" is a sentence the evaluation acts on.
    if (readable.isEmpty()) {
        Log.w("AiCoach", "TypeSafe answered none of the ${asked.size} runs it was asked about")
        return null
    }
    return readable.filter { (_, noul) -> noul >= GRADUATION_NOUL_THRESHOLD }.map { it.first }.toSet()
}
