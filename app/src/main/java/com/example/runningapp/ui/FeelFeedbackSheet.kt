package com.example.runningapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeelFeedbackSheet(
    onSave: (Int?, String?) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var sliderPosition by remember { mutableStateOf(5f) }
    var effortChosen by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    val selectedEffort = if (effortChosen) sliderPosition.roundToInt() else null

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
                    onClick = { onSave(selectedEffort, note.trim().ifBlank { null }) },
                    enabled = selectedEffort != null || note.isNotBlank()
                ) {
                    Text("Save")
                }
            }
        }
    }
}
