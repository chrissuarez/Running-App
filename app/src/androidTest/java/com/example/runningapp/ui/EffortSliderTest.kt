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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A tap and a drag are the same act to the runner, so the slider has to treat them as one (#158).
 *
 * The defect these guard against is that a tap landing on the value the thumb already rests at
 * changes nothing, so a slider listening only for changes hears nothing — and that is where an
 * untouched slider sits, so it is the tap a first-time runner is likeliest to make.
 */
@RunWith(AndroidJUnit4::class)
class EffortSliderTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val chosenEffort = mutableStateOf<Int?>(null)

    private fun isSlider() = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    /** Where along the track a given effort sits, on the one-to-ten the slider is asked on. */
    private fun fractionOf(effort: Int) = (effort - 1) / 9f

    private fun showSlider() {
        composeRule.setContent {
            RunningAppTheme {
                EffortSlider(
                    effort = chosenEffort.value,
                    onEffortChosen = { chosenEffort.value = it }
                )
            }
        }
    }

    @Test
    fun effortSlider_ratesTheRunOnATapThatMovesNothing() {
        showSlider()
        composeRule.onNodeWithText("Tap the slider to rate your effort").assertIsDisplayed()

        // Five is where the untouched thumb rests, so this tap changes no value at all — which is
        // the whole of the defect: a slider listening only for changes counts it as nothing.
        composeRule.onNode(isSlider()).performTouchInput {
            click(Offset(width * fractionOf(5), center.y))
        }

        composeRule.runOnIdle { assertEquals(5, chosenEffort.value) }
        composeRule.onNodeWithText("Effort: 5 / 10").assertIsDisplayed()
    }

    @Test
    fun effortSlider_reportsTheEffortUnderTheThumb() {
        showSlider()

        composeRule.onNode(isSlider()).performTouchInput {
            click(Offset(width - 1f, center.y))
        }

        composeRule.runOnIdle { assertEquals(10, chosenEffort.value) }
        composeRule.onNodeWithText("Effort: 10 / 10").assertIsDisplayed()
    }
}
