package com.example.runningapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunWalkIntervalStat
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.ui.theme.RunningAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDetailScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sessionDetailScreen_showsRunWalkSummaryAndExpandsRawData() {
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = RunnerSession(
                        id = 1L,
                        startTime = 1_742_000_000_000,
                        durationSeconds = 2286,
                        avgBpm = 119,
                        maxBpm = 142,
                        targetZone = 2,
                        zone1Seconds = 53,
                        zone2Seconds = 1940,
                        zone3Seconds = 277,
                        zone4Seconds = 16,
                        walkBreaksCount = 22,
                        isRunWalkMode = true
                    ),
                    samples = listOf(
                        HrSample(sessionId = 1L, elapsedSeconds = 0, rawBpm = 101, smoothedBpm = 101, connectionState = "Connected"),
                        HrSample(sessionId = 1L, elapsedSeconds = 60, rawBpm = 110, smoothedBpm = 110, connectionState = "Connected"),
                        HrSample(sessionId = 1L, elapsedSeconds = 120, rawBpm = 132, smoothedBpm = 132, connectionState = "Connected")
                    ),
                    intervalStats = listOf(
                        RunWalkIntervalStat(
                            sessionId = 1L,
                            intervalIndex = 0,
                            plannedDurationSeconds = 60,
                            actualRunningDurationBeforeHrTriggerSeconds = 52,
                            timeIntoIntervalWhenHrExceededCapSeconds = 52,
                            hrTriggerEvents = 1,
                            totalTimeSpentWalkingDuringRunIntervalSeconds = 8,
                            avgHrAtTriggerInInterval = 135.0,
                            avgRecoverySecondsAfterTriggerInInterval = 10.0
                        ),
                        RunWalkIntervalStat(
                            sessionId = 1L,
                            intervalIndex = 1,
                            plannedDurationSeconds = 60,
                            actualRunningDurationBeforeHrTriggerSeconds = 60,
                            timeIntoIntervalWhenHrExceededCapSeconds = null,
                            hrTriggerEvents = 0,
                            totalTimeSpentWalkingDuringRunIntervalSeconds = 0,
                            avgHrAtTriggerInInterval = null,
                            avgRecoverySecondsAfterTriggerInInterval = null
                        )
                    ),
                    onDeleteSession = {},
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText("Run/Walk Interval Summary").assertIsDisplayed()
        composeRule.onNodeWithText("Total run intervals").assertIsDisplayed()
        composeRule.onNodeWithText("Intervals with no trigger").assertIsDisplayed()
        composeRule.onNodeWithText("Average time before heart rate went above target").assertIsDisplayed()
        composeRule.onNodeWithText("Longest interval with no trigger").assertIsDisplayed()
        composeRule.onNodeWithText("Raw Interval Data").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Interval 1").assertIsDisplayed()

        // The collapse vocabulary is gone from the screen, not just from the summary card (#169).
        listOf("Average completion", "Severe breakdown", "Poor tolerance", "Strained completion", "Completion band")
            .forEach { composeRule.onAllNodesWithText(it).assertCountEquals(0) }
    }

    @Test
    fun sessionDetailScreen_offersShareForARunWithAGpsTrack() {
        var sharedSessionId: Long? = null
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = plainSession(),
                    samples = emptyList(),
                    intervalStats = emptyList(),
                    onDeleteSession = {},
                    onBack = {},
                    canShareGpx = true,
                    onShareGpx = { sharedSessionId = it }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Share run as GPX").assertIsDisplayed().performClick()
        assertEquals(1L, sharedSessionId)
    }

    @Test
    fun sessionDetailScreen_hidesShareForARunWithNoGpsTrack() {
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = plainSession(),
                    samples = emptyList(),
                    intervalStats = emptyList(),
                    onDeleteSession = {},
                    onBack = {},
                    canShareGpx = false
                )
            }
        }

        composeRule.onNodeWithContentDescription("Delete run").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Share run as GPX").assertCountEquals(0)
    }

    private fun plainSession() = RunnerSession(
        id = 1L,
        startTime = 1_742_000_000_000,
        durationSeconds = 1800,
        avgBpm = 130,
        maxBpm = 150,
        targetZone = 2
    )
}
