package com.example.runningapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.data.STATED_DISTANCE_ROUNDING_METERS
import com.example.runningapp.data.StatedBestEffort
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * The time the console showed, as the runner types it — whole seconds, or null when it is not a time
 * (#282).
 *
 * A colon is required, and that is the whole of the format: `25:30` is twenty-five and a half
 * minutes and `1:25:30` is an hour more. A bare `2530` is refused rather than guessed at, because
 * there is no reading of it that is obviously right — a lone `4` against a 1 km is four minutes to
 * one runner and four seconds to another, and a Best Effort is not a number to guess at. It is also
 * exactly how the app writes a time back out ([formatDuration]), so what is typed here and what is
 * read on the record book are one format.
 *
 * Seconds must be two digits under sixty, and so must the minutes where an hour is given — `25:75`
 * is not a time anybody's console showed.
 */
fun statedEffortSecondsOf(typed: String): Int? {
    val parts = typed.trim().split(':')
    if (parts.size !in 2..3) return null
    if (parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
    // Every part but the first is a two-digit place in a bigger unit, so it is written out in full
    // and it never reaches sixty.
    val tail = parts.drop(1)
    if (tail.any { it.length != 2 || it.toInt() >= 60 }) return null
    val numbers = parts.map { it.toInt() }
    val seconds = numbers.fold(0L) { total, part -> total * 60 + part }
    return seconds.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
}

/** True when what is typed was meant to be a time and is not one — blank says nothing, as ever. */
fun statedEffortIsRejected(typed: String): Boolean =
    typed.isNotBlank() && statedEffortSecondsOf(typed) == null

/**
 * Which record distances this Run could hold a claim at, given what it is (#282).
 *
 * The screen's copy of the refusal the repository makes
 * ([com.example.runningapp.data.SessionRepository.stateBestEffort]), so a distance the Run cannot
 * contain is never offered in the first place — a Save that silently did nothing would be worse than
 * a chip that was never there. The repository still refuses it, because a screen is not where a rule
 * lives.
 *
 * Every distance is offerable on a Run nobody stated a distance for: the two statements are
 * independent, and a runner who noted only the 5 km split has still said something true.
 */
fun recordDistancesWithin(statedDistanceKm: Double): List<RecordType> {
    val meters = statedDistanceKm * 1_000.0
    if (meters <= 0.0) return RecordType.bestEfforts
    return RecordType.bestEfforts.filter { it.distanceMeters!! <= meters + STATED_DISTANCE_ROUNDING_METERS }
}

/**
 * What a treadmill Run has been told it holds, and the way in to telling it (#282).
 *
 * Shown on a finished treadmill Run whether or not anything has been stated, for the same reason the
 * feel card is: a card that only appeared once it had something to show would give a runner who has
 * never used it no way to find it. It is hidden only where there is nothing it could ever hold — a
 * Run stated at 600 metres contests no record distance, and offering it five chips it must refuse
 * would be a card that exists to say no.
 *
 * **Each row is tappable in its whole width** rather than carrying an edit button. At 320dp with the
 * system text turned up there is no room for a text column and an icon beside it, and #82 shipped a
 * delete icon crushed to a single pixel exactly that way; a row that *is* the target cannot be
 * crushed.
 */
@Composable
fun StatedBestEffortsCard(
    stated: List<StatedBestEffort>,
    runDurationSeconds: Long,
    statedDistanceKm: Double,
    onState: (RecordType, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val offerable = recordDistancesWithin(statedDistanceKm)
    val claims = stated.filter { it.type in offerable }.sortedBy { it.type.ordinal }
    if (offerable.isEmpty() && claims.isEmpty()) return

    var editing by remember { mutableStateOf<RecordType?>(null) }
    var adding by remember { mutableStateOf(false) }

    val open = editing
    if (open != null || adding) {
        StatedBestEffortDialog(
            editing = open,
            // Only distances nothing has been said about yet, because stating one that is already
            // there is correcting it, and the row for it is right there on the card.
            offerable = offerable.filter { type -> claims.none { it.type == type } },
            secondsAlready = claims.firstOrNull { it.type == open }?.seconds,
            runDurationSeconds = runDurationSeconds,
            onDismiss = { editing = null; adding = false },
            onState = { type, seconds ->
                editing = null
                adding = false
                onState(type, seconds)
            },
        )
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
            Text(
                "The treadmill has no route to measure, so it can only be told. A time stated here " +
                    "places in the record book like any other.",
                style = MaterialTheme.typography.bodySmall,
            )
            claims.forEach { claim ->
                Spacer(modifier = Modifier.height(12.dp))
                StatedEffortRow(
                    label = claim.type.label,
                    value = formatDuration(claim.seconds.toLong()),
                    onClick = { editing = claim.type },
                )
            }
            if (claims.size < offerable.size) {
                Spacer(modifier = Modifier.height(12.dp))
                StatedEffortRow(
                    label = if (claims.isEmpty()) "Add a best effort" else "Add another",
                    value = "",
                    onClick = { adding = true },
                )
            }
        }
    }
}

@Composable
private fun StatedEffortRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // The whole row is the target, so it is held to the size a finger needs even when the
            // text inside it is small.
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = if (value.isEmpty()) label else "$label, $value, tap to change"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Asks for a time the console showed, or corrects one already stated (#282).
 *
 * [editing] is the record being corrected, or null when a new claim is being made — which is the one
 * thing that changes: a correction is about a distance already chosen, so the chips are not offered
 * again and the claim cannot quietly become one about a different stretch.
 *
 * The field says why a Save cannot be pressed, including the one refusal that is about this Run
 * rather than about the format: a time longer than the Run itself is not a claim about this Run at
 * all. An *implausible* time is not refused here or anywhere — it is believed, and corrected the
 * same way it was stated.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StatedBestEffortDialog(
    editing: RecordType?,
    offerable: List<RecordType>,
    secondsAlready: Int?,
    runDurationSeconds: Long,
    onDismiss: () -> Unit,
    onState: (RecordType, Int?) -> Unit,
) {
    var chosen by remember { mutableStateOf(editing ?: offerable.firstOrNull()) }
    var typed by remember {
        mutableStateOf(secondsAlready?.let { formatDuration(it.toLong()) }.orEmpty())
    }
    val seconds = statedEffortSecondsOf(typed)
    val tooLong = seconds != null && seconds > runDurationSeconds
    val rejected = statedEffortIsRejected(typed)
    val type = chosen

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing != null) "Correct the time" else "What did the console show?") },
        text = {
            Column {
                if (editing != null) {
                    Text(editing.label, style = MaterialTheme.typography.titleSmall)
                } else {
                    Text(
                        "Which distance, and the time beside it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        offerable.forEach { option ->
                            FilterChip(
                                selected = option == chosen,
                                onClick = { chosen = option },
                                label = { Text(option.chipLabel) },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Time") },
                    singleLine = true,
                    isError = rejected || tooLong,
                    supportingText = when {
                        tooLong -> {
                            {
                                Text(
                                    "Longer than the run itself (${formatDuration(runDurationSeconds)})"
                                )
                            }
                        }
                        rejected -> {
                            { Text("Enter a time like 25:30, or 1:25:30") }
                        }
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (type != null && seconds != null) onState(type, seconds) },
                enabled = type != null && seconds != null && !tooLong,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                // Offered only where there is something to take back — a Run that was never told
                // this distance is already where Remove would leave it.
                if (editing != null) {
                    TextButton(onClick = { onState(editing, null) }) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * The record distance as a chip says it: the distance alone.
 *
 * "Fastest 5 km" is what the record book calls the record, and it is the right name there. On a chip
 * in a row of five the word is on every one of them, and at 320dp with the text turned up five chips
 * saying "Fastest" wrap to five lines to say nothing.
 */
private val RecordType.chipLabel: String
    get() = label.removePrefix("Fastest ").replaceFirstChar(Char::uppercase)
