package com.example.runningapp.ui

import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.data.Achievement
import com.example.runningapp.data.RunnerSession
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the model is told about one Run (#76).
 *
 * The whole of the feature that is worth pinning lives here: the facts gathered off stored rows, and
 * the prompt built out of them. Neither reaches the network, which is the point — an AI feature
 * whose only testable surface is the reply is a feature nobody can check.
 *
 * The rule most of these guard is the same one: **a fact that does not exist is not mentioned at
 * all**. A model told "records earned: none" writes a sentence about earning none; a model told
 * nothing writes about what the Run did hold.
 */
class RunSummaryPromptTest {

    private val london = ZoneId.of("Europe/London")

    /** Wednesday 12 August 2026, 07:00 in London. */
    private val wednesdayMorning = 1_786_514_400_000L

    private fun run(
        distanceKm: Double = 5.0,
        avgPaceMinPerKm: Double = 5.5,
        avgBpm: Int = 152,
        maxBpm: Int = 171,
        durationSeconds: Long = 1_650,
        movingTimeSeconds: Long? = null,
        effortScore: Int? = 62,
        sessionNote: String? = null,
        isWalk: Boolean = false,
        runMode: String = "outdoor",
    ) = RunnerSession(
        id = 7,
        startTime = wednesdayMorning,
        endTime = wednesdayMorning + durationSeconds * 1_000,
        durationSeconds = durationSeconds,
        avgBpm = avgBpm,
        maxBpm = maxBpm,
        runMode = runMode,
        distanceKm = distanceKm,
        avgPaceMinPerKm = avgPaceMinPerKm,
        movingTimeSeconds = movingTimeSeconds,
        effortScore = effortScore,
        sessionNote = sessionNote,
        isWalk = isWalk,
        // The offset in force in London that August morning, so the date is the runner's own (#304).
        ranAtUtcOffsetSeconds = 3_600,
    )

    private fun matchedRun(
        sessionId: Long,
        paceMinPerKm: Double?,
        isThisRun: Boolean = false,
    ) = MatchedRunUi(
        sessionId = sessionId,
        startTime = wednesdayMorning,
        date = LocalDate.of(2026, 8, 12),
        dateLabel = "12 Aug 2026",
        distanceLabel = "5.00 km",
        timeLabel = "27:30",
        paceLabel = "5:30 /km",
        paceMinPerKm = paceMinPerKm,
        isThisRun = isThisRun,
    )

    // --- What is gathered ---

    @Test
    fun `a run's own numbers are read off the row`() {
        val facts = runSummaryFacts(run(), zone = london)

        assertEquals("Run", facts.kind)
        assertEquals("Wednesday 12 August 2026", facts.dateLabel)
        assertEquals("27 minutes 30 seconds", facts.durationLabel)
        assertEquals("5.00 km", facts.distanceLabel)
        assertEquals("5:30 /km", facts.paceLabel)
        assertEquals(152, facts.avgBpm)
        assertEquals(171, facts.maxBpm)
        assertEquals(62, facts.effortScore)
    }

    @Test
    fun `a run that wore no strap has no heart rate and no effort score`() {
        val facts = runSummaryFacts(run(avgBpm = 0, maxBpm = 0, effortScore = null), zone = london)

        assertNull(facts.avgBpm)
        assertNull(facts.maxBpm)
        assertNull(facts.effortScore)

        val prompt = buildRunSummaryPrompt(facts)
        assertFalse(prompt.contains("heart rate"))
        assertFalse(prompt.contains("Effort Score"))
    }

    @Test
    fun `a run nobody measured a distance for has neither distance nor pace`() {
        val facts = runSummaryFacts(run(distanceKm = 0.0, avgPaceMinPerKm = 0.0), zone = london)

        assertNull(facts.distanceLabel)
        assertNull(facts.paceLabel)
        assertFalse(buildRunSummaryPrompt(facts).contains("Distance"))
    }

    @Test
    fun `a treadmill walk is named as one`() {
        val facts = runSummaryFacts(run(runMode = "treadmill", isWalk = true), zone = london)

        assertEquals("Treadmill walk", facts.kind)
    }

    @Test
    fun `moving time is sent only where it differs from the clock`() {
        assertNull(runSummaryFacts(run(movingTimeSeconds = 1_650), zone = london).movingTimeLabel)
        assertEquals(
            "26 minutes",
            runSummaryFacts(run(movingTimeSeconds = 1_560), zone = london).movingTimeLabel,
        )
    }

    @Test
    fun `a time under a minute is in seconds, not a clock that reads as minutes`() {
        // 0:37 was read by the model as a 37-minute run (#547).
        assertEquals("37 seconds", runSummaryFacts(run(durationSeconds = 37), zone = london).durationLabel)
        assertEquals("1 second", spokenDuration(1))
        assertEquals("0 seconds", spokenDuration(0))
    }

    @Test
    fun `a time under an hour is minutes and seconds with singular and plural right`() {
        assertEquals("1 minute 1 second", spokenDuration(61))
        assertEquals("25 minutes 30 seconds", spokenDuration(1_530))
    }

    @Test
    fun `a time over an hour carries hours and leaves out parts that are zero`() {
        assertEquals("1 hour 5 minutes 3 seconds", spokenDuration(3_903))
        assertEquals("2 hours", spokenDuration(7_200))
        val facts = runSummaryFacts(run(durationSeconds = 3_900), zone = london)
        assertEquals("1 hour 5 minutes", facts.durationLabel)
    }

    @Test
    fun `a seconds record is written with units in the prompt`() {
        // FASTEST_5K 1_471 s: the clock form 24:31 is exactly what could be misread.
        val prompt = buildRunSummaryPrompt(
            runSummaryFacts(
                run(),
                achievements = listOf(
                    Achievement(sessionId = 7, type = RecordType.FASTEST_5K, medal = Medal.GOLD, value = 1_471.0)
                ),
                zone = london,
            )
        )
        assertTrue(prompt, prompt.contains("24 minutes 31 seconds"))
        assertFalse(prompt, prompt.contains("24:31"))
    }

    /**
     * The one fact on the page the app did not measure, and the only one deliberately held back: it
     * is free prose the runner wrote for themselves, and #76 did not ask for it.
     */
    @Test
    fun `what the runner wrote about the run is never sent`() {
        val prompt = buildRunSummaryPrompt(
            runSummaryFacts(run(sessionNote = "Argued with my boss before this one"), zone = london)
        )

        assertFalse(prompt.contains("Argued"))
        assertFalse(prompt.contains("boss"))
    }

    @Test
    fun `medals are named with the metal and the effort that won them`() {
        val facts = runSummaryFacts(
            run(),
            achievements = listOf(
                Achievement(sessionId = 7, type = RecordType.LONGEST_DISTANCE, medal = Medal.SILVER, value = 5_000.0),
                Achievement(sessionId = 7, type = RecordType.FASTEST_5K, medal = Medal.GOLD, value = 1_471.0),
            ),
            zone = london,
        )

        // In the book's own order, not the order the rows happened to arrive in.
        assertEquals(
            listOf("Gold at Fastest 5 km (24 minutes 31 seconds)", "Silver at Longest run (5.00 km)"),
            facts.records,
        )
    }

    @Test
    fun `segments are named in the order the run went over them, with the placing where there is one`() {
        val facts = runSummaryFacts(
            run(),
            segmentEfforts = listOf(
                RunSegmentEffortUi(1, 10, "Cemetery Hill", "2:31", "4:12 /km", Medal.GOLD, 151),
                RunSegmentEffortUi(2, 11, "The Long Straight", "3:04", "5:01 /km", null, 184),
            ),
            zone = london,
        )

        assertEquals(
            listOf(
                "Cemetery Hill in 2 minutes 31 seconds at 4:12 /km, Gold there",
                "The Long Straight in 3 minutes 4 seconds at 5:01 /km",
            ),
            facts.segments,
        )
    }

    // --- The route ---

    @Test
    fun `the route comparison is against the middle of the other runs, not of all of them`() {
        val matched = MatchedRunsUi(
            runs = listOf(
                matchedRun(1, 6.0),
                matchedRun(2, 5.8),
                matchedRun(7, 5.5, isThisRun = true),
            ),
            position = 3,
        )

        val route = runSummaryFacts(run(), matched = matched, zone = london).matched!!
        assertEquals(3, route.position)
        assertEquals(3, route.count)
        assertEquals("5:30 /km", route.paceLabel)
        // The middle of 6.0 and 5.8 — this Run's own 5.5 is not in it.
        assertEquals("5:54 /km", route.usualPaceLabel)
        assertEquals("24 s/km faster than usual on this route", route.comparison)
    }

    @Test
    fun `a slower run is said to be slower`() {
        val matched = MatchedRunsUi(
            runs = listOf(matchedRun(1, 5.0), matchedRun(7, 5.25, isThisRun = true)),
            position = 2,
        )

        assertEquals(
            "15 s/km slower than usual on this route",
            runSummaryFacts(run(), matched = matched, zone = london).matched!!.comparison,
        )
    }

    @Test
    fun `a group where nobody else measured a pace has nothing to compare`() {
        val matched = MatchedRunsUi(
            runs = listOf(matchedRun(1, null), matchedRun(7, 5.5, isThisRun = true)),
            position = 2,
        )

        assertNull(runSummaryFacts(run(), matched = matched, zone = london).matched)
    }

    @Test
    fun `a run with no group has no route section at all`() {
        val prompt = buildRunSummaryPrompt(runSummaryFacts(run(), zone = london))

        assertFalse(prompt.contains("THIS ROUTE"))
        assertFalse(prompt.contains("route"))
    }

    // --- The prompt ---

    @Test
    fun `the prompt carries every fact it was given`() {
        val prompt = buildRunSummaryPrompt(
            runSummaryFacts(
                run(),
                achievements = listOf(
                    Achievement(sessionId = 7, type = RecordType.FASTEST_5K, medal = Medal.GOLD, value = 1_471.0)
                ),
                segmentEfforts = listOf(
                    RunSegmentEffortUi(1, 10, "Cemetery Hill", "2:31", "4:12 /km", Medal.BRONZE, 151)
                ),
                matched = MatchedRunsUi(
                    runs = listOf(matchedRun(1, 6.0), matchedRun(7, 5.5, isThisRun = true)),
                    position = 2,
                ),
                zone = london,
            )
        )

        listOf(
            "Wednesday 12 August 2026",
            "5.00 km",
            "5:30 /km",
            "152 bpm",
            "Effort Score: 62",
            "Gold at Fastest 5 km (24 minutes 31 seconds)",
            "Cemetery Hill in 2 minutes 31 seconds at 4:12 /km, Bronze there",
            "run 2 of 2",
            "30 s/km faster than usual on this route",
        ).forEach { assertTrue("missing: $it", prompt.contains(it)) }
    }

    /**
     * The model explains the pace off this line, so the line has to be true. An Outage is a Break
     * the run carried on through and its seconds are counted as moving (ADR 0012) — a prompt saying
     * lost signal was taken out would have the model explain a real number from a false premise.
     */
    @Test
    fun `moving time is not described as having lost signal taken out`() {
        val prompt = buildRunSummaryPrompt(
            runSummaryFacts(run(movingTimeSeconds = 1_560), zone = london)
        )

        val movingLine = prompt.lines().single { it.startsWith("- Moving time") }
        assertFalse(movingLine.contains("lost signal taken out"))
        assertTrue(movingLine.contains("the time they spent stopped taken out"))
        assertTrue(movingLine.contains("still counts as moving"))
        assertTrue(movingLine.endsWith("26 minutes"))
    }

    @Test
    fun `the prompt fences the summary off from prescribing anything`() {
        val prompt = buildRunSummaryPrompt(runSummaryFacts(run(), zone = london))

        assertTrue(prompt.contains("Do not prescribe the next workout"))
        assertTrue(prompt.contains("do not guess at anything you are not told"))
    }

    @Test
    fun `the same facts build the same prompt`() {
        val facts = runSummaryFacts(run(), zone = london)

        assertEquals(buildRunSummaryPrompt(facts), buildRunSummaryPrompt(facts))
    }
}
