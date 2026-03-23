package com.example.runningapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EasyFixedDurationSummaryTest {

    @Test
    fun `computeEasyFixedDurationSummary derives locomotion metrics from pace samples when coverage is adequate`() {
        val samples = buildList {
            repeat(40) { second ->
                add(
                    HrSample(
                        sessionId = 1,
                        elapsedSeconds = second.toLong(),
                        rawBpm = 130,
                        smoothedBpm = 130,
                        connectionState = "Connected",
                        paceMinPerKm = 8.0
                    )
                )
            }
            repeat(20) { offset ->
                add(
                    HrSample(
                        sessionId = 1,
                        elapsedSeconds = (40 + offset).toLong(),
                        rawBpm = 120,
                        smoothedBpm = 120,
                        connectionState = "Connected",
                        paceMinPerKm = 12.0
                    )
                )
            }
        }

        val summary = computeEasyFixedDurationSummary(
            plannedDurationSeconds = 1800,
            actualDurationSeconds = 60,
            avgBpm = 126,
            maxBpm = 145,
            timeAboveEasyCapSeconds = 7,
            noDataSeconds = 0,
            samples = samples
        )

        assertEquals(1800, summary.plannedDurationSeconds)
        assertEquals(60, summary.actualDurationSeconds)
        assertEquals(40, summary.totalJogSeconds)
        assertEquals(20, summary.totalWalkSeconds)
        assertEquals(67, summary.jogPercent)
        assertEquals(40, summary.longestJogBoutSeconds)
        assertEquals(1, summary.walkInterruptions)
        assertEquals(7, summary.timeAboveEasyCapSeconds)
    }

    @Test
    fun `computeEasyFixedDurationSummary leaves locomotion metrics provisional when pace coverage is poor`() {
        val samples = listOf(
            HrSample(
                sessionId = 1,
                elapsedSeconds = 1,
                rawBpm = 130,
                smoothedBpm = 130,
                connectionState = "Connected",
                paceMinPerKm = null
            )
        )

        val summary = computeEasyFixedDurationSummary(
            plannedDurationSeconds = 1800,
            actualDurationSeconds = 300,
            avgBpm = 128,
            maxBpm = 144,
            timeAboveEasyCapSeconds = 12,
            noDataSeconds = 5,
            samples = samples
        )

        assertNull(summary.totalJogSeconds)
        assertNull(summary.totalWalkSeconds)
        assertNull(summary.jogPercent)
        assertNull(summary.longestJogBoutSeconds)
        assertNull(summary.walkInterruptions)
        assertEquals("hr_only_or_low_pace_confidence noData=5s", summary.dataQualitySummary)
    }
}
