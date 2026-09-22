package com.example.runningapp

import com.example.runningapp.run.AcquisitionPhase
import com.example.runningapp.run.AcquisitionState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.ui.theme.RunningUiTokens

@Composable
internal fun RunModeSelector(runMode: String, onRunModeChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { onRunModeChange("treadmill") },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = RunningUiTokens.MinTouchTarget),
            colors = if (runMode == "treadmill") {
                ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            } else {
                ButtonDefaults.outlinedButtonColors()
            }
        ) {
            Text("Treadmill")
        }
        OutlinedButton(
            onClick = { onRunModeChange("outdoor") },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = RunningUiTokens.MinTouchTarget),
            colors = if (runMode == "outdoor") {
                ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            } else {
                ButtonDefaults.outlinedButtonColors()
            }
        ) {
            Text("Outdoor")
        }
    }
}

// The bottom of the record screen (#110): one quiet sensor line that states a single fact and
// vanishes when nothing is wrong, above an always-live START. Heart rate is a sensor, not a
// gate — START never dies; when there's no strap it says what you'll lose, and starts anyway.
@Composable
internal fun StartFooter(
    acquisition: AcquisitionState,
    strapConnected: Boolean,
    isSimulating: Boolean,
    onStart: () -> Unit,
    onRetryStrap: () -> Unit
) {
    val connectionStatus = acquisition.statusLine
    // "Looking for your strap…" is exactly an Acquisition in flight. One definition, shared with
    // the service's START guard and with Promotion — this used to be its own copy of the test.
    val looking = !isSimulating && acquisition.inFlight
    // The terminal give-up phase (pre-run reconnect cap). The key before last was
    // contains("Retrying"), which matched a status that lived for milliseconds between retry
    // cycles — this state was designed for the strap-absent case but never actually rendered.
    val notFound = !isSimulating && acquisition.phase is AcquisitionPhase.GaveUp

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RunningUiTokens.PagePadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when {
                looking -> {
                    Text(
                        "Looking for your strap…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                notFound -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Strap not found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onRetryStrap) {
                            Text("Retry")
                        }
                    }
                }
            }

            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("START", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    // Not while `looking`: the sensor line above says "Looking for your strap…",
                    // and stating "without heart rate" at the same time contradicts it. The
                    // subtitle belongs to the settled strapless states (absent / not found).
                    if (!strapConnected && !isSimulating && !looking) {
                        Text(
                            "Without heart rate — no zone coaching",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
