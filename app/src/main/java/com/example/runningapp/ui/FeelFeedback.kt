package com.example.runningapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.runningapp.ui.theme.RunningUiTokens
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
    typedNote: String,
    /** Whether the Run is marked a Walk, and whether the switch still says so (#275). */
    storedIsWalk: Boolean = false,
    isWalk: Boolean = storedIsWalk,
): Boolean =
    effort != storedEffort ||
        feelNoteOf(typedNote) != feelNoteOf(storedNote) ||
        isWalk != storedIsWalk

/**
 * The way in, named for whether the Run has anything to say yet (#80).
 *
 * A Run marked a Walk has something said about it even with no effort and no note (#275), so the
 * way back in has to be an edit rather than an invitation to add the first thing.
 */
fun feelEditLabel(effort: Int?, note: String?, isWalk: Boolean = false): String =
    if (effort == null && feelNoteOf(note) == null && !isWalk) "Add effort / note"
    else "Edit effort / note"

/**
 * The one control that says a Run was a Walk (#275) — the same switch on the sheet at the finish and
 * on the Run's own page, because both are asking the runner the same question.
 *
 * A switch and not a mode picker: there is no taxonomy here, only a Run and the runner's word that
 * they walked it. The explanation sits under it because the consequence is not guessable — a Walk's
 * Effort Score is untouched, and what changes is the fatigue it is held to have cost and the medals
 * it is allowed to take.
 *
 * The label and the switch are one target, which is the whole row: a 20dp switch at the right-hand
 * edge is a hard thing to hit on a phone held after a run, and this is a control the runner is meant
 * to be able to reach through a winter of history with.
 */
@Composable
fun WalkSwitch(
    isWalk: Boolean,
    onIsWalkChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = isWalk,
                    role = Role.Switch,
                    onValueChange = onIsWalkChanged,
                )
                .heightIn(min = RunningUiTokens.MinTouchTarget),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "This was a walk",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            // Null, because the row above is the target: a switch with its own handler would be a
            // second toggleable inside the first, and two semantics nodes saying the same thing.
            Switch(checked = isWalk, onCheckedChange = null)
        }
        Text(
            text = "Walks build fitness in full and count far less towards fatigue. " +
                "They don't take records or complete a planned workout.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The small badge that says a Run is a Walk (#275) — one marker, drawn the same on the History row
 * and on the Run's own page.
 *
 * On the row it is what makes retagging a winter of sessions workable at all: without it there is no
 * way to see which ones have already been done short of opening every one.
 */
@Composable
fun WalkMarker(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = "Walk",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

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
            // Also on release, because a press-release is the honest signal for *the runner has
            // chosen an effort*, where a change of value is only a signal for *something moved*.
            // A tap that lands on the value the slider already rests at — the middle, for a runner
            // who has not touched it yet — moves nothing, and used to count as nothing (#158).
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
    onSave: (Int?, String?, Boolean) -> Unit,
    /** Whether the Run is a Walk, and the switch that changes it — editable for ever (#275). */
    isWalk: Boolean = false,
) {
    var chosenEffort by remember { mutableStateOf(effort) }
    var typedNote by remember { mutableStateOf(note.orEmpty()) }
    var walked by remember { mutableStateOf(isWalk) }
    val hasChanges = feelEditHasChanges(
        storedEffort = effort,
        storedNote = note,
        effort = chosenEffort,
        typedNote = typedNote,
        storedIsWalk = isWalk,
        isWalk = walked,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How did that feel?") },
        text = {
            // Scrolled, because this dialog now holds a slider, a note field and a switch, and at a
            // large system text size on a narrow screen that is taller than the dialog is allowed to
            // be — buttons and all (#238).
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    // Says only what is true today: the coach is not told any of this yet (#83),
                    // and the Effort score the app computes is heart rate's, not this number's.
                    "Your effort and note are your own words about this run, kept alongside it. " +
                        "They never change what your heart rate says about it.",
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
                // Last, and set apart from the words above it: unlike an effort or a note, this one
                // changes what the Run is worth — to the record book and to the curves (#275).
                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))
                WalkSwitch(isWalk = walked, onIsWalkChanged = { walked = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(chosenEffort, feelNoteOf(typedNote), walked) },
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
