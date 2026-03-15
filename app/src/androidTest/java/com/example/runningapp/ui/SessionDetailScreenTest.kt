package com.example.runningapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunWalkIntervalStat
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.ui.theme.RunningAppTheme
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
                        timeInTargetZoneSeconds = 1940,
                        zone1Seconds = 53,
                        zone2Seconds = 1940,
                        zone3Seconds = 277,
                        zone4Seconds = 16,
                        walkBreaksCount = 22,
                        isRunWalkMode = true,
                        sessionType = "Run/Walk"
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
        composeRule.onNodeWithText("Average completion").assertIsDisplayed()
        composeRule.onNodeWithText("Strong completion (>=90%)").assertIsDisplayed()
        composeRule.onNodeWithText("Raw Interval Data").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Interval 1").assertIsDisplayed()
        composeRule.onNodeWithText("Completion band").assertIsDisplayed()
    }
}
