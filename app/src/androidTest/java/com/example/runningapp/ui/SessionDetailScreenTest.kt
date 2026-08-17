package com.example.runningapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextClearance
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
import com.example.runningapp.export.ExportFormat
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
        composeRule.onNodeWithText("Average time to first trigger").assertIsDisplayed()
        composeRule.onNodeWithText("Longest interval with no trigger").assertIsDisplayed()
        composeRule.onNodeWithText("Raw Interval Data").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Interval 1").assertIsDisplayed()

        // The collapse vocabulary is gone from the screen, not just from the summary card (#169).
        listOf("Average completion", "Severe breakdown", "Poor tolerance", "Strained completion", "Completion band")
            .forEach { composeRule.onAllNodesWithText(it).assertCountEquals(0) }
    }

    @Test
    fun sessionDetailScreen_offersBothFormatsForARunWithAGpsTrack() {
        var shared: Pair<Long, ExportFormat>? = null
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = plainSession(),
                    samples = emptyList(),
                    intervalStats = emptyList(),
                    onDeleteSession = {},
                    onBack = {},
                    shareableFormats = listOf(ExportFormat.FIT, ExportFormat.GPX),
                    onShareRun = { id, format -> shared = id to format }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Share run").assertIsDisplayed().performClick()
        // FIT first: it is the better file, and the one Garmin reads whole.
        composeRule.onNodeWithText("GPX").assertIsDisplayed()
        composeRule.onNodeWithText("Garmin (.fit)").assertIsDisplayed().performClick()
        assertEquals(1L to ExportFormat.FIT, shared)
    }

    @Test
    fun sessionDetailScreen_offersOnlyFitForARunWithNoGpsTrack() {
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = plainSession(),
                    samples = emptyList(),
                    intervalStats = emptyList(),
                    onDeleteSession = {},
                    onBack = {},
                    shareableFormats = listOf(ExportFormat.FIT)
                )
            }
        }

        composeRule.onNodeWithContentDescription("Share run").performClick()
        composeRule.onNodeWithText("Garmin (.fit)").assertIsDisplayed()
        composeRule.onAllNodesWithText("GPX").assertCountEquals(0)
    }

    @Test
    fun sessionDetailScreen_hidesShareForARunThatCanBeNeitherFile() {
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = plainSession(),
                    samples = emptyList(),
                    intervalStats = emptyList(),
                    onDeleteSession = {},
                    onBack = {},
                    shareableFormats = emptyList()
                )
            }
        }

        composeRule.onNodeWithContentDescription("Delete run").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Share run").assertCountEquals(0)
    }

    // --- Saying afterwards how a Run felt (#80) ------------------------------------------------

    @Test
    fun sessionDetailScreen_offersAWayInForARunNothingWasSaidAbout() {
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = finishedSession(),
                    samples = emptyList(),
                    intervalStats = emptyList(),
                    onDeleteSession = {},
                    onBack = {},
                    onSaveFeelFeedback = { _, _, _, _ -> }
                )
            }
        }

        // The sheet at the finish is skippable, so a Run nobody rated still has to be ratable.
        composeRule.onNodeWithText("No effort rated").assertIsDisplayed()
        composeRule.onNodeWithText("Add effort / note").assertIsDisplayed()
    }

    @Test
    fun sessionDetailScreen_showsWhatWasSaidAndOffersToChangeIt() {
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = finishedSession(effort = 7, note = "Felt strong"),
                    samples = emptyList(),
                    intervalStats = emptyList(),
                    onDeleteSession = {},
                    onBack = {},
                    onSaveFeelFeedback = { _, _, _, _ -> }
                )
            }
        }

        composeRule.onNodeWithText("7 / 10").assertIsDisplayed()
        composeRule.onNodeWithText("Felt strong").assertIsDisplayed()
        composeRule.onNodeWithText("Edit effort / note").assertIsDisplayed()
    }

    @Test
    fun sessionDetailScreen_clearsANoteBackToNothing() {
        var savedEffort: Int? = -1
        var savedNote: String? = "untouched"
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = finishedSession(effort = 7, note = "Felt strong"),
                    samples = emptyList(),
                    intervalStats = emptyList(),
                    onDeleteSession = {},
                    onBack = {},
                    onSaveFeelFeedback = { _, effort, note, _ ->
                        savedEffort = effort
                        savedNote = note
                    }
                )
            }
        }

        composeRule.onNodeWithText("Edit effort / note").performClick()
        // The note is on screen twice with the dialog open — on the card behind it and in the field
        // being edited — so the field is asked for by being the thing that takes typing.
        composeRule.onNode(hasSetTextAction() and hasText("Felt strong")).performTextClearance()
        composeRule.onNodeWithText("Save").performClick()

        // Emptied, not blank: what reaches the repository is the absence itself.
        assertEquals(7, savedEffort)
        assertEquals(null, savedNote)
    }

    @Test
    fun sessionDetailScreen_takesBackAnEffortThatWasRatedByAccident() {
        var savedEffort: Int? = -1
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = finishedSession(effort = 7, note = "Felt strong"),
                    samples = emptyList(),
                    intervalStats = emptyList(),
                    onDeleteSession = {},
                    onBack = {},
                    onSaveFeelFeedback = { _, effort, _, _ -> savedEffort = effort }
                )
            }
        }

        composeRule.onNodeWithText("Edit effort / note").performClick()
        composeRule.onNodeWithText("Clear the effort").performClick()
        composeRule.onNodeWithText("Save").performClick()

        assertEquals(null, savedEffort)
    }

    @Test
    fun sessionDetailScreen_leavesSaveShutUntilSomethingChanges() {
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = finishedSession(effort = 7, note = "Felt strong"),
                    samples = emptyList(),
                    intervalStats = emptyList(),
                    onDeleteSession = {},
                    onBack = {},
                    onSaveFeelFeedback = { _, _, _, _ -> }
                )
            }
        }

        composeRule.onNodeWithText("Edit effort / note").performClick()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    // --- Saying a Run was a Walk (#275) ---------------------------------------------------------

    @Test
    fun sessionDetailScreen_marksARunAsAWalkFromItsOwnPage() {
        var savedIsWalk: Boolean? = null
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = finishedSession(),
                    samples = emptyList(),
                    intervalStats = emptyList(),
                    onDeleteSession = {},
                    onBack = {},
                    onSaveFeelFeedback = { _, _, _, isWalk -> savedIsWalk = isWalk }
                )
            }
        }

        composeRule.onNodeWithText("Add effort / note").performClick()
        composeRule.onNodeWithText("This was a walk").performClick()
        // The switch alone is a change, so Save opens on it with no effort and no note typed.
        composeRule.onNodeWithText("Save").performClick()

        assertEquals(true, savedIsWalk)
    }

    @Test
    fun sessionDetailScreen_showsThatARunIsAWalkAndOffersToTakeItBack() {
        var savedIsWalk: Boolean? = null
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = finishedSession().copy(isWalk = true),
                    samples = emptyList(),
                    intervalStats = emptyList(),
                    onDeleteSession = {},
                    onBack = {},
                    onSaveFeelFeedback = { _, _, _, isWalk -> savedIsWalk = isWalk }
                )
            }
        }

        // Marked on the card, and the way in is an edit rather than an invitation to add the first
        // thing — a Walk with no effort and no note has still had something said about it.
        composeRule.onNodeWithText("Walk").assertIsDisplayed()
        composeRule.onNodeWithText("Edit effort / note").performClick()
        composeRule.onNodeWithText("This was a walk").performClick()
        composeRule.onNodeWithText("Save").performClick()

        assertEquals(false, savedIsWalk)
    }

    // --- What the Run cost (#61) ----------------------------------------------------------------

    @Test
    fun sessionDetailScreen_showsTheEffortScoreOfARunThatHasOne() {
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = finishedSession().copy(effortScore = 145),
                    samples = emptyList(),
                    intervalStats = emptyList(),
                    onDeleteSession = {},
                    onBack = {}
                )
            }
        }

        composeRule.onNodeWithText("Effort Score").assertIsDisplayed()
        composeRule.onNodeWithText("145").assertIsDisplayed()
    }

    @Test
    fun sessionDetailScreen_showsAnEffortScoreOfZeroRatherThanHidingIt() {
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = finishedSession().copy(effortScore = 0),
                    samples = emptyList(),
                    intervalStats = emptyList(),
                    onDeleteSession = {},
                    onBack = {}
                )
            }
        }

        // A Run spent entirely below Zone 1 cost nothing, and that is a measurement.
        composeRule.onNodeWithText("Effort Score").assertIsDisplayed()
    }

    @Test
    fun sessionDetailScreen_saysNothingAboutARunThatWasNeverScored() {
        composeRule.setContent {
            RunningAppTheme {
                SessionDetailScreen(
                    session = finishedSession(),
                    samples = emptyList(),
                    intervalStats = emptyList(),
                    onDeleteSession = {},
                    onBack = {}
                )
            }
        }

        composeRule.onAllNodesWithText("Effort Score").assertCountEquals(0)
    }

    private fun finishedSession(effort: Int? = null, note: String? = null) = RunnerSession(
        id = 1L,
        startTime = 1_742_000_000_000,
        endTime = 1_742_000_180_000,
        durationSeconds = 1800,
        avgBpm = 130,
        maxBpm = 150,
        targetZone = 2,
        perceivedEffort = effort,
        sessionNote = note
    )

    private fun plainSession() = RunnerSession(
        id = 1L,
        startTime = 1_742_000_000_000,
        durationSeconds = 1800,
        avgBpm = 130,
        maxBpm = 150,
        targetZone = 2
    )
}
