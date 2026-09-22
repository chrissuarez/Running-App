package com.example.runningapp

import com.example.runningapp.run.AcquisitionPhase
import com.example.runningapp.run.AcquisitionState
import com.example.runningapp.run.ScannedStrap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map
import com.example.runningapp.ui.theme.RunningUiTokens

@Composable
fun ManageDevicesScreen(
    settings: UserSettings,
    acquisition: AcquisitionState,
    scannedDevices: List<ScannedStrap>,
    isRunActive: Boolean,
    onSetActive: (String) -> Unit,
    onRemove: (String) -> Unit,
    onConnect: (String) -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit
) {
    // No skip-today's-plan control here: under #110 connecting a strap on this screen only acquires
    // the sensor and returns to the record screen — it no longer starts a run — so the plan-skip
    // choice belongs solely to START on the record screen, where the run actually begins.
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Manage Devices", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                Text("Back")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Status: ${acquisition.statusLine}", style = MaterialTheme.typography.bodySmall)
        // Scan is the only way to pair a first strap (#110 removed the record-screen list): find
        // and tap a discovered strap here, and connecting it saves it below. Pairing is a pre-run
        // action — scanning drops the current strap, so it is disabled during an active run to keep
        // it from dropping the live session (the service ignores a mid-run scan for the same reason).
        val isScanning = acquisition.phase is AcquisitionPhase.Scanning
        Button(
            onClick = onScan,
            enabled = !isScanning && !isRunActive,
            modifier = Modifier.fillMaxWidth().heightIn(min = RunningUiTokens.MinTouchTarget)
        ) {
            Text(if (isScanning) "Scanning…" else "Scan for heart-rate strap")
        }
        if (isRunActive) {
            Text(
                "Finish your run to scan for a new strap.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Only surface discovered straps we haven't already saved, so a first pair is unambiguous.
        val savedAddresses = settings.savedDevices.map { it.address }.toSet()
        val newlyDiscovered = scannedDevices.filter { it.address !in savedAddresses }

        if (settings.savedDevices.isEmpty() && newlyDiscovered.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (isScanning) "Scanning for straps…" else "No saved devices. Scan to add one.",
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(settings.savedDevices) { device ->
                    val isActive = device.address == settings.activeDeviceAddress
                    SavedDeviceListItem(
                        device = device,
                        isActive = isActive,
                        onSetActive = { onSetActive(device.address) },
                        onRemove = { onRemove(device.address) },
                        onConnect = { onConnect(device.address) }
                    )
                }
                if (newlyDiscovered.isNotEmpty()) {
                    item {
                        Text(
                            "Discovered",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                        )
                    }
                    items(newlyDiscovered) { device ->
                        // Connecting saves the strap (service persists it once the HR service is
                        // verified) and returns to the record screen where START awaits.
                        DeviceListItem(
                            device = device,
                            onClick = { onConnect(device.address) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SavedDeviceListItem(
    device: SavedDevice,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onRemove: () -> Unit,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = if (isActive) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = device.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Text(text = device.address, style = MaterialTheme.typography.bodySmall)
                    if (isActive) {
                        Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
                Row {
                    if (!isActive) {
                        TextButton(onClick = onSetActive) {
                            Text("Set Active")
                        }
                    }
                    Button(onClick = onConnect, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Connect")
                    }
                }
            }
            Divider(modifier = Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = Color.Gray.copy(alpha = 0.3f))
            TextButton(onClick = onRemove, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Forget Device")
            }
        }
    }
}

@Composable
fun DeviceListItem(device: ScannedStrap, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = device.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(text = device.address, style = MaterialTheme.typography.bodySmall)
        }
    }
}
