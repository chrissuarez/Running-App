package com.example.runningapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.runningapp.ui.theme.RunningAppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A tap and a drag are the same act to the runner, so the slider has to treat them as one (#158).
 *
 * The defect these guard against is that a tap landing on the value the thumb already rests at
 * changes nothing, so a slider listening only for changes hears nothing — and it lands there most
 * often for a runner who has not touched the control yet and taps the middle.
 */
@RunWith(AndroidJUnit4::class)
class EffortSliderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val chosen = mutableStateOf<Int?>(null)

    private fun isSlider() = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    private fun showSlider() {
        composeRule.setContent {
            RunningAppTheme {
                EffortSlider(effort = chosen.value, onEffortChosen = { chosen.value = it })
            }
        }
    }

    @Test
    fun tappingTheMiddleOfTheTrackRatesTheRun() {
        showSlider()
        composeRule.onNodeWithText("Tap the slider to rate your effort").assertIsDisplayed()

        composeRule.onNode(isSlider()).performTouchInput { click(center) }

        // The middle of a one-to-ten track is where the untouched thumb already sits, which is
        // exactly the tap that used to count for nothing.
        composeRule.runOnIdle {
            assertNotNull("a tap on the middle of the track left the run unrated", chosen.value)
        }
    }

    @Test
    fun theEffortReportedIsTheOneUnderTheThumb() {
        showSlider()

        composeRule.onNode(isSlider()).performTouchInput {
            click(Offset(width - 1f, center.y))
        }

        composeRule.runOnIdle { assertEquals(10, chosen.value) }
        composeRule.onNodeWithText("Effort: 10 / 10").assertIsDisplayed()
    }
}
