package com.example.runningapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * What the runner has to say about the Run just finished — and, on a treadmill Run, the one thing
 * only they can tell the app (#231).
 *
 * [askForDistance] puts the console's number in front of the runner while they are still standing on
 * the machine looking at it, which is the only moment it is free. It stays optional, like everything
 * else here: a Run with no distance is a Run with no distance, and the Run's own page can be told
 * later (ADR 0008).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeelFeedbackSheet(
    onSave: (Int?, String?, Double?) -> Unit,
    onDismiss: () -> Unit,
    askForDistance: Boolean = false
) {
    val sheetState = rememberModalBottomSheetState()
    var sliderPosition by remember { mutableStateOf(5f) }
    var effortChosen by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var typedDistance by remember { mutableStateOf("") }

    val selectedEffort = if (effortChosen) sliderPosition.roundToInt() else null
    val statedDistance = statedDistanceKmOf(typedDistance)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("How did that feel?", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "Optional — skip if you'd rather not log it.",
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            // First, and above the effort slider: it is the one field here the runner cannot fill in
            // later from memory, because the console clears itself the moment they step off.
            if (askForDistance) {
                OutlinedTextField(
                    value = typedDistance,
                    onValueChange = { typedDistance = it },
                    label = { Text("Distance on the console (km)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            Text(
                text = selectedEffort?.let { "Effort: $it / 10" } ?: "Tap the slider to rate your effort",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Slider(
                value = sliderPosition,
                onValueChange = {
                    sliderPosition = it
                    effortChosen = true
                },
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

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Skip")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onSave(selectedEffort, note.trim().ifBlank { null }, statedDistance) },
                    enabled = selectedEffort != null || note.isNotBlank() || statedDistance != null
                ) {
                    Text("Save")
                }
            }
        }
    }
}
