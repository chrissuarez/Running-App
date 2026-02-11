package com.example.runningapp

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var hrService: HrForegroundService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as HrForegroundService.LocalBinder
            hrService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            hrService = null
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // Handle permission results if needed
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bind to the service
        Intent(this, HrForegroundService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val serviceState = hrService?.hrState?.collectAsState(initial = HrState())
                    val scope = rememberCoroutineScope()
                    
                    var boundService by remember { mutableStateOf<HrForegroundService?>(null) }
                    
                    LaunchedEffect(Unit) {
                        while(true) {
                            if (isBound && hrService != null) {
                                boundService = hrService
                            }
                            delay(500)
                        }
                    }

                    var currentScreen by remember { mutableStateOf("main") }
                    val settingsRepository = remember { SettingsRepository(this) }
                    val userSettings by settingsRepository.userSettingsFlow.collectAsState(initial = UserSettings())

                    if (currentScreen == "main") {
                        MainScreen(
                            hrService = boundService,
                            onRequestPermissions = { checkAndRequestPermissions() },
                            onStartService = {
                                val intent = Intent(this, HrForegroundService::class.java).apply {
                                    action = HrForegroundService.ACTION_START_FOREGROUND
                                }
                                startService(intent)
                            },
                            onTogglePause = {
                                boundService?.togglePause()
                            },
                            onStopSession = {
                                 val intent = Intent(this, HrForegroundService::class.java).apply {
                                    action = HrForegroundService.ACTION_STOP_FOREGROUND
                                }
                                startService(intent)
                            },
                            onConnectToDevice = { address ->
                                boundService?.connectToDevice(address)
                            },
                            onTestCue = {
                                boundService?.playCue("Target heart rate reached. Keep it up!")
                            },
                            onOpenSettings = {
                                currentScreen = "settings"
                            }
                        )
                    } else {
                        SettingsScreen(
                            settings = userSettings,
                            onSave = { updatedSettings ->
                                scope.launch {
                                    settingsRepository.updateSettings(updatedSettings)
                                    currentScreen = "main"
                                }
                            },
                            onBack = { currentScreen = "main" }
                        )
                    }
                }
            }
        }
    }
    
    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
             permissions.add(Manifest.permission.BLUETOOTH)
             permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
             permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missing = permissions.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }
        
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}

@Composable
fun MainScreen(
    hrService: HrForegroundService?, 
    onRequestPermissions: () -> Unit,
    onStartService: () -> Unit,
    onTogglePause: () -> Unit,
    onStopSession: () -> Unit,
    onConnectToDevice: (String) -> Unit,
    onTestCue: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state = hrService?.hrState?.collectAsState()?.value ?: HrState()
    
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Running App", style = MaterialTheme.typography.headlineMedium)
            IconButton(onClick = onOpenSettings) {
                Text("⚙️", fontSize = 24.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(text = "Status: ${state.connectionStatus}")
        if (state.sessionStatus == SessionStatus.ERROR) {
            Text(text = "ERROR: ${state.errorMessage ?: "Unknown"}", color = Color.Red, fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.sessionStatus == SessionStatus.IDLE || state.sessionStatus == SessionStatus.STOPPED || state.sessionStatus == SessionStatus.ERROR) {
                Button(onClick = onStartService) {
                    Text("Scan / Start")
                }
            } else {
                Button(onClick = onTogglePause) {
                    Text(if (state.sessionStatus == SessionStatus.PAUSED) "Resume" else "Pause")
                }
                Button(onClick = onStopSession, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Stop")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
             Button(onClick = onTestCue) {
                Text("Test Audio")
            }
            Button(onClick = onRequestPermissions) {
                Text("Perms")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Settings Summary
        SettingsSummaryCard(settings = state.userSettings)

        Spacer(modifier = Modifier.height(16.dp))

        if (state.connectionStatus == "Scanning...") {
            Text("Scanned Devices:", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color.LightGray.copy(alpha = 0.2f))
            ) {
                items(state.scannedDevices) { device ->
                    DeviceListItem(device = device, onClick = { onConnectToDevice(device.address) })
                }
            }
        } else if (state.sessionStatus != SessionStatus.IDLE && state.sessionStatus != SessionStatus.STOPPED) {
             WorkoutView(state = state)
        } else {
            Spacer(modifier = Modifier.weight(1f))
            Text("Ready to start a session.")
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun SettingsSummaryCard(settings: UserSettings) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Zone 2: ${settings.zone2Low}-${settings.zone2High} BPM", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Text("Cooldown: ${settings.cooldownSeconds}s | Persistence: ${settings.persistenceHighSeconds}s/${settings.persistenceLowSeconds}s", style = MaterialTheme.typography.bodySmall)
            }
            Text(if (settings.coachingEnabled) "Coaching ON" else "Coaching OFF", style = MaterialTheme.typography.bodySmall, color = if (settings.coachingEnabled) Color.Green else Color.Red)
        }
    }
}

@Composable
fun WorkoutView(state: HrState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.verticalScroll(rememberScrollState())) {
         if (state.connectedDeviceName != null) {
            Text(text = "Device: ${state.connectedDeviceName}", style = MaterialTheme.typography.titleMedium)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(text = "${state.bpm} BPM", style = MaterialTheme.typography.displayLarge)
        Text(text = "Data Age: ${state.lastHrAgeSeconds}s")
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Session Engine:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text("State: ${state.sessionStatus}", style = MaterialTheme.typography.bodyMedium, 
             color = when(state.sessionStatus) {
                 SessionStatus.RUNNING -> Color.Green
                 SessionStatus.PAUSED -> Color.Yellow
                 SessionStatus.CONNECTING -> Color.Cyan
                 SessionStatus.ERROR -> Color.Red
                 else -> Color.Gray
             }
        )
        Text("Active Time: ${formatTime(state.secondsRunning)}", style = MaterialTheme.typography.bodyMedium)
        Text("Paused Time: ${formatTime(state.secondsPaused)}", style = MaterialTheme.typography.bodyMedium)
        if (state.reconnectAttempts > 0) {
            Text("Reconnect Attempts: ${state.reconnectAttempts}", style = MaterialTheme.typography.bodyMedium, color = Color.Red)
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Coaching Debug:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text("Avg BPM (5s): ${state.avgBpm}", style = MaterialTheme.typography.bodyMedium)
        Text("Zone: ${state.currentZone}", style = MaterialTheme.typography.bodyMedium, 
            color = if(state.currentZone == "TARGET") Color.Green else if (state.currentZone == "LOW" || state.currentZone == "HIGH") Color.Red else Color.Gray
        )
        Text("Time in Zone: ${state.timeInZoneString}", style = MaterialTheme.typography.bodyMedium)
        Text("Status: ${state.cooldownWithHysteresisString}", style = MaterialTheme.typography.bodyMedium)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Raw BLE Info:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text("Last Packet: ${state.lastPacketTimeFormatted}", style = MaterialTheme.typography.bodySmall)
        Text("Data Format: ${state.dataBits}", style = MaterialTheme.typography.bodySmall)
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Column(modifier = Modifier.fillMaxWidth().height(100.dp).verticalScroll(rememberScrollState()).background(Color.Black.copy(alpha = 0.05f)).padding(8.dp)) {
              Text("Discovered Services:", fontSize = 10.sp, fontWeight = FontWeight.Bold)
              state.discoveredServices.forEach { uuid ->
                 Text(text = uuid, fontSize = 10.sp)
             }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: UserSettings,
    onSave: (UserSettings) -> Unit,
    onBack: () -> Unit
) {
    var maxHr by remember { mutableStateOf(settings.maxHr.toString()) }
    var zone2Low by remember { mutableStateOf(settings.zone2Low.toString()) }
    var zone2High by remember { mutableStateOf(settings.zone2High.toString()) }
    var cooldown by remember { mutableStateOf(settings.cooldownSeconds.toString()) }
    var persistenceHigh by remember { mutableStateOf(settings.persistenceHighSeconds.toString()) }
    var persistenceLow by remember { mutableStateOf(settings.persistenceLowSeconds.toString()) }
    var voiceStyle by remember { mutableStateOf(settings.voiceStyle) }
    var coachingEnabled by remember { mutableStateOf(settings.coachingEnabled) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                Text("Back")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Basic Info
        OutlinedTextField(value = maxHr, onValueChange = { maxHr = it }, label = { Text("Max HR") }, modifier = Modifier.fillMaxWidth())
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = zone2Low, onValueChange = { zone2Low = it }, label = { Text("Zone 2 Low") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = zone2High, onValueChange = { zone2High = it }, label = { Text("Zone 2 High") }, modifier = Modifier.weight(1f))
        }
        
        Button(onClick = {
            val max = maxHr.toIntOrNull() ?: 190
            zone2Low = (max * 0.6).roundToInt().toString()
            zone2High = (max * 0.75).roundToInt().toString()
        }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Derive Defaults from Max HR")
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Coaching Preferences", style = MaterialTheme.typography.titleMedium)
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = coachingEnabled, onCheckedChange = { coachingEnabled = it })
            Text("Enable Coaching Cues")
        }

        OutlinedTextField(value = cooldown, onValueChange = { cooldown = it }, label = { Text("Cue Cooldown (s)") }, modifier = Modifier.fillMaxWidth())
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = persistenceHigh, onValueChange = { persistenceHigh = it }, label = { Text("High Persistence (s)") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = persistenceLow, onValueChange = { persistenceLow = it }, label = { Text("Low Persistence (s)") }, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Voice Style:")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = voiceStyle == "short", onClick = { voiceStyle = "short" })
            Text("Short")
            Spacer(modifier = Modifier.width(16.dp))
            RadioButton(selected = voiceStyle == "detailed", onClick = { voiceStyle = "detailed" })
            Text("Detailed")
        }

        Spacer(modifier = Modifier.height(32.dp))
        
        Button(onClick = {
            onSave(UserSettings(
                maxHr = maxHr.toIntOrNull() ?: settings.maxHr,
                zone2Low = zone2Low.toIntOrNull() ?: settings.zone2Low,
                zone2High = zone2High.toIntOrNull() ?: settings.zone2High,
                cooldownSeconds = cooldown.toIntOrNull() ?: settings.cooldownSeconds,
                persistenceHighSeconds = persistenceHigh.toIntOrNull() ?: settings.persistenceHighSeconds,
                persistenceLowSeconds = persistenceLow.toIntOrNull() ?: settings.persistenceLowSeconds,
                voiceStyle = voiceStyle,
                coachingEnabled = coachingEnabled
            ))
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Save Settings")
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

private fun formatTime(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(mins, secs)
}

@Composable
fun DeviceListItem(device: BluetoothDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = device.name ?: "Unknown Device", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(text = device.address, style = MaterialTheme.typography.bodySmall)
        }
    }
}
