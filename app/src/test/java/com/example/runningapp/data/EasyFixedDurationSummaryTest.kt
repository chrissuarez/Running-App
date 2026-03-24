package com.example.runningapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EasyFixedDurationSummaryTest {

    @Test
    fun `computeEasyFixedDurationSummary derives locomotion metrics from stable jog then walk pace samples`() {
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
        assertEquals(16, summary.totalWalkSeconds)
        assertEquals(71, summary.jogPercent)
        assertEquals(40, summary.longestJogBoutSeconds)
        assertEquals(1, summary.walkInterruptions)
        assertEquals(7, summary.timeAboveEasyCapSeconds)
        assertEquals("pace_inferred coverage=93% noData=0s", summary.dataQualitySummary)
    }

    @Test
    fun `computeEasyFixedDurationSummary suppresses rapid switching around hysteresis band`() {
        val samples = buildList {
            repeat(70) { second ->
                val pace = when {
                    second < 10 -> 8.8
                    second % 2 == 0 -> 10.6
                    else -> 10.9
                }
                add(
                    HrSample(
                        sessionId = 1,
                        elapsedSeconds = second.toLong(),
                        rawBpm = 128,
                        smoothedBpm = 126,
                        connectionState = "Connected",
                        paceMinPerKm = pace
                    )
                )
            }
        }

        val summary = computeEasyFixedDurationSummary(
            plannedDurationSeconds = 1800,
            actualDurationSeconds = 70,
            avgBpm = 126,
            maxBpm = 142,
            timeAboveEasyCapSeconds = 6,
            noDataSeconds = 0,
            samples = samples
        )

        assertEquals(70, summary.totalJogSeconds)
        assertEquals(0, summary.totalWalkSeconds)
        assertEquals(100, summary.jogPercent)
        assertEquals(70, summary.longestJogBoutSeconds)
        assertEquals(0, summary.walkInterruptions)
        assertEquals("pace_inferred coverage=100% noData=0s", summary.dataQualitySummary)
    }

    @Test
    fun `computeEasyFixedDurationSummary keeps startup unknown until jog evidence becomes stable`() {
        val samples = buildList {
            repeat(4) { second ->
                add(
                    HrSample(
                        sessionId = 1,
                        elapsedSeconds = second.toLong(),
                        rawBpm = 110,
                        smoothedBpm = 110,
                        connectionState = "Connected",
                        paceMinPerKm = null
                    )
                )
            }
            repeat(61) { offset ->
                add(
                    HrSample(
                        sessionId = 1,
                        elapsedSeconds = (4 + offset).toLong(),
                        rawBpm = 132,
                        smoothedBpm = 130,
                        connectionState = "Connected",
                        paceMinPerKm = 9.4
                    )
                )
            }
        }

        val summary = computeEasyFixedDurationSummary(
            plannedDurationSeconds = 1800,
            actualDurationSeconds = 65,
            avgBpm = 124,
            maxBpm = 145,
            timeAboveEasyCapSeconds = 10,
            noDataSeconds = 4,
            samples = samples
        )

        assertEquals(57, summary.totalJogSeconds)
        assertEquals(0, summary.totalWalkSeconds)
        assertEquals(100, summary.jogPercent)
        assertEquals(57, summary.longestJogBoutSeconds)
        assertEquals(0, summary.walkInterruptions)
        assertEquals("pace_inferred coverage=88% noData=4s", summary.dataQualitySummary)
    }

    @Test
    fun `computeEasyFixedDurationSummary leaves jog walk metrics null when pace coverage is too sparse`() {
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
        assertEquals("no_usable_pace noData=5s", summary.dataQualitySummary)
    }

    @Test
    fun `computeEasyFixedDurationSummary populates summary when pace coverage is adequate despite gaps`() {
        val samples = buildList {
            repeat(35) { second ->
                add(
                    HrSample(
                        sessionId = 1,
                        elapsedSeconds = second.toLong(),
                        rawBpm = 129,
                        smoothedBpm = 128,
                        connectionState = "Connected",
                        paceMinPerKm = 9.7
                    )
                )
            }
            repeat(10) { offset ->
                add(
                    HrSample(
                        sessionId = 1,
                        elapsedSeconds = (35 + offset).toLong(),
                        rawBpm = 118,
                        smoothedBpm = 118,
                        connectionState = "Connected",
                        paceMinPerKm = null
                    )
                )
            }
            repeat(35) { offset ->
                add(
                    HrSample(
                        sessionId = 1,
                        elapsedSeconds = (45 + offset).toLong(),
                        rawBpm = 121,
                        smoothedBpm = 121,
                        connectionState = "Connected",
                        paceMinPerKm = 12.2
                    )
                )
            }
        }

        val summary = computeEasyFixedDurationSummary(
            plannedDurationSeconds = 1800,
            actualDurationSeconds = 80,
            avgBpm = 125,
            maxBpm = 148,
            timeAboveEasyCapSeconds = 15,
            noDataSeconds = 10,
            samples = samples
        )

        assertEquals(35, summary.totalJogSeconds)
        assertEquals(31, summary.totalWalkSeconds)
        assertEquals(53, summary.jogPercent)
        assertEquals(35, summary.longestJogBoutSeconds)
        assertEquals(1, summary.walkInterruptions)
        assertEquals("pace_inferred coverage=83% noData=10s", summary.dataQualitySummary)
    }
}
