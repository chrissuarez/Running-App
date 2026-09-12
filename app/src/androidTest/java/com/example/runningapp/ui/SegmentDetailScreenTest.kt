package com.example.runningapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.runningapp.data.Segment
import com.example.runningapp.ui.theme.RunningAppTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Segment's page while its own delete is running (#414).
 *
 * Every effort row on this page is a door to a Run's page, and the pop that follows the delete
 * takes whatever is above this page with it. So the page has to close those doors first.
 */
@RunWith(AndroidJUnit4::class)
class SegmentDetailScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun segment() = Segment(
        id = 7L,
        name = "The hill",
        polyline = "",
        distanceMeters = 800.0,
        sourceSessionId = 1L,
        createdAtMillis = 1_754_300_000_000L,
    )

    private fun effort() = SegmentEffortUi(
        effortId = 1L,
        sessionId = 42L,
        date = LocalDate.of(2026, 9, 1),
        dateLabel = "1 Sep",
        timeLabel = "4:32",
        paceLabel = "5:40 /km",
        elapsedMillis = 272_000L,
        startedAtMillis = 1_754_300_000_000L,
        isRecord = true,
    )

    @Test
    fun segmentDetailScreen_closesItsForwardDoorsWhileTheDeleteRuns() {
        var deleted = 0L
        composeRule.setContent {
            RunningAppTheme {
                SegmentDetailScreen(
                    segment = segment(),
                    efforts = listOf(effort()),
                    onRename = { _, _ -> },
                    onDelete = { deleted = it.id },
                    onOpenRun = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Delete segment").performClick()
        composeRule.onNodeWithText("Delete").performClick()

        assertEquals(7L, deleted)
        composeRule.onNodeWithText("Deleting this segment…").assertIsDisplayed()
        // The effort rows are doors to a Run's page, so they are gone rather than tappable.
        composeRule.onAllNodesWithText("4:32").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Rename segment").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Delete segment").assertIsNotEnabled()
    }

    /** The way back stays open, so a delete that never lands is not a trap. */
    @Test
    fun segmentDetailScreen_stillOffersBackWhileTheDeleteRuns() {
        var backs = 0
        composeRule.setContent {
            RunningAppTheme {
                SegmentDetailScreen(
                    segment = segment(),
                    efforts = listOf(effort()),
                    onRename = { _, _ -> },
                    onDelete = {},
                    onOpenRun = {},
                    onBack = { backs++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Delete segment").performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }
}
