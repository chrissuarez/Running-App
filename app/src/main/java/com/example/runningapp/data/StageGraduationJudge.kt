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
 * One Run of the evidence the requirement is judged on, and the id it is named by (#514).
 *
 * The id is the app's own database id. It is what the Runs are keyed by in the judge's state, so
 * nothing about a Run has to be matched back by a timestamp a model wrote out: the whole of the
 * evidence travels under keys the app chose. That copy is what this replaced — a graduation used to
 * rest on the model reproducing a `timestamp` digit for digit, where a miscopied digit and a
 * refusal were the same answer.
 */
data class GraduationCandidate(
    val runId: Long,
    val run: AiRecentRun,
    val zones: RunZoneExposure,
)

/**
 * How long a Run was actually spent in each heart-rate zone (#514).
 *
 * A requirement written as a zone cannot be answered by an average. The zones are boundaries
 * computed from *this* runner's own maximum and resting heart rate, so one average bpm is a
 * different zone for two runners — and a Run that swings either side of Zone 2 averages neatly into
 * it while having been trained in neither. The app has measured this second by second all along and
 * stores it on the row; it simply never travelled.
 *
 * [secondsWithoutHeartRate] is here so the seconds can be read as a whole. A Run whose strap dropped
 * for half of it has a zone total that says nothing about the other half, and an absence that is not
 * stated reads as a Run spent out of every zone.
 */
data class RunZoneExposure(
    val secondsByZone: Map<Int, Long>,
    val targetZone: Int,
    val secondsWithoutHeartRate: Long,
) {
    companion object {
        /** The Run's own stored measurement, read off the row it was written to. */
        fun of(session: RunnerSession): RunZoneExposure = RunZoneExposure(
            secondsByZone = mapOf(
                1 to session.zone1Seconds,
                2 to session.zone2Seconds,
                3 to session.zone3Seconds,
                4 to session.zone4Seconds,
                5 to session.zone5Seconds,
            ),
            targetZone = session.targetZone,
            secondsWithoutHeartRate = session.noDataSeconds,
        )
    }
}

/**
 * What the judge is asked: the Stage's requirement, how much training the Stage has held, and the
 * Runs that are allowed to answer it (#514).
 *
 * [candidates] is exactly the set [AiTrainingContext.requirementEvidenceRuns] holds — structured
 * Runs recorded under this Stage that the runner did not mark a Walk and did not keep from the
 * coach. A Walk or an unplanned Open Run is never in the evidence, so there is no rule needed to
 * forbid naming one: it is not in the request at all.
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
     * Whether the Stage's training, taken as a whole, meets its requirement — or null when no
     * judgement was reached.
     *
     * Null is not false. False is a judgement — this training does not meet the requirement — and
     * the Stage stands while the evaluation carries on. Null is no judgement at all, and a
     * graduation cannot be taken back, so the caller throws the whole evaluation away rather than
     * reading it as a no.
     *
     * An implementation that [canBeAsked] is false for returns null here too, because it reached no
     * judgement either. The two are still different answers and the caller still tells them apart
     * — it asks [canBeAsked] first, and a build with no key graduates nothing while the coach goes
     * on writing (#76). This is the safe order: a judge that is asked when it should not have been
     * refuses rather than inventing a no.
     */
    suspend fun requirementIsMet(question: GraduationQuestion): Boolean?
}

/**
 * How sure the judge has to be before the requirement counts as met (#514).
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

/**
 * The name the one question is asked under, and the only name an answer is accepted under (#516).
 *
 * It is not any key of `state`, deliberately. An answer arriving as `runs` or as a bare run id is a
 * key copied off the state the model was shown rather than an answer to what was asked, and this
 * one grants a graduation that is granted for good.
 */
private const val REQUIREMENT_QUESTION_ID = "requirement_met"

/**
 * The judge, asked over TypeSafe's System One (#514, #516).
 *
 * One request and **one question**: does the training this Stage holds meet its requirement. The
 * evidence Runs and the Stage's own week-by-week record go into the state together, and the judge
 * reads them as one body of training.
 *
 * It used to be one question per Run, each forbidden to read the others (#514). That shape cannot
 * answer the requirement Stage 1 is built on — "4 weeks of consistent Zone 2 training" — because no
 * single Run shows four weeks of anything, so the only honest per-Run answer is no and the Stage
 * could never graduate. Measured on Chris's own data against `jev-1.13.0`: the per-Run question
 * scored 0.51, the per-Run question with each week's Zone 2 seconds added scored 0.43, and this one
 * cohort question scored 0.87 — the only one of the three over [GRADUATION_NOUL_THRESHOLD]. See
 * ADR 0024.
 *
 * What the cohort question does *not* give back is the model's freedom to pick Runs. It is asked
 * one closed question about a set the app chose, and answers a probability. Which Runs may be in
 * that set, how sure the answer has to be, and what a yes does are all still the app's, in Kotlin.
 */
class TypeSafeGraduationJudge(
    private val apiKey: String = BuildConfig.TYPESAFE_API_KEY,
    private val endpoint: String = TYPESAFE_ENDPOINT,
) : StageGraduationJudge {

    override val canBeAsked: Boolean get() = apiKey.isNotBlank()

    override suspend fun requirementIsMet(question: GraduationQuestion): Boolean? {
        if (!canBeAsked) return null
        // No evidence is a no, and it is the app's own no: there is nothing to send, so nothing is
        // asked. A request with an empty `runs` would be asking the judge to reason about an
        // absence, which is the one thing a model reliably fills in for itself.
        if (question.candidates.isEmpty()) return false

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
                    parseGraduationAnswer(body)
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
 * The request body, built rather than templated so the question and the state paths it names cannot
 * drift apart (#516).
 *
 * One judgement, narrowly put. The model is not asked to pick a Run, to name its evidence, or to
 * decide what happens next — it is asked, once, whether the training in front of it has met the
 * requirement. Which Runs it may see, how sure it has to be, and what a yes does are all the app's.
 */
internal fun buildGraduationRequest(question: GraduationQuestion): String {
    // A Stage with no qualifying Run behind it sends no record — nothing rather than a record of
    // zeroes, which is a thing to reason from where an absence is not. So the sentence pointing at
    // it has to go with it: an instruction naming `stageTrainingRecord` on a request that has no
    // such key is a path the model is sent to look down and finds nothing at.
    val consistencyClause = when {
        question.stageTraining.isEmpty -> ""
        else -> " Read them together with `stageTrainingRecord`, which is the app's own count of " +
            "how much training this stage has held and is how a requirement written in weeks is " +
            "answered."
    }
    val runs = JsonObject().apply {
        question.candidates.forEach { candidate ->
            add(candidate.runId.toString(), candidate.asJudgeState())
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
        add(
            REQUIREMENT_QUESTION_ID,
            JsonObject().apply {
                addProperty("type", "noul")
                addProperty(
                    "instructions",
                    "The runner is working toward this training stage's requirement: " +
                        "`requirement`. Judge whether the training this stage holds has met it. " +
                        "`runs` holds the stage's most recent qualifying runs, keyed by the app's " +
                        "own run id, with what was measured for each." + consistencyClause +
                        " Judge the training as a whole, not any single run: a requirement about " +
                        "a span of weeks is met by the record of those weeks and not by one run, " +
                        "and a requirement about one performance is met the moment one run shows " +
                        "it."
                )
                add(
                    "criteria",
                    JsonObject().apply {
                        addProperty(
                            "true",
                            "The recorded training, read as a whole, shows this stage's " +
                                "requirement has been met."
                        )
                        addProperty(
                            "false",
                            "The requirement has not been met yet, or the numbers recorded " +
                                "cannot answer it at all."
                        )
                    }
                )
            }
        )
    }
    return JsonObject().apply {
        add("state", state)
        addProperty("model", TYPESAFE_MODEL)
        add("questions", questions)
    }.toString()
}

/**
 * One Run as the judge's state: its measurements — zone seconds included — and nothing a person
 * wrote (#514).
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
private fun GraduationCandidate.asJudgeState(): JsonObject = JsonObject().apply {
    addProperty("durationSeconds", run.durationSeconds)
    addProperty("avgHr", run.avgHr)
    addProperty("sessionType", run.sessionType)
    addProperty("runMode", run.runMode)
    addProperty("timestamp", run.timestamp)
    addProperty("distanceKm", run.distanceKm)
    addProperty("fastest5kSeconds", run.fastest5kSeconds)
    addProperty("perceivedEffort", run.perceivedEffort)
    // What a requirement written as a zone is judged on, and the reason `avgHr` is not it.
    add(
        "secondsInZone",
        JsonObject().apply {
            zones.secondsByZone.toSortedMap().forEach { (zone, seconds) ->
                addProperty(zone.toString(), seconds)
            }
        }
    )
    addProperty("targetZone", zones.targetZone)
    addProperty("secondsWithoutHeartRate", zones.secondsWithoutHeartRate)
    addProperty(
        "zonesAre",
        "Heart-rate zones measured second by second against this runner's own maximum and " +
            "resting heart rate, so zone 2 here is this runner's zone 2. Judge a requirement " +
            "written as a zone on these seconds and never on avgHr: an average sits in a zone " +
            "the run may have spent no time in. secondsWithoutHeartRate is time the strap " +
            "recorded nothing for, which is unknown and not time out of every zone."
    )
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
 * Whether the reply says the requirement is met, or null when it cannot be read (#516).
 *
 * Every doubt is settled the way every doubt on this path is settled — refuse — and here refusing
 * is one thing rather than two: there is a single question, so a reply that does not answer it
 * answers nothing, and nothing is graduated on it.
 *
 * The answer has to arrive under the name the question was asked under. An answer keyed off the
 * state the model was shown — `runs`, or a bare run id — is a number copied out of a prompt, and
 * that copy is exactly what this path stopped resting on (#287).
 */
internal fun parseGraduationAnswer(json: String): Boolean? {
    val answers = try {
        JsonParser.parseString(json)?.asJsonObject?.getAsJsonObject("answers")
    } catch (e: Exception) {
        Log.w("AiCoach", "TypeSafe sent a reply that is not a System One answer set", e)
        null
    } ?: return null

    val answer = runCatching { answers.getAsJsonObject(REQUIREMENT_QUESTION_ID) }.getOrNull()
    if (answer == null) {
        Log.w("AiCoach", "TypeSafe did not answer $REQUIREMENT_QUESTION_ID")
        return null
    }
    // A probability, or nothing. A Noul is a number between 0 and 1, so anything else — a string,
    // an infinity, a 42 — is a reply that did not answer the question rather than a confident yes,
    // and this one grants a graduation that is granted for good.
    val noul = runCatching { answer.get("noul") }.getOrNull()
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.let { runCatching { it.asDouble }.getOrNull() }
        ?.takeIf { it.isFinite() && it in 0.0..1.0 }
    if (noul == null) {
        Log.w("AiCoach", "TypeSafe's answer to $REQUIREMENT_QUESTION_ID is not a probability")
        return null
    }
    return noul >= GRADUATION_NOUL_THRESHOLD
}
