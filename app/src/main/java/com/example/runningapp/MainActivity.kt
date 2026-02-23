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
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.example.runningapp.data.AppDatabase
import com.example.runningapp.data.AiCoachClient
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.ui.HistoryScreen
import com.example.runningapp.ui.HistoryViewModel
import com.example.runningapp.ui.HistoryViewModelFactory
import com.example.runningapp.ui.SessionDetailScreen
import com.example.runningapp.ui.SessionDetailViewModel
import com.example.runningapp.ui.SessionDetailViewModelFactory
import com.example.runningapp.ui.TrainingPlanScreen

private const val SESSION_TYPE_RUN_WALK = "Run/Walk"
private const val SESSION_TYPE_ZONE2_WALK = "Zone 2 Walk"
private const val SESSION_TYPE_FREE_TRACK = "Free Track"

class MainActivity : ComponentActivity() {

    private var hrService by mutableStateOf<HrForegroundService?>(null)
    private var isBound by mutableStateOf(false)
    private val aiCoachClient by lazy {
        AiCoachClient()
    }

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
                    val sessionRepository = remember { SessionRepository(database.sessionDao()) }
                    val historyViewModel: HistoryViewModel = viewModel(
                        factory = HistoryViewModelFactory(sessionRepository)
                    )
                    val sessionDetailViewModel: SessionDetailViewModel = viewModel(
                        factory = SessionDetailViewModelFactory(sessionRepository)
                    )
                    val selectedSessionIds by historyViewModel.selectedSessionIds.collectAsState()
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

                    LaunchedEffect(sessionDetailViewModel) {
                        sessionDetailViewModel.deleteCompleted.collect {
                            selectedSessionId = null
                            currentScreen = "history"
                        }
                    }

                    when (currentScreen) {
                        "main" -> {
                            MainScreen(
                                hrService = hrService,
                                userSettings = userSettings,
                                onRequestPermissions = { checkAndRequestPermissions() },
                                onStartService = { selectedSessionType ->
                                    val action = if (hrService == null) {
                                        HrForegroundService.ACTION_START_FOREGROUND
                                    } else {
                                        HrForegroundService.ACTION_FORCE_SCAN
                                    }
                                    val intent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                        this.action = action
                                        putExtra("SESSION_TYPE", selectedSessionType)
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
                                onConnectToDevice = { address, selectedSessionType ->
                                    Log.d("MainActivity", "User tapped device: $address")
                                    val intent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                        action = HrForegroundService.ACTION_START_FOREGROUND
                                        putExtra(HrForegroundService.EXTRA_DEVICE_ADDRESS, address)
                                        putExtra("SESSION_TYPE", selectedSessionType)
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
                                onToggleSimulation = { simulationEnabled ->
                                    scope.launch(Dispatchers.IO) {
                                        settingsRepository.setSimulationEnabled(simulationEnabled)

                                        val isSessionRunning =
                                            hrService?.hrState?.value?.sessionStatus == SessionStatus.RUNNING

                                        val startIntent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                            action = HrForegroundService.ACTION_START_FOREGROUND
                                        }
                                        ContextCompat.startForegroundService(this@MainActivity, startIntent)
                                        hrService?.toggleSimulation()

                                        if (!simulationEnabled && isSessionRunning) {
                                            val stopIntent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                                action = HrForegroundService.ACTION_STOP_FOREGROUND
                                            }
                                            ContextCompat.startForegroundService(this@MainActivity, stopIntent)
                                        }
                                    }
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
                                selectedSessionIds = selectedSessionIds,
                                onToggleSelection = { id -> historyViewModel.toggleSelection(id) },
                                onClearSelection = { historyViewModel.clearSelection() },
                                onDeleteSelected = { historyViewModel.deleteSelectedSessions() },
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
                                onDeleteSession = { id ->
                                    sessionDetailViewModel.deleteSession(id)
                                },
                                onBack = { currentScreen = "history" }
                            )
                        }
                        "training_plan" -> {
                            TrainingPlanScreen(
                                activePlanId = userSettings.activePlanId,
                                activeStageId = userSettings.activeStageId,
                                onActivatePlan = { planId, stageId ->
                                    scope.launch {
                                        settingsRepository.setActivePlan(planId, stageId)
                                    }
                                },
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
    userSettings: UserSettings,
    paddingValues: PaddingValues = PaddingValues(0.dp),
    onRequestPermissions: () -> Unit,
    onStartService: (String) -> Unit,
    onTogglePause: () -> Unit,
    onStopSession: () -> Unit,
    onConnectToDevice: (String, String) -> Unit,
    onTestCue: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenManageDevices: () -> Unit,
    onOpenTrainingPlan: () -> Unit,
    onToggleSimulation: (Boolean) -> Unit
) {
    val sessionTypeOptions = listOf(
        SESSION_TYPE_RUN_WALK,
        SESSION_TYPE_ZONE2_WALK,
        SESSION_TYPE_FREE_TRACK
    )
    var selectedSessionType by rememberSaveable { mutableStateOf(SESSION_TYPE_RUN_WALK) }

    val state = hrService?.hrState?.collectAsState()?.value ?: HrState()
    val activePlan = userSettings.activePlanId?.let { TrainingPlanProvider.getPlanById(it) }
    val activeStage = activePlan?.stages?.firstOrNull { it.id == userSettings.activeStageId } ?: activePlan?.stages?.firstOrNull()
    val baseWorkout = activeStage?.workouts?.firstOrNull()
    val aiRunIntervalSeconds = userSettings.aiRunIntervalSeconds
    val aiWalkIntervalSeconds = userSettings.aiWalkIntervalSeconds
    val aiRepeats = userSettings.aiRepeats
    val coachMessage = userSettings.latestCoachMessage?.takeIf { it.isNotBlank() }
    val todaysWorkout = if (baseWorkout != null && aiRunIntervalSeconds != null) {
        baseWorkout.copy(
            runDurationSeconds = aiRunIntervalSeconds,
            walkDurationSeconds = aiWalkIntervalSeconds ?: baseWorkout.walkDurationSeconds,
            totalRepeats = aiRepeats ?: baseWorkout.totalRepeats
        )
    } else {
        baseWorkout
    }
    
    val activeDevice = state.userSettings.savedDevices.find { it.address == state.userSettings.activeDeviceAddress }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Running App", style = MaterialTheme.typography.headlineMedium)
                Row {
                    IconButton(onClick = onOpenHistory) { Text("📜", fontSize = 24.sp) }
                    IconButton(onClick = onOpenManageDevices) { Text("⌚", fontSize = 24.sp) }
                    IconButton(onClick = onOpenTrainingPlan) { Text("🏆", fontSize = 24.sp) }
                    IconButton(onClick = onOpenSettings) { Text("⚙️", fontSize = 24.sp) }
                }
            }
        }

        item {
            Text(text = "Status: ${state.connectionStatus}")
        }

        if (activeDevice != null && state.connectionStatus == "Disconnected") {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Active Device: ${activeDevice.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(activeDevice.address, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { onConnectToDevice(activeDevice.address, selectedSessionType) }) {
                            Text("Connect")
                        }
                    }
                }
            }
        }

        if (state.sessionStatus == SessionStatus.ERROR) {
            item {
                Text(
                    text = "ERROR: ${state.errorMessage ?: "Unknown"}",
                    color = Color.Red,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (selectedSessionType == SESSION_TYPE_RUN_WALK) {
            if (activePlan != null && activeStage != null && todaysWorkout != null) {
                item {
                    TodaysWorkoutCard(
                        stageTitle = activeStage.title,
                        workout = todaysWorkout
                    )
                }
            } else if (userSettings.activePlanId == null) {
                item {
                    TextButton(onClick = onOpenTrainingPlan) {
                        Text("No active plan - tap to view plans")
                    }
                }
            }

            if (coachMessage != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE7E8FF))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "AI Coach Debrief",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = coachMessage,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        } else if (selectedSessionType == SESSION_TYPE_ZONE2_WALK) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "Zone 2 Walk Mode: Aerobic volume. HR safety cues only.",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else if (selectedSessionType == SESSION_TYPE_FREE_TRACK) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "Free Track Mode: Pure data logging. No audio cues.",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        item {
            Text(
                text = "Session Type",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                sessionTypeOptions.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        RadioButton(
                            selected = selectedSessionType == option,
                            onClick = { selectedSessionType = option }
                        )
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.sessionStatus == SessionStatus.IDLE || state.sessionStatus == SessionStatus.STOPPED || state.sessionStatus == SessionStatus.ERROR) {
                    Button(onClick = { onStartService(selectedSessionType) }) {
                        val label = if (hrService == null) "Start Service" else "Scan for Devices"
                        Text(label)
                    }
                } else {
                    Button(onClick = onTogglePause) {
                        Text(if (state.sessionStatus == SessionStatus.PAUSED) "Resume" else "Pause")
                    }
                    Button(onClick = { hrService?.skipCurrentPhase() }) {
                        val label = when (state.currentPhase) {
                            SessionPhase.WARM_UP -> "Skip Warmup"
                            SessionPhase.MAIN -> "Start Cooldown"
                            SessionPhase.COOL_DOWN -> "End Session"
                        }
                        Text(label)
                    }
                }

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
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onRequestPermissions) {
                    Text("Perms")
                }
                Button(
                    onClick = { onToggleSimulation(!state.isSimulating) },
                    colors = if (state.isSimulating) ButtonDefaults.buttonColors(containerColor = Color.Magenta) else ButtonDefaults.buttonColors()
                ) {
                    Text(if (state.isSimulating) "Stop Sim" else "Simulate")
                }
            }
        }

        item {
            SettingsSummaryCard(
                settings = state.userSettings,
                selectedSessionType = selectedSessionType
            )
        }

        if (state.connectionStatus == "Scanning...") {
            item {
                Text("Scanned Devices:", style = MaterialTheme.typography.titleMedium)
            }
            items(state.scannedDevices) { device ->
                DeviceListItem(
                    device = device,
                    onClick = { onConnectToDevice(device.address, selectedSessionType) }
                )
            }
        } else if (state.sessionStatus != SessionStatus.IDLE && state.sessionStatus != SessionStatus.STOPPED) {
            item {
                WorkoutView(state = state)
            }
        } else {
            item {
                Text("Ready to start a session.")
            }
        }
    }
}

@Composable
fun TodaysWorkoutCard(
    stageTitle: String,
    workout: WorkoutTemplate
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Today's Workout",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stageTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = workout.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Target HR Zone: Z${workout.targetZone}", style = MaterialTheme.typography.bodyMedium)
            Text("Run: ${formatSecondsToMinutes(workout.runDurationSeconds)}", style = MaterialTheme.typography.bodyMedium)
            Text("Walk: ${formatSecondsToMinutes(workout.walkDurationSeconds)}", style = MaterialTheme.typography.bodyMedium)
            Text("Repeats: ${workout.totalRepeats}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SettingsSummaryCard(
    settings: UserSettings,
    selectedSessionType: String
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Zone 2: ${settings.zone2Low}-${settings.zone2High} BPM", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                val modeLabel = if (settings.runMode == "outdoor") "Outdoor Run" else "Treadmill Run"
                Text("Mode: $modeLabel | Cooldown: ${settings.cooldownSeconds}s", style = MaterialTheme.typography.bodySmall)
                val sessionTypeSummary = when (selectedSessionType) {
                    SESSION_TYPE_RUN_WALK -> "RUN/WALK COACH ACTIVE" to Color(0xFFFFA500)
                    SESSION_TYPE_ZONE2_WALK -> "ZONE 2 WALK (Volume Only)" to MaterialTheme.colorScheme.primary
                    SESSION_TYPE_FREE_TRACK -> "FREE TRACK (Silent Logging)" to MaterialTheme.colorScheme.outline
                    else -> "RUN/WALK COACH ACTIVE" to Color(0xFFFFA500)
                }
                Text(
                    text = sessionTypeSummary.first,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = sessionTypeSummary.second
                )
            }
            Text(if (settings.coachingEnabled) "Coaching ON" else "Coaching OFF", style = MaterialTheme.typography.bodySmall, color = if (settings.coachingEnabled) Color.Green else Color.Red)
        }
    }
}

@Composable
fun WorkoutView(state: HrState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
        
        Column(modifier = Modifier.fillMaxWidth().height(100.dp).background(Color.Black.copy(alpha = 0.05f)).padding(8.dp)) {
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

private fun formatSecondsToMinutes(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "${m}m ${s}s"
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
