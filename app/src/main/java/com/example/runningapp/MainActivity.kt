package com.example.runningapp

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.example.runningapp.data.AppDatabase
import com.example.runningapp.ui.HistoryScreen
import com.example.runningapp.ui.SessionDetailScreen
import com.example.runningapp.ui.TrainingPlanScreen

class MainActivity : ComponentActivity() {

    private var hrService by mutableStateOf<HrForegroundService?>(null)
    private var isBound by mutableStateOf(false)

    private var currentScreenState = mutableStateOf("main")

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as HrForegroundService.LocalBinder
            val bound = binder.getService()
            hrService = bound
            isBound = true
            
            // Mission: Robust Sync - if service is running, force UI to main screen
            if (bound.isSessionActive()) {
                Log.d("MainActivity", "Restoring active session UI")
                currentScreenState.value = "main"
            }
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

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val serviceState = produceState(initialValue = HrState(), key1 = hrService) {
                        hrService?.let { service ->
                            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                service.hrState.collect { value = it }
                            }
                        }
                    }
                    val scope = rememberCoroutineScope()

                    var currentScreen by currentScreenState
                    var selectedSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
                    
                    val settingsRepository = remember { SettingsRepository(this) }
                    val userSettings by settingsRepository.userSettingsFlow.collectAsState(initial = UserSettings())

                    val database = remember { AppDatabase.getDatabase(this) }
                    val historySessions by database.sessionDao().getLast20Sessions().collectAsState(initial = emptyList())
                    
                    val sessionSamples by produceState<List<com.example.runningapp.data.HrSample>>(initialValue = emptyList(), key1 = selectedSessionId) {
                        selectedSessionId?.let { id ->
                            database.sampleDao().getSamplesForSession(id).collect { value = it }
                        }
                    }
                    val selectedSession by produceState<com.example.runningapp.data.RunnerSession?>(initialValue = null, key1 = selectedSessionId) {
                        selectedSessionId?.let { id ->
                            database.sessionDao().getSessionByIdFlow(id).collect { value = it }
                        }
                    }

                    when (currentScreen) {
                        "main" -> {
                            MainScreen(
                                hrService = hrService,
                                onRequestPermissions = { checkAndRequestPermissions() },
                                onStartService = {
                                    val action = if (hrService == null) {
                                        HrForegroundService.ACTION_START_FOREGROUND
                                    } else {
                                        HrForegroundService.ACTION_FORCE_SCAN
                                    }
                                    val intent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                        this.action = action
                                    }
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                },
                                onTogglePause = {
                                    hrService?.togglePause()
                                },
                                onStopSession = {
                                     val intent = Intent(this, HrForegroundService::class.java).apply {
                                        action = HrForegroundService.ACTION_STOP_FOREGROUND
                                    }
                                    ContextCompat.startForegroundService(this, intent)
                                },
                                onConnectToDevice = { address ->
                                    Log.d("MainActivity", "User tapped device: $address")
                                    val intent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                        action = HrForegroundService.ACTION_START_FOREGROUND
                                        putExtra(HrForegroundService.EXTRA_DEVICE_ADDRESS, address)
                                    }
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                    hrService?.connectToDevice(address)
                                },
                                onTestCue = {
                                    hrService?.playCue("Target heart rate reached. Keep it up!")
                                },
                                onOpenSettings = {
                                    currentScreen = "settings"
                                },
                                onOpenHistory = {
                                    currentScreen = "history"
                                },
                                onOpenManageDevices = {
                                    currentScreen = "manage_devices"
                                },
                                onOpenTrainingPlan = {
                                    currentScreen = "training_plan"
                                },
                                onToggleSimulation = {
                                    val intent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                        action = HrForegroundService.ACTION_START_FOREGROUND
                                    }
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                    hrService?.toggleSimulation()
                                }
                            )
                        }
                        "manage_devices" -> {
                            ManageDevicesScreen(
                                settings = userSettings,
                                connectionStatus = serviceState?.value?.connectionStatus ?: "Disconnected",
                                onSetActive = { address ->
                                    scope.launch {
                                        settingsRepository.setActiveDevice(address)
                                    }
                                },
                                onRemove = { address ->
                                    scope.launch {
                                        settingsRepository.removeDevice(address)
                                    }
                                },
                                onConnect = { address ->
                                    Log.d("MainActivity", "User tapped device in ManageDevices: $address")
                                    val intent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                        action = HrForegroundService.ACTION_START_FOREGROUND
                                        putExtra(HrForegroundService.EXTRA_DEVICE_ADDRESS, address)
                                    }
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                    hrService?.connectToDevice(address)
                                    currentScreen = "main"
                                },
                                onBack = { currentScreen = "main" }
                            )
                        }
                        "settings" -> {
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
                        "history" -> {
                            HistoryScreen(
                                sessions = historySessions,
                                onSessionClick = { id ->
                                    selectedSessionId = id
                                    currentScreen = "detail"
                                },
                                onBack = { currentScreen = "main" }
                            )
                        }
                        "detail" -> {
                            SessionDetailScreen(
                                session = selectedSession,
                                samples = sessionSamples,
                                onBack = { currentScreen = "history" }
                            )
                        }
                        "training_plan" -> {
                            TrainingPlanScreen(
                                activePlanId = userSettings.activePlanId,
                                activeStageId = userSettings.activeStageId,
                                onBack = { currentScreen = "main" }
                            )
                        }
                    }
                }
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        Intent(this, HrForegroundService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
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
        }
        
        // Mission 4: Location permissions (Foreground)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missing = permissions.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }
        
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        } else {
            // Foreground granted, now check background if needed
            checkBackgroundLocationPermission()
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Show a dialog/explanation if needed? For now just request.
                // NOTE: Android 11+ requires separate request for background.
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenManageDevices: () -> Unit,
    onOpenTrainingPlan: () -> Unit,
    onToggleSimulation: () -> Unit
) {
    val state = hrService?.hrState?.collectAsState()?.value ?: HrState()
    
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Running App", style = MaterialTheme.typography.headlineMedium)
            Row {
                IconButton(onClick = onOpenHistory) {
                    Text("📜", fontSize = 24.sp)
                }
                IconButton(onClick = onOpenManageDevices) {
                    Text("⌚", fontSize = 24.sp)
                }
                IconButton(onClick = onOpenTrainingPlan) {
                    Text("🏆", fontSize = 24.sp)
                }
                IconButton(onClick = onOpenSettings) {
                    Text("⚙️", fontSize = 24.sp)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(text = "Status: ${state.connectionStatus}")
        
        // Active Device Shortcut
        val activeDevice = state.userSettings.savedDevices.find { it.address == state.userSettings.activeDeviceAddress }
        if (activeDevice != null && state.connectionStatus == "Disconnected") {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Active Device: ${activeDevice.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(activeDevice.address, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { onConnectToDevice(activeDevice.address) }) {
                        Text("Connect")
                    }
                }
            }
        }

        if (state.sessionStatus == SessionStatus.ERROR) {
            Text(text = "ERROR: ${state.errorMessage ?: "Unknown"}", color = Color.Red, fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.sessionStatus == SessionStatus.IDLE || state.sessionStatus == SessionStatus.STOPPED || state.sessionStatus == SessionStatus.ERROR) {
                Button(onClick = onStartService) {
                    val label = if (hrService == null) "Start Service" else "Scan for Devices"
                    Text(label)
                }
            } else {
                Button(onClick = onTogglePause) {
                    Text(if (state.sessionStatus == SessionStatus.PAUSED) "Resume" else "Pause")
                }
                
                // Mission: Phase Skipping
                Button(onClick = { hrService?.skipCurrentPhase() }) {
                    val label = when(state.currentPhase) {
                        SessionPhase.WARM_UP -> "Skip Warmup"
                        SessionPhase.MAIN -> "Start Cooldown"
                        SessionPhase.COOL_DOWN -> "End Session"
                    }
                    Text(label)
                }
            }
            
            // Mission: Robust Kill Switch - show Force Stop if service is active or in error
            if (state.sessionStatus != SessionStatus.IDLE && state.sessionStatus != SessionStatus.STOPPED) {
                Button(
                    onClick = onStopSession, 
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text("Force Stop")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
             Button(onClick = onRequestPermissions) {
                Text("Perms")
            }
            Button(
                onClick = onToggleSimulation,
                colors = if (state.isSimulating) ButtonDefaults.buttonColors(containerColor = Color.Magenta) else ButtonDefaults.buttonColors()
            ) {
                Text(if (state.isSimulating) "Stop Sim" else "Simulate")
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
                val modeLabel = if (settings.runMode == "outdoor") "Outdoor Run" else "Treadmill Run"
                Text("Mode: $modeLabel | Cooldown: ${settings.cooldownSeconds}s", style = MaterialTheme.typography.bodySmall)
                if (settings.runWalkCoachEnabled) {
                    Text("RUN/WALK COACH ACTIVE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = Color(0xFFFFA500))
                }
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
        
        if (state.runMode == "outdoor") {
             Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                 Column(horizontalAlignment = Alignment.CenterHorizontally) {
                     Text("${"%.2f".format(state.distanceKm)} km", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                     Text("Distance", style = MaterialTheme.typography.labelSmall)
                 }
                 Column(horizontalAlignment = Alignment.CenterHorizontally) {
                     val pace = state.paceMinPerKm
                     val paceStr = if (pace > 0) {
                         val mins = pace.toInt()
                         val secs = ((pace - mins) * 60).roundToInt()
                         "%d:%02d".format(mins, secs)
                     } else "--:--"
                     Text(paceStr, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                     Text("Pace (min/km)", style = MaterialTheme.typography.labelSmall)
                 }
             }
             Spacer(modifier = Modifier.height(8.dp))
        }
        
        Text(text = "Data Age: ${state.lastHrAgeSeconds}s")
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Session Engine:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        
        // Phase Indicator
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text = "${state.currentPhase}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = when(state.currentPhase) {
                    SessionPhase.WARM_UP -> Color(0xFFFFA500)
                    SessionPhase.MAIN -> Color.Green
                    SessionPhase.COOL_DOWN -> Color.Cyan
                }
            )
            if (state.currentPhase != SessionPhase.MAIN) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${formatTime(state.phaseSecondsRemaining.toLong())} remaining",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Red
                )
            }
        }
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
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Zone Timer Breakdown
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.03f))
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("Zone Breakdown (Active Only):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    (1..5).forEach { zone ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Z$zone", style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = formatTime(state.zoneTimes[zone] ?: 0L),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if ((state.zoneTimes[zone] ?: 0L) > 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
        
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
    var runMode by remember { mutableStateOf(settings.runMode) }
    var splitAudio by remember { mutableStateOf(settings.splitAnnouncementsEnabled) }
    var runWalkCoach by remember { mutableStateOf(settings.runWalkCoachEnabled) }
    
    // Warm-up Selection
    var warmUpSelection by remember { mutableStateOf(if (settings.warmUpDurationSeconds == 480) "recommended" else "custom") }
    
    // Warm-up Min/Sec
    var warmUpMin by remember { mutableStateOf((settings.warmUpDurationSeconds / 60).toString()) }
    var warmUpSec by remember { mutableStateOf((settings.warmUpDurationSeconds % 60).toString()) }
    
    // Cool-down Min/Sec
    var coolDownMin by remember { mutableStateOf((settings.coolDownDurationSeconds / 60).toString()) }
    var coolDownSec by remember { mutableStateOf((settings.coolDownDurationSeconds % 60).toString()) }

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

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).background(if (runWalkCoach) Color(0xFFFFA500).copy(alpha = 0.1f) else Color.Transparent).padding(4.dp)
        ) {
            Switch(checked = runWalkCoach, onCheckedChange = { runWalkCoach = it })
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Run/Walk Coach Mode", fontWeight = FontWeight.Bold)
                Text("Special cues for beginner Z2 training", style = MaterialTheme.typography.labelSmall)
            }
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

        Spacer(modifier = Modifier.height(24.dp))
        Text("Mission 4: Run Configuration", style = MaterialTheme.typography.titleMedium)
        
        Text("Running Mode:")
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = runMode == "treadmill", onClick = { runMode = "treadmill" })
            Text("Treadmill")
            Spacer(modifier = Modifier.width(16.dp))
            RadioButton(selected = runMode == "outdoor", onClick = { runMode = "outdoor" })
            Text("Outdoor (GPS)")
        }
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = splitAudio, onCheckedChange = { splitAudio = it })
            Text("1km Split Audio Announcements")
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Session Phases", style = MaterialTheme.typography.titleMedium)
        
        Text("Warm-up Duration", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = warmUpSelection == "recommended", onClick = { warmUpSelection = "recommended" })
            Text("Recommended (8 mins)")
            Spacer(modifier = Modifier.width(16.dp))
            RadioButton(selected = warmUpSelection == "custom", onClick = { warmUpSelection = "custom" })
            Text("Custom")
        }
        
        if (warmUpSelection == "custom") {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = warmUpMin, 
                    onValueChange = { warmUpMin = it }, 
                    label = { Text("Minutes") }, 
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
                Text(":")
                OutlinedTextField(
                    value = warmUpSec, 
                    onValueChange = { warmUpSec = it }, 
                    label = { Text("Seconds") }, 
                    modifier = Modifier.weight(1f),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text("Cool-down Duration", style = MaterialTheme.typography.labelMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = coolDownMin, onValueChange = { coolDownMin = it }, label = { Text("Min") }, modifier = Modifier.weight(1f))
            Text(":")
            OutlinedTextField(value = coolDownSec, onValueChange = { coolDownSec = it }, label = { Text("Sec") }, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(32.dp))
        
        Button(onClick = {
            onSave(settings.copy(
                maxHr = maxHr.toIntOrNull() ?: settings.maxHr,
                zone2Low = zone2Low.toIntOrNull() ?: settings.zone2Low,
                zone2High = zone2High.toIntOrNull() ?: settings.zone2High,
                cooldownSeconds = cooldown.toIntOrNull() ?: settings.cooldownSeconds,
                persistenceHighSeconds = persistenceHigh.toIntOrNull() ?: settings.persistenceHighSeconds,
                persistenceLowSeconds = persistenceLow.toIntOrNull() ?: settings.persistenceLowSeconds,
                voiceStyle = voiceStyle,
                coachingEnabled = coachingEnabled,
                runMode = runMode,
                splitAnnouncementsEnabled = splitAudio,
                runWalkCoachEnabled = runWalkCoach,
                warmUpDurationSeconds = if (warmUpSelection == "recommended") 480 else {
                    (warmUpMin.toIntOrNull() ?: 0) * 60 + (warmUpSec.toIntOrNull() ?: 0)
                },
                coolDownDurationSeconds = (coolDownMin.toIntOrNull() ?: 0) * 60 + (coolDownSec.toIntOrNull() ?: 0)
            ))
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Save Settings")
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun ManageDevicesScreen(
    settings: UserSettings,
    connectionStatus: String,
    onSetActive: (String) -> Unit,
    onRemove: (String) -> Unit,
    onConnect: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Manage Devices", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                Text("Back")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Status: $connectionStatus", style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))

        if (settings.savedDevices.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No saved devices. Scan to add one.")
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
