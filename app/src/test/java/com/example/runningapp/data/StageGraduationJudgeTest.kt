package com.example.runningapp.data

import com.example.runningapp.training.StageTrainingRecord
import com.example.runningapp.training.StageWeek
import com.google.gson.JsonParser
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of the judge that are worth testing, and neither needs a network (#514) — the same
 * bargain `WeatherClient` strikes: the URL it builds and the reply it reads are ordinary functions,
 * and only the socket between them is not.
 */
class StageGraduationJudgeTest {

    private fun aRun(seconds: Long, avgHr: Int, timestamp: Long) = AiRecentRun(
        durationSeconds = seconds,
        avgHr = avgHr,
        sessionType = "Run/Walk",
        timestamp = timestamp,
        runMode = "outdoor",
        distanceKm = 5.2,
        fastest5kSeconds = 1_680,
    )

    private val twoCandidates = GraduationQuestion(
        requirement = "Complete 4 weeks of consistent Zone 2 training.",
        stageTraining = StageTrainingRecord.NONE,
        candidates = listOf(
            GraduationCandidate(runId = 47, run = aRun(1_680, 148, 1_000L)),
            GraduationCandidate(runId = 48, run = aRun(1_720, 151, 2_000L)),
        ),
    )

    private val threeWeeksOfTraining = StageTrainingRecord(
        firstRunOn = LocalDate.parse("2026-08-10"),
        daysSinceFirstRun = 21,
        qualifyingRuns = 5,
        weeks = (0 until 3).map { StageWeek(LocalDate.parse("2026-08-10").plusWeeks(it.toLong()), 2) },
        calendarWeeksSpanned = 3,
    )

    private fun requestOf(question: GraduationQuestion) =
        JsonParser.parseString(buildGraduationRequest(question)).asJsonObject

    // --- What is asked ------------------------------------------------------------------------

    @Test
    fun `each candidate gets its own question, keyed by the app's own run id`() {
        // The whole of the change in one assertion. The model never copies a number: the app asked
        // about run 47 and gets run 47's answer back, so a miscopied digit — which used to be
        // indistinguishable from a refusal — has nowhere to happen (#287).
        val questions = requestOf(twoCandidates).getAsJsonObject("questions")

        assertEquals(setOf("run_47", "run_48"), questions.keySet())
        assertEquals("noul", questions.getAsJsonObject("run_47").get("type").asString)
    }

    @Test
    fun `a question points at its own run and forbids reading from the others`() {
        val instructions = requestOf(twoCandidates)
            .getAsJsonObject("questions")
            .getAsJsonObject("run_47")
            .get("instructions").asString

        assertTrue(instructions.contains("`runs.47`"))
        assertTrue(instructions.contains("Do not use any other run in `runs`"))
    }

    @Test
    fun `a Stage with no record sends no record, and points at no such path`() {
        // An instruction naming `stageTrainingRecord` on a request that has no such key is a path
        // the model is sent to look down and finds nothing at.
        val instructions = requestOf(twoCandidates)
            .getAsJsonObject("questions")
            .getAsJsonObject("run_47")
            .get("instructions").asString

        assertFalse(instructions.contains("stageTrainingRecord"))
    }

    @Test
    fun `a Stage with a record is pointed at it`() {
        val question = twoCandidates.copy(stageTraining = threeWeeksOfTraining)

        val instructions = requestOf(question)
            .getAsJsonObject("questions")
            .getAsJsonObject("run_47")
            .get("instructions").asString

        assertTrue(instructions.contains("`stageTrainingRecord`"))
    }

    @Test
    fun `the runs are keyed by id, and their numbers are sent whole`() {
        val runs = requestOf(twoCandidates).getAsJsonObject("state").getAsJsonObject("runs")

        assertEquals(setOf("47", "48"), runs.keySet())
        assertEquals(148, runs.getAsJsonObject("47").get("avgHr").asInt)
    }

    @Test
    fun `a run with no measured 5K says so as a null rather than by omission`() {
        // The same bargain the evaluation prompt strikes (#182): a field that is simply absent
        // reads as an oversight, and this one is the whole of the evidence a distance-and-time
        // requirement is judged on.
        val question = twoCandidates.copy(
            candidates = listOf(
                GraduationCandidate(
                    runId = 47,
                    run = aRun(1_680, 148, 1_000L).copy(distanceKm = null, fastest5kSeconds = null),
                )
            )
        )

        val run = requestOf(question).getAsJsonObject("state").getAsJsonObject("runs")
            .getAsJsonObject("47")

        assertTrue(run.has("fastest5kSeconds"))
        assertTrue(run.get("fastest5kSeconds").isJsonNull)
        assertTrue(run.get("distanceKm").isJsonNull)
    }

    @Test
    fun `a Stage with no training behind it sends no record at all`() {
        // Nothing rather than a record saying there is nothing, which is what the prompt does with
        // the same case: a field of zeroes is a thing to reason from and an absence is not.
        assertFalse(requestOf(twoCandidates).getAsJsonObject("state").has("stageTrainingRecord"))
    }

    @Test
    fun `the record hands over the full weeks outright, rather than the rows to count`() {
        // Four Monday rows can be on the list little over two weeks in, and a graduation cannot be
        // taken back — which used to need a CRITICAL RULE telling the model not to count the rows.
        // A field holding the answer needs no rule (#289, #514).
        val question = twoCandidates.copy(
            stageTraining = StageTrainingRecord(
                firstRunOn = LocalDate.parse("2026-08-09"),
                daysSinceFirstRun = 15,
                qualifyingRuns = 4,
                weeks = (0 until 4).map {
                    StageWeek(LocalDate.parse("2026-08-03").plusWeeks(it.toLong()), 1)
                },
                calendarWeeksSpanned = 4,
            )
        )

        val record = requestOf(question).getAsJsonObject("state")
            .getAsJsonObject("stageTrainingRecord")

        assertEquals(2, record.get("fullWeeksOfTrainingCompleted").asInt)
        assertEquals(4, record.getAsJsonObject("calendarWeeks").size())
        assertTrue(record.get("measures").asString.contains("counts runs and measures none of them"))
    }

    // --- What comes back ----------------------------------------------------------------------

    @Test
    fun `a run over the threshold answers the requirement and one under it does not`() {
        val answered = parseGraduationAnswers(
            """{"answers":{"run_47":{"type":"noul","noul":0.94},"run_48":{"type":"noul","noul":0.41}}}""",
            asked = setOf(47L, 48L),
        )

        assertEquals(setOf(47L), answered)
    }

    @Test
    fun `a run below the threshold is left out, and the other runs' answers still stand`() {
        // The difference that carries the whole design. A run the judge is unsure about is simply
        // not evidence — that is a judgement, and it does not take the other runs down with it.
        // Only an unreadable REPLY refuses everything.
        val answered = parseGraduationAnswers(
            """{"answers":{"run_47":{"type":"noul","noul":0.99},"run_48":{"type":"noul","noul":0.79}}}""",
            asked = setOf(47L, 48L),
        )

        assertEquals(setOf(47L), answered)
    }

    @Test
    fun `a reply that is not an answer set is no judgement at all`() {
        assertNull(parseGraduationAnswers("not json", asked = setOf(47L)))
        assertNull(parseGraduationAnswers("""{"error":"overloaded"}""", asked = setOf(47L)))
    }

    @Test
    fun `a reply answering none of what was asked is no judgement, not a judgement of no`() {
        // "The judge said no" is a sentence the evaluation acts on — it goes on to write a
        // prescription and a debrief under the Stage the runner is still in. A reply that says
        // nothing about these Runs has not said that.
        assertNull(parseGraduationAnswers("""{"answers":{}}""", asked = setOf(47L)))
        assertNull(
            parseGraduationAnswers(
                """{"answers":{"run_47":{"type":"noul"}}}""",
                asked = setOf(47L),
            )
        )
        assertNull(
            parseGraduationAnswers(
                """{"answers":{"run_999":{"type":"noul","noul":0.99}}}""",
                asked = setOf(47L),
            )
        )
    }

    @Test
    fun `an answer under a key nobody asked about cannot graduate anything`() {
        // It has nowhere to have come from, because the ids in the questions are the app's own.
        // Ignored rather than fatal: the runs that WERE asked about have answered.
        val answered = parseGraduationAnswers(
            """{"answers":{"run_47":{"type":"noul","noul":0.95},"run_999":{"type":"noul","noul":0.99},"nonsense":{"noul":1.0}}}""",
            asked = setOf(47L),
        )

        assertEquals(setOf(47L), answered)
    }

    @Test
    fun `an answer with no readable probability is not a yes, and does not sink the readable ones`() {
        val answered = parseGraduationAnswers(
            """{"answers":{"run_47":{"type":"noul","noul":0.95},"run_48":{"type":"noul","noul":"very"}}}""",
            asked = setOf(47L, 48L),
        )

        assertEquals(setOf(47L), answered)
    }

    @Test
    fun `no candidates is a no without a request being sent`() {
        val judge = TypeSafeGraduationJudge(apiKey = "not-a-real-key", endpoint = "http://127.0.0.1:1")

        val answered = kotlinx.coroutines.runBlocking {
            judge.runsAnsweringRequirement(twoCandidates.copy(candidates = emptyList()))
        }

        assertEquals(emptySet<Long>(), answered)
    }

    @Test
    fun `a build with no key cannot be asked`() {
        assertFalse(TypeSafeGraduationJudge(apiKey = "").canBeAsked)
        assertTrue(TypeSafeGraduationJudge(apiKey = "sk-whatever").canBeAsked)
    }
}
