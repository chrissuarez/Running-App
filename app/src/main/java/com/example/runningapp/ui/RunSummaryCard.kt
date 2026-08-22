package com.example.runningapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * What a Run's page says about the Run in words (#76).
 *
 * The words are written once, the first time the Run is opened, and read from the database for ever
 * afterwards — so the ordinary case is a card that is simply *there*, instantly, with no sign that
 * anything was ever fetched. Everything else in this file is about the two moments that are not
 * ordinary: the first open, and the open that could not reach the model.
 *
 * **A missing summary never blocks reviewing a Run.** The card is one card among many on a page full
 * of measurements the app made itself, and none of those need this one to have worked. So a failure
 * is a quiet line and a button, never a dialog and never an error the runner has to dismiss to get
 * at their own run.
 */

/** What the card is showing, worked out from the three things the page knows. */
data class RunSummaryUi(
    /** The words, or null where none have been written. */
    val text: String?,
    /** Whether the model is being asked right now. */
    val isWriting: Boolean,
    /** Whether the last ask came back with nothing. */
    val failed: Boolean,
    /** Whether the app declined to ask at all — AI sharing off, or a Run recorded under an opt-out. */
    val refused: Boolean = false,
    /**
     * Whether everything the words would describe has been measured yet (#76).
     *
     * True wherever nothing supplies it, which is the honest default for a card built from words
     * that are already written: the first ask cannot have happened without it.
     */
    val factsSettled: Boolean = true,
) {
    /**
     * Whether there is a card here at all.
     *
     * Stated once and read by both the card and the page around it, because "is there anything to
     * draw" and "is there anything to leave a gap under" are the same question — asked in two places
     * they drift, and the page ends up with a 24dp hole where a card is not.
     */
    val hasSomethingToSay: Boolean get() = text != null || isWriting || failed || refused

    /**
     * Whether asking again is worth offering.
     *
     * Never while an ask is in flight, and never after a refusal: the app is not asking because it
     * may not, and a button that can only refuse again is worse than no button. A failure is the
     * opposite — trying again is exactly the thing that might work.
     *
     * And never before the Run's facts have settled (#76). The new words replace the old ones and
     * are then kept for ever, so a re-ask made while the medals or the route comparisons are still
     * being worked out would freeze a half-measured account of the Run in place — the same
     * permanent wrong the first ask waits to avoid, reached by a button. Not offered rather than
     * offered-and-ignored: a button that does nothing for ten seconds reads as broken, while one
     * that appears a moment later reads as a page still filling in.
     */
    val canAskAgain: Boolean get() = !isWriting && !refused && factsSettled
}

/** What the card is titled. */
const val RUN_SUMMARY_TITLE: String = "This run"

/** What it says while the words are being written. */
const val RUN_SUMMARY_WRITING_MESSAGE: String = "Reading this run…"

/** What it says when they could not be. */
const val RUN_SUMMARY_FAILED_MESSAGE: String =
    "Couldn't write a summary for this run — you may be offline. Everything else on this page is here."

/** What it says when the app may not ask — a quiet statement, with no button under it. */
const val RUN_SUMMARY_REFUSED_MESSAGE: String =
    "No summary for this run: AI sharing is switched off, or this run was recorded while it was."

/** The button that asks again after a failure. */
const val RUN_SUMMARY_RETRY_LABEL: String = "Try again"

/** The button that asks for different words when some are already written. */
const val RUN_SUMMARY_REGENERATE_LABEL: String = "Write it again"

/**
 * Draws the summary, or nothing at all.
 *
 * Nothing at all is the right answer for more of a runner's history than any other: a Run whose
 * facts are still being measured, a Run recorded while AI sharing was off, and — until the ask lands
 * — every Run in a history nobody has browsed. An empty framed card on all of those would be a
 * section that exists to apologise.
 */
@Composable
fun RunSummaryCard(
    summary: RunSummaryUi,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!summary.hasSomethingToSay) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(RunningUiTokens.CardPadding)) {
            Text(
                text = RUN_SUMMARY_TITLE,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // The words win over the spinner: asking again while some are already written leaves
            // them on screen, because taking them away for a spinner is losing something the runner
            // had in exchange for something they may not get.
            summary.text?.let { text ->
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
            }

            if (summary.isWriting) {
                if (summary.text != null) Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.semantics { contentDescription = RUN_SUMMARY_WRITING_MESSAGE },
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = RUN_SUMMARY_WRITING_MESSAGE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (summary.failed || summary.refused) {
                if (summary.text != null) Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (summary.refused) RUN_SUMMARY_REFUSED_MESSAGE else RUN_SUMMARY_FAILED_MESSAGE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The retry after a failure, and the re-ask once there are words to replace — one
            // button in two moods, because they are the same action. Absent only while the model is
            // being asked, when pressing it could do nothing but queue a second ask.
            if (summary.canAskAgain) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = onRegenerate,
                    modifier = Modifier.heightIn(min = RunningUiTokens.MinTouchTarget),
                ) {
                    Text(if (summary.failed) RUN_SUMMARY_RETRY_LABEL else RUN_SUMMARY_REGENERATE_LABEL)
                }
            }
        }
    }
}
