package com.example.runningapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    var selectedEffort by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf("") }
    var typedDistance by remember { mutableStateOf("") }

    val statedDistance = statedDistanceKmOf(typedDistance)
    // A distance typed but not understood holds Save shut, even when there is an effort or a note to
    // save alongside it. Saving around it would dismiss the sheet and drop the number the runner is
    // looking at on the console, with nothing said and no way back to it.
    val distanceRejected = statedDistanceIsRejected(typedDistance)

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
                StatedDistanceField(
                    typed = typedDistance,
                    label = "Distance on the console (km)",
                    onTyped = { typedDistance = it }
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            // The same control the Run's own page asks the same question with (#80).
            EffortSlider(
                effort = selectedEffort,
                onEffortChosen = { selectedEffort = it }
            )

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
                    onClick = { onSave(selectedEffort, feelNoteOf(note), statedDistance) },
                    enabled = !distanceRejected &&
                        (selectedEffort != null || note.isNotBlank() || statedDistance != null)
                ) {
                    Text("Save")
                }
            }
        }
    }
}
