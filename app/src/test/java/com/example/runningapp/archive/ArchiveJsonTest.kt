package com.example.runningapp.archive

import com.example.runningapp.data.RunWalkIntervalStat
import com.example.runningapp.data.RunnerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveJsonTest {

    private val settings = ArchivedSettings(
        maxHr = 181,
        maxHrEverSet = true,
        maxHrCardDismissed = true,
        historyMaxHr = 181,
        restingHr = 60,
        targetZone = 2,
        coachingEnabled = true,
        splitAnnouncementsEnabled = false,
        autoPauseEnabled = true,
        aiDataSharingEnabled = false,
        runMode = "outdoor",
        activePlanId = "couch_to_5k",
        activeStageId = "base_builder"
    )

    private fun document(
        runs: List<RunnerSession> = emptyList(),
        intervalStats: List<RunWalkIntervalStat> = emptyList()
    ) = ArchiveDocument(
        createdAtEpochMillis = 1_753_800_000_000,
        databaseVersion = 19,
        settings = settings,
        runs = runs,
        intervalStats = intervalStats
    )

    @Test
    fun `a run's subjective and weather fields survive the round trip`() {
        val session = RunnerSession(
            id = 41,
            startTime = 1_753_800_000_000,
            endTime = 1_753_802_400_000,
            durationSeconds = 2400,
            avgBpm = 140,
            maxBpm = 168,
            perceivedEffort = 7,
            sessionNote = "Legs heavy, hot out",
            weatherTempC = 24.5,
            weatherFeelsLikeC = 26.0,
            weatherHumidityPercent = 71,
            weatherWindSpeedKmh = 9.3,
            weatherConditionCode = 3,
            movingTimeSeconds = 2350
        )

        val restored = ArchiveJson.read(ArchiveJson.write(document(runs = listOf(session))))

        assertEquals(listOf(session), restored?.runs)
    }

    @Test
    fun `an unanswered feel sheet stays unanswered rather than becoming a number`() {
        val session = RunnerSession(id = 42, startTime = 1, perceivedEffort = null, sessionNote = null)

        val restored = ArchiveJson.read(ArchiveJson.write(document(runs = listOf(session))))

        assertNull(restored?.runs?.single()?.perceivedEffort)
        assertNull(restored?.runs?.single()?.sessionNote)
    }

    @Test
    fun `settings and interval stats round trip whole`() {
        val stat = RunWalkIntervalStat(
            id = 7,
            sessionId = 41,
            intervalIndex = 2,
            plannedDurationSeconds = 180,
            actualRunningDurationBeforeHrTriggerSeconds = 54,
            timeIntoIntervalWhenHrExceededCapSeconds = null,
            hrTriggerEvents = 1,
            totalTimeSpentWalkingDuringRunIntervalSeconds = 0,
            avgHrAtTriggerInInterval = 147.5,
            avgRecoverySecondsAfterTriggerInInterval = null
        )

        val restored = ArchiveJson.read(ArchiveJson.write(document(intervalStats = listOf(stat))))

        assertEquals(settings, restored?.settings)
        assertEquals(listOf(stat), restored?.intervalStats)
        assertEquals(19, restored?.databaseVersion)
        assertEquals(ARCHIVE_FORMAT_VERSION, restored?.formatVersion)
    }

    @Test
    fun `an archive written before the card existed says it was never put away`() {
        // The one-time card is newer than the archive format. A document without the field is a
        // phone that was never asked, which is exactly what false means here — and the only
        // reading that leaves a restored runner askable.
        val withoutTheField = ArchiveJson.write(document())
            .lines()
            .filterNot { it.contains("maxHrCardDismissed") }
            .joinToString("\n")

        assertEquals(false, ArchiveJson.read(withoutTheField)?.settings?.maxHrCardDismissed)
    }

    @Test
    fun `an empty history is a document, not a failure`() {
        val restored = ArchiveJson.read(ArchiveJson.write(document()))

        assertNotNull(restored)
        assertEquals(emptyList<RunnerSession>(), restored?.runs)
    }

    @Test
    fun `a null field is written out rather than left for the reader to infer`() {
        val json = ArchiveJson.write(
            document(runs = listOf(RunnerSession(id = 1, startTime = 1, sessionNote = null)))
        )

        assertTrue(json.contains("\"sessionNote\": null"))
    }

    @Test
    fun `text that is not an archive is refused`() {
        assertNull(ArchiveJson.read("not json at all"))
        assertNull(ArchiveJson.read("{\"something\": \"else\"}"))
        assertNull(ArchiveJson.read(""))
    }

    @Test
    fun `a document from a later app is refused rather than half understood`() {
        val fromTheFuture = ArchiveJson.write(
            document().copy(formatVersion = ARCHIVE_FORMAT_VERSION + 1)
        )

        assertNull(ArchiveJson.read(fromTheFuture))
    }
}
