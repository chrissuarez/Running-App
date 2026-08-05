package com.example.runningapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * What the runner wrote about a Run, as the Run should hold it — null when they wrote nothing (#78).
 *
 * A note of nothing but spaces is no note: a Run nobody wrote about and a Run whose note was emptied
 * are the same absence, and the page has one way of showing it.
 */
fun feelNoteOf(typed: String?): String? = typed?.trim()?.ifBlank { null }

/**
 * Whether what is in front of the runner differs from what the Run already holds (#80).
 *
 * Save is offered on a *change*, not on there being something to say: clearing a note is a change
 * and has to reach the repository, while re-opening the dialog and pressing Save on what is already
 * there must cost nothing at all.
 */
fun feelEditHasChanges(
    storedEffort: Int?,
    storedNote: String?,
    effort: Int?,
    typedNote: String
): Boolean =
    effort != storedEffort || feelNoteOf(typedNote) != feelNoteOf(storedNote)

/** The way in, named for whether the Run has anything to say yet (#80). */
fun feelEditLabel(effort: Int?, note: String?): String =
    if (effort == null && feelNoteOf(note) == null) "Add effort / note" else "Edit effort / note"

/** An effort as the page says it back, or null on a Run that was never rated. */
fun feelEffortText(effort: Int?): String? = effort?.let { "$it / 10" }

/**
 * How hard it felt, on the one to ten every surface asks it on (#78, #80).
 *
 * One control rather than two alike, because the sheet at the finish and the Run's own page are
 * asking the same question. [effort] is held by the caller: unset until the runner touches it, which
 * is what keeps an untouched slider from claiming the five it happens to be resting on.
 */
@Composable
fun EffortSlider(
    effort: Int?,
    onEffortChosen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderPosition by remember { mutableStateOf((effort ?: 5).toFloat()) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = effort?.let { "Effort: $it / 10" } ?: "Tap the slider to rate your effort",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Slider(
            value = sliderPosition,
            onValueChange = {
                sliderPosition = it
                onEffortChosen(it.roundToInt())
            },
            // Also on release, so a tap that lands on the value the slider already rests at still
            // counts as a rating rather than as nothing having happened.
            onValueChangeFinished = { onEffortChosen(sliderPosition.roundToInt()) },
            valueRange = 1f..10f,
            steps = 8
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Easy")
            Text("Moderate")
            Text("Max effort")
        }
    }
}

/**
 * Says how a Run felt, or changes what was said before (#80).
 *
 * The sheet at the finish is skippable and easy to miss, and a Run's effort and note are the runner's
 * own words about it — so the Run's page has to be able to add them later and to take them back. The
 * only asymmetry with the sheet is that here nothing left to say is itself an instruction: a note
 * cleared is a note removed, not a save with nothing in it.
 */
@Composable
fun FeelFeedbackDialog(
    effort: Int?,
    note: String?,
    onDismiss: () -> Unit,
    onSave: (Int?, String?) -> Unit,
) {
    var chosenEffort by remember { mutableStateOf(effort) }
    var typedNote by remember { mutableStateOf(note.orEmpty()) }
    val hasChanges = feelEditHasChanges(
        storedEffort = effort,
        storedNote = note,
        effort = chosenEffort,
        typedNote = typedNote
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How did that feel?") },
        text = {
            Column {
                Text(
                    // Says only what is true today: the coach is not told any of this yet (#83),
                    // and the Effort score the app computes is heart rate's, not this number's.
                    "Your own words about this run, kept alongside it. They never change what your " +
                        "heart rate says about it.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                EffortSlider(
                    effort = chosenEffort,
                    onEffortChosen = { chosenEffort = it }
                )
                // A rating can be taken back the way a note can, and for the same reason: a slider
                // records a number on the first touch, so a Run can be rated by accident. Offered
                // only where there is one to remove.
                if (chosenEffort != null) {
                    TextButton(onClick = { chosenEffort = null }) { Text("Clear the effort") }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = typedNote,
                    onValueChange = { typedNote = it },
                    label = { Text("Note (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(chosenEffort, feelNoteOf(typedNote)) },
                enabled = hasChanges
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
