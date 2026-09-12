package com.example.runningapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.run.RunMode
import com.example.runningapp.ui.theme.RunningAppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What a History row says about what its Run cost (#62). */
@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun row(id: Long, effortScore: Int?) = HistoryRow(
        session = RunnerSession(
            id = id,
            startTime = 1_754_300_000_000L + id * 86_400_000L,
            endTime = 1_754_300_000_000L + id * 86_400_000L + 2_700_000L,
            durationSeconds = 2_700,
            avgBpm = 148,
            maxBpm = 171,
            runMode = RunMode.OUTDOOR.settingValue,
            distanceKm = 8.2,
            targetZone = 2,
            zone2Seconds = 1_992,
            effortScore = effortScore,
        ),
        medals = 0,
        thumbnail = null,
    )

    private fun showRows(rows: List<HistoryRow>) {
        composeRule.setContent {
            RunningAppTheme {
                HistoryScreen(
                    rows = rows,
                    selectedSessionIds = emptySet(),
                    onToggleSelection = {},
                    onClearSelection = {},
                    onDeleteSelected = {},
                    onSessionClick = {},
                    onBack = {},
                )
            }
        }
    }

    private fun pressSystemBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun historyRow_showsTheEffortScoreOfARunThatHasOne() {
        showRows(listOf(row(id = 1L, effortScore = 142)))

        // By its name rather than by its "⚡ 142", because the name is the part that has to be right:
        // the row has no room to spell "Effort Score" out, so the screen reader is where it is said.
        composeRule.onNodeWithContentDescription("Effort Score 142").assertIsDisplayed()
    }

    @Test
    fun historyRow_showsAScoreOfZeroRatherThanHidingIt() {
        // A Run spent entirely below Zone 1 cost nothing, and that is a measurement.
        showRows(listOf(row(id = 1L, effortScore = 0)))

        composeRule.onNodeWithContentDescription("Effort Score 0").assertIsDisplayed()
    }

    @Test
    fun historyRow_saysNothingAboutARunThatHasNoScore() {
        showRows(listOf(row(id = 1L, effortScore = null), row(id = 2L, effortScore = 55)))

        composeRule.onAllNodesWithContentDescription("Effort Score", substring = true)
            .assertCountEquals(1)
    }

    @Test
    fun systemBack_clearsTheSelectionAndDoesNotLeaveHistory() {
        // The bug in #416: the selection lives in a view model that outlives this screen, so a Back
        // that leaves while rows are held comes back to an armed Delete button nobody armed.
        var cleared = 0
        var left = 0
        composeRule.setContent {
            RunningAppTheme {
                HistoryScreen(
                    rows = listOf(row(id = 1L, effortScore = 100)),
                    selectedSessionIds = setOf(1L),
                    onToggleSelection = {},
                    onClearSelection = { cleared++ },
                    onDeleteSelected = {},
                    onSessionClick = {},
                    onBack = { left++ },
                )
            }
        }

        pressSystemBack()

        assertEquals(1, cleared)
        assertEquals(0, left)
    }

    @Test
    fun systemBack_isNotThisScreensWhenNothingIsSelected() {
        // With no selection the screen intercepts nothing, so Back is Navigation's again and pops
        // the page. Where it lands is not this screen's business (#412), so all that is asserted
        // here is that History does not swallow the press.
        var cleared = 0
        composeRule.setContent {
            RunningAppTheme {
                HistoryScreen(
                    rows = listOf(row(id = 1L, effortScore = 100)),
                    selectedSessionIds = emptySet(),
                    onToggleSelection = {},
                    onClearSelection = { cleared++ },
                    onDeleteSelected = {},
                    onSessionClick = {},
                    onBack = {},
                )
            }
        }

        pressSystemBack()

        assertEquals(0, cleared)
    }
}
