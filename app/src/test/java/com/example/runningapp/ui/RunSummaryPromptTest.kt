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
        assertEquals("27:30", facts.durationLabel)
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
            "26:00",
            runSummaryFacts(run(movingTimeSeconds = 1_560), zone = london).movingTimeLabel,
        )
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
            listOf("Gold at Fastest 5 km (24:31)", "Silver at Longest run (5.00 km)"),
            facts.records,
        )
    }

    @Test
    fun `segments are named in the order the run went over them, with the placing where there is one`() {
        val facts = runSummaryFacts(
            run(),
            segmentEfforts = listOf(
                RunSegmentEffortUi(1, 10, "Cemetery Hill", "2:31", "4:12 /km", Medal.GOLD),
                RunSegmentEffortUi(2, 11, "The Long Straight", "3:04", "5:01 /km", null),
            ),
            zone = london,
        )

        assertEquals(
            listOf(
                "Cemetery Hill in 2:31 at 4:12 /km, Gold there",
                "The Long Straight in 3:04 at 5:01 /km",
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
                    RunSegmentEffortUi(1, 10, "Cemetery Hill", "2:31", "4:12 /km", Medal.BRONZE)
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
            "Gold at Fastest 5 km (24:31)",
            "Cemetery Hill in 2:31 at 4:12 /km, Bronze there",
            "run 2 of 2",
            "30 s/km faster than usual on this route",
        ).forEach { assertTrue("missing: $it", prompt.contains(it)) }
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
