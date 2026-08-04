package com.example.runningapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/**
 * What the runner typed off the treadmill console, as kilometres — null when it is not a distance
 * (#231).
 *
 * A comma is a decimal point, because half the world's keyboards put one there and a runner typing
 * `5,2` means five point two rather than nothing at all.
 *
 * Everything else is left to the one rule: a distance is a positive, finite number of kilometres.
 * Nothing here caps it. A treadmill Run of 300 km is a typo, but so is one of 30, and the answer to
 * a typo is that a Stated Distance can be corrected — not a limit that decides for the runner where
 * belief runs out.
 */
fun statedDistanceKmOf(typed: String): Double? {
    val number = typed.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return number.takeIf { it.isFinite() && it > 0.0 }
}

/**
 * True when what is typed was meant to be a distance and is not one (#231).
 *
 * Blank is not a rejection: a runner who typed nothing has said nothing, and everywhere the field
 * appears that is allowed. It is the half-typed `5.2.1`, the `0` and the `five` that have to stop a
 * Save, because the alternative is a number the runner watched themselves type being dropped on the
 * way out.
 */
fun statedDistanceIsRejected(typed: String): Boolean =
    typed.isNotBlank() && statedDistanceKmOf(typed) == null

/** A distance already stated, as the field should show it back — empty when there is none. */
fun statedDistanceFieldText(distanceKm: Double): String =
    if (distanceKm > 0.0) "%.2f".format(distanceKm) else ""

/**
 * Where the console's number is typed, wherever it is asked for (#231).
 *
 * One field rather than two alike, because the sheet at the finish and the Run's own page are asking
 * the same question and a decimal keyboard that appeared in one place and not the other would be a
 * difference nobody chose. [label] is all that changes: at the finish the console is in front of the
 * runner, and afterwards it is not.
 *
 * The field says so itself when what is typed is not a distance, so that a Save the runner cannot
 * press is never a mystery.
 */
@Composable
fun StatedDistanceField(
    typed: String,
    label: String,
    onTyped: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rejected = statedDistanceIsRejected(typed)
    OutlinedTextField(
        value = typed,
        onValueChange = onTyped,
        label = { Text(label) },
        singleLine = true,
        isError = rejected,
        supportingText = if (rejected) {
            { Text("Enter a distance in kilometres, like 5.2") }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Asks for the number the console showed, or corrects one already stated (#231).
 *
 * Correctable rather than write-once, because a stated distance reaches the volume, the coach and
 * the record book: a mistyped one is not a cosmetic error. Withdrawing is offered on the same terms
 * — a Run nobody stated a distance for has no distance, which is a thing this dialog has to be able
 * to get back to.
 *
 * What a correction can and cannot put right is settled in the repository
 * ([com.example.runningapp.data.SessionRepository.stateDistance]) and in ADR 0008; nothing about it
 * is decided here.
 */
@Composable
fun StatedDistanceDialog(
    distanceKm: Double,
    onDismiss: () -> Unit,
    onState: (Double?) -> Unit,
) {
    var typed by remember { mutableStateOf(statedDistanceFieldText(distanceKm)) }
    val stated = statedDistanceKmOf(typed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (distanceKm > 0.0) "Correct the distance" else "How far did you go?") },
        text = {
            Column {
                Text(
                    "The distance on the treadmill's console. It counts everywhere a measured one " +
                        "does — your pace, your weekly volume, what the coach sees, and the record book.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                StatedDistanceField(
                    typed = typed,
                    label = "Distance (km)",
                    onTyped = { typed = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { stated?.let(onState) },
                enabled = stated != null
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                // Offered only where there is something to remove: a runner correcting a number is
                // the one who might want it gone entirely, and a Run with no distance is already
                // where Remove would leave it.
                if (distanceKm > 0.0) {
                    TextButton(onClick = { onState(null) }) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
