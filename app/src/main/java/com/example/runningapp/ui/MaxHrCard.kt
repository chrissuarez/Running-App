package com.example.runningapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.runningapp.MAX_STATABLE_AGE
import com.example.runningapp.MIN_STATABLE_AGE
import com.example.runningapp.RESTING_HR_UNSTATED
import com.example.runningapp.maxHrForAge
import com.example.runningapp.parseAge
import com.example.runningapp.parseMaxHr
import com.example.runningapp.suggestedMaxHrForAge
import com.example.runningapp.ui.theme.RunningAppTheme
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * What the confirmation card needs to know, which is the runner's profile and their own evidence.
 *
 * [suggestedMaxHr] null is a phone with nothing recorded — the card asks for an age there, and
 * offers `220 − age`. Everything else is the same card.
 */
data class MaxHrCardState(
    val currentMaxHr: Int,
    val restingHr: Int = RESTING_HR_UNSTATED,
    val suggestedMaxHr: Int? = null,
)

/**
 * The one time this app asks the runner about their heart (#65).
 *
 * It exists because every zone edge, every Effort Score and every reading of how training is going
 * hangs off a number nobody has ever stated — the placeholder maximum the app ships with. Asked
 * once, on the screen where those numbers are read, and then never again however it is answered:
 * confirming and closing both put it away for good, which is what "calibration with zero nagging"
 * comes to in practice.
 *
 * Three ways out, and all three are the runner's:
 * - **Save** the number in the field, which starts at their own recorded maximum;
 * - **Keep** the number their zones are on now, which is a deliberate statement and not a shrug —
 *   it says "190 is right", and history is worked out against it exactly as a typed number would be;
 * - **close it**, which states nothing at all.
 *
 * Only the first two are a statement, and the card says plainly what a statement does before it is
 * made: the first one re-works history and every one after it counts for future runs only. That
 * sentence is the whole of what makes a one-shot honest — a runner who is told costs nothing to
 * ask again, and one who is not finds out by reading a history that changed under them.
 */
@Composable
fun MaxHrConfirmationCard(
    state: MaxHrCardState,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    // Started at the runner's own recorded maximum, so the commonest answer — "yes, that is me" —
    // is one tap. Blank where there is nothing recorded: a prefilled placeholder would be the app
    // suggesting the very number it is asking about.
    // Keyed on the suggestion, so a suggestion that moves — the runner stating a resting heart rate
    // beside it — takes the untouched field with it rather than leaving a number the field beneath
    // would now refuse.
    var typed by remember(state.suggestedMaxHr) {
        mutableStateOf(state.suggestedMaxHr?.toString() ?: "")
    }
    var refused by remember { mutableStateOf(false) }
    var age by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RunningUiTokens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Confirm your max heart rate",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss")
                }
            }
            Text(
                "Your zones and your Effort scores are worked out from this number. Setting it now " +
                    "works your past runs out again to match. After that, changes count for new " +
                    "runs only, so your history stays as you read it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            val suggested = state.suggestedMaxHr
            if (suggested != null) {
                Text(
                    "The highest we have recorded from you is $suggested BPM. Your zones are on " +
                        "${state.currentMaxHr}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "We have not recorded a heart rate from you yet. Your age gives a rough " +
                        "starting point — a proper max HR test beats it whenever you do one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val statedAge = parseAge(age)
                // Through the same rule the field beneath it applies, so the age branch cannot
                // offer what Save would then refuse: `220 − age` runs out of reserve at the old
                // end of the range once a resting heart rate is stated.
                val fromAge = statedAge?.let { suggestedMaxHrForAge(it, state.restingHr) }
                OutlinedTextField(
                    value = age,
                    onValueChange = { age = it },
                    label = { Text("Your age") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            when {
                                fromAge != null -> "Age $statedAge suggests $fromAge BPM"
                                // Said rather than left as a missing button: an age that gives
                                // nothing usable is a dead end otherwise, with no way to tell it
                                // from a number that has not been typed yet.
                                statedAge != null ->
                                    "Age $statedAge gives ${maxHrForAge(statedAge)} BPM, which " +
                                        "leaves no room above your resting ${state.restingHr}. " +
                                        "Type your own number below."
                                else -> "Between $MIN_STATABLE_AGE and $MAX_STATABLE_AGE"
                            }
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (fromAge != null) {
                    // Fills the field rather than committing, so the suggestion is still a number
                    // the runner looked at and pressed Save on. Nothing here is stated by arithmetic.
                    TextButton(onClick = {
                        typed = fromAge.toString()
                        refused = false
                    }) {
                        Text("Use $fromAge")
                    }
                }
            }
            OutlinedTextField(
                value = typed,
                onValueChange = {
                    typed = it
                    refused = false
                },
                label = { Text("Max HR") },
                singleLine = true,
                isError = refused,
                supportingText = {
                    if (refused) Text(maxHrRefusalText(state.restingHr))
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = {
                    // Refused where the runner can see it and the field keeps what they typed —
                    // the same bargain the settings screen makes, because this is the same number
                    // and a silently rounded maximum is the failure #172 deleted there.
                    val stated = parseMaxHr(typed, state.restingHr)
                    if (stated == null) refused = true else onConfirm(stated)
                }) {
                    Text("Save")
                }
                TextButton(onClick = { onConfirm(state.currentMaxHr) }) {
                    Text("Keep ${state.currentMaxHr}")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MaxHrCardWithRecordedPeakPreview() {
    RunningAppTheme {
        MaxHrConfirmationCard(
            state = MaxHrCardState(currentMaxHr = 190, restingHr = 60, suggestedMaxHr = 181),
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MaxHrCardWithoutHistoryPreview() {
    RunningAppTheme {
        MaxHrConfirmationCard(
            state = MaxHrCardState(currentMaxHr = 190),
            onConfirm = {},
            onDismiss = {},
        )
    }
}
