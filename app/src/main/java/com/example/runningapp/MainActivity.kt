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
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.export.gpxShareChooser
import com.example.runningapp.foreground.isAcquiringStrap
import com.example.runningapp.navigation.Routes
import com.example.runningapp.ui.FeelFeedbackSheet
import com.example.runningapp.ui.HistoryScreen
import com.example.runningapp.ui.HistoryViewModel
import com.example.runningapp.ui.HistoryViewModelFactory
import com.example.runningapp.ui.SessionDetailScreen
import com.example.runningapp.ui.SessionDetailViewModel
import com.example.runningapp.ui.SessionDetailViewModelFactory
import com.example.runningapp.ui.SettingsScreen
import com.example.runningapp.ui.TrainingPlanScreen
import com.example.runningapp.ui.strapRowSummary
import com.example.runningapp.ui.theme.RunningAppTheme
import com.example.runningapp.ui.theme.RunningUiTokens
import com.example.runningapp.ui.workout.CUE_REASON_HR_HIGH
import com.example.runningapp.ui.workout.CUE_REASON_SENSOR_LOST
import com.example.runningapp.ui.workout.CueSeverity
import com.example.runningapp.ui.workout.FullScreenMapScreen
import com.example.runningapp.ui.workout.MapCard
import com.example.runningapp.ui.workout.TimelineMarkerType
import com.example.runningapp.ui.workout.TimelineSegmentType
import com.example.runningapp.ui.workout.TodayCardLinkKind
import com.example.runningapp.ui.workout.TodayCardUiState
import com.example.runningapp.ui.workout.todayCardUiState
import com.example.runningapp.ui.workout.mapWorkoutPlayerUiState
import com.example.runningapp.ui.workout.zoneBandColor

class MainActivity : ComponentActivity() {

    private companion object {
        /** Guards the #163 moving-time backfill so it runs once per process, not once per activity. */
        val movingTimeBackfilled = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private var hrService by mutableStateOf<HrForegroundService?>(null)
    private var isBound by mutableStateOf(false)
    private var forceMainToken = mutableStateOf(0)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as HrForegroundService.LocalBinder
            val bound = binder.getService()
            hrService = bound
            isBound = true

            // Mission: Robust Sync - if service is running, force UI to main screen
            if (bound.isSessionActive()) {
                Log.d("MainActivity", "Restoring active session UI")
                forceMainToken.value++
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            hrService = null
        }
    }

    // A START tap that had to ask for location first parks here until the
    // dialog resolves, then the run starts from the launcher callback.
    private var pendingStartRun: Pair<Boolean, String>? = null

    // A Manage Devices scan tap that had to ask for BLUETOOTH_SCAN first.
    // Unlike START (which proceeds even on denial — GPS is a sensor, #110),
    // a scan without the permission is a pure dead-end, so it only fires on grant.
    private var pendingScan = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // Resume a START that was waiting on the location dialog. The gate
            // was only "having asked" (#110) — the run starts whether or not
            // the dialog was granted; denied just means no GPS this run.
            pendingStartRun?.let { (skipPlan, runMode) ->
                pendingStartRun = null
                sendStartRun(skipPlan, runMode)
            }
            if (pendingScan) {
                pendingScan = false
                val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(
                        this, Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED
                if (granted) sendForceScan()
            }
        }

    private fun sendForceScan() {
        val intent = Intent(this, HrForegroundService::class.java).apply {
            action = HrForegroundService.ACTION_FORCE_SCAN
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendStartRun(skipPlan: Boolean, runMode: String) {
        // START begins the run regardless of the strap (#110): the service
        // opens the record and starts the clock, then acquires the strap as a
        // sensor alongside. The mode travels with the intent so a just-tapped
        // Treadmill/Outdoor choice is honoured even before its settings write
        // lands.
        val intent = Intent(this, HrForegroundService::class.java).apply {
            action = HrForegroundService.ACTION_START_RUN
            putExtra(HrForegroundService.EXTRA_SKIP_PLAN, skipPlan)
            putExtra(HrForegroundService.EXTRA_RUN_MODE, runMode)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Runs already in history predate moving time, so their pace would be measured against a
        // different clock from today's runs until this fills them in (#163). Off the main thread,
        // and once per process: a run with no usable track stays unmeasurable, so re-reading the
        // set on every rotation would be work that can only ever come back empty-handed.
        if (movingTimeBackfilled.compareAndSet(false, true)) {
            val repository = runningAppContainer().sessionRepository
            lifecycleScope.launch(Dispatchers.IO) { repository.backfillMovingTime() }
        }

        setContent {
            RunningAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                  Box(modifier = Modifier.fillMaxSize()) {
                    val serviceState = produceState(initialValue = HrState(), key1 = hrService) {
                        hrService?.let { service ->
                            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                service.hrState.collect { value = it }
                            }
                        }
                    }
                    val scope = rememberCoroutineScope()
                    var feelSheetSessionId by rememberSaveable { mutableStateOf<Long?>(null) }

                    val navController = rememberNavController()
                    val navigateTo: (String) -> Unit = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    }

                    val appContainer = remember { this@MainActivity.runningAppContainer() }
                    val settingsRepository = remember { appContainer.settingsRepository }
                    val userSettings by settingsRepository.userSettingsFlow.collectAsState(initial = UserSettings())
                    val coachPrescription by appContainer.coachPrescriptionRepository
                        .prescriptionFlow.collectAsState(initial = null)

                    val database = remember { appContainer.database }
                    val sessionRepository = remember { appContainer.sessionRepository }
                    val historyViewModel: HistoryViewModel = viewModel(
                        factory = HistoryViewModelFactory(sessionRepository)
                    )
                    val sessionDetailViewModel: SessionDetailViewModel = viewModel(
                        factory = SessionDetailViewModelFactory(sessionRepository, appContainer.gpxFileStore)
                    )
                    val gpxShareReady by sessionDetailViewModel.gpxShareReady.collectAsState()
                    val gpxShareFailed by sessionDetailViewModel.gpxShareFailed.collectAsState()
                    val selectedSessionIds by historyViewModel.selectedSessionIds.collectAsState()
                    val historySessions by database.sessionDao().getLast20Sessions().collectAsState(initial = emptyList())

                    val forceMainSignal by forceMainToken
                    LaunchedEffect(forceMainSignal) {
                        if (forceMainSignal > 0) {
                            navigateTo(Routes.MAIN)
                        }
                    }

                    LaunchedEffect(sessionDetailViewModel) {
                        sessionDetailViewModel.deleteCompleted.collect {
                            navigateTo(Routes.HISTORY)
                        }
                    }

                    LaunchedEffect(sessionRepository) {
                        sessionRepository.retryMissingWeather()
                    }

                    NavHost(navController = navController, startDestination = Routes.MAIN) {
                        composable(Routes.MAIN) {
                            MainScreen(
                                hrService = hrService,
                                userSettings = userSettings,
                                coachPrescription = coachPrescription,
                                sessionRepository = sessionRepository,
                                onRequestPermissions = { checkAndRequestPermissions() },
                                onStartRun = { skipPlan, runMode ->
                                    // An Outdoor run without location permission would silently
                                    // record 0 km (LocationTracker just logs and returns): ask
                                    // first instead of starting blind. The tap is parked in
                                    // pendingStartRun and the run starts from the permission
                                    // callback once the dialog resolves — START itself never
                                    // gates on GPS (#110), only on having asked.
                                    val needsLocation = runMode == "outdoor" &&
                                        ContextCompat.checkSelfPermission(
                                            this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                                        ) != PackageManager.PERMISSION_GRANTED
                                    if (needsLocation) {
                                        pendingStartRun = skipPlan to runMode
                                        checkAndRequestPermissions()
                                    } else {
                                        sendStartRun(skipPlan, runMode)
                                    }
                                },
                                onRetryStrap = {
                                    // Re-acquire, don't scan: a bare scan never auto-connects
                                    // (results only fill the Discovered list in Manage Devices),
                                    // so FORCE_SCAN here couldn't bring the strap back. The
                                    // no-extra START_FOREGROUND path connects the saved strap
                                    // directly, falling back to a scan only when none is saved.
                                    val intent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                        this.action = HrForegroundService.ACTION_START_FOREGROUND
                                    }
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                },
                                onTogglePause = {
                                    hrService?.togglePause()
                                },
                                onStopSession = {
                                    hrService?.hrState?.value?.let {
                                        if (it.activeDbSessionId != null &&
                                            (it.sessionStatus == SessionStatus.RUNNING || it.sessionStatus == SessionStatus.PAUSED)
                                        ) {
                                            feelSheetSessionId = it.activeDbSessionId
                                        }
                                    }
                                     val intent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                        action = HrForegroundService.ACTION_STOP_FOREGROUND
                                    }
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                },
                                onConnectToDevice = { address, skipPlan ->
                                    Log.d("MainActivity", "User tapped device: $address")
                                    // The service's ACTION_START_FOREGROUND handler reads EXTRA_SKIP_PLAN
                                    // and then connects via the override address, so the skip choice is
                                    // always applied before session setup. Do NOT also call
                                    // hrService?.connectToDevice() here: when the service is already bound
                                    // that direct connect can reach startNewDatabaseSession() before the
                                    // intent sets skipPlanForToday, attaching the plan the user skipped.
                                    val intent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                        action = HrForegroundService.ACTION_START_FOREGROUND
                                        putExtra(HrForegroundService.EXTRA_DEVICE_ADDRESS, address)
                                        putExtra(HrForegroundService.EXTRA_MAKE_ACTIVE, true)
                                        putExtra(HrForegroundService.EXTRA_SKIP_PLAN, skipPlan)
                                    }
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                },
                                onTestCue = {
                                    hrService?.playCue("Target heart rate reached. Keep it up!")
                                },
                                onOpenSettings = {
                                    navigateTo(Routes.SETTINGS)
                                },
                                onOpenHistory = {
                                    navigateTo(Routes.HISTORY)
                                },
                                onOpenManageDevices = {
                                    navigateTo(Routes.MANAGE_DEVICES)
                                },
                                onOpenTrainingPlan = {
                                    navigateTo(Routes.TRAINING_PLAN)
                                },
                                onOpenFullScreenMap = {
                                    navigateTo(Routes.MAP)
                                },
                                onToggleSimulation = { simulationEnabled, skipPlan ->
                                    scope.launch(Dispatchers.IO) {
                                        settingsRepository.setSimulationEnabled(simulationEnabled)
                                        val simulationIntent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                            action = HrForegroundService.ACTION_SET_SIMULATION
                                            putExtra(HrForegroundService.EXTRA_SIMULATION_ENABLED, simulationEnabled)
                                            putExtra(HrForegroundService.EXTRA_SKIP_PLAN, skipPlan)
                                        }
                                        ContextCompat.startForegroundService(this@MainActivity, simulationIntent)
                                    }
                                },
                                onRunModeChange = { runMode ->
                                    scope.launch(Dispatchers.IO) {
                                        settingsRepository.setRunMode(runMode)
                                    }
                                }
                            )
                        }
                        composable(Routes.MANAGE_DEVICES) {
                            ManageDevicesScreen(
                                settings = userSettings,
                                connectionStatus = serviceState?.value?.connectionStatus ?: "Disconnected",
                                scannedDevices = serviceState?.value?.scannedDevices ?: emptyList(),
                                isRunActive = serviceState?.value?.sessionStatus.let {
                                    it == SessionStatus.RUNNING || it == SessionStatus.PAUSED
                                },
                                onSetActive = { address ->
                                    scope.launch {
                                        settingsRepository.setActiveDevice(address)
                                    }
                                },
                                onRemove = { address ->
                                    // Release the live connection too when it's this strap:
                                    // otherwise the retry loop keeps chasing it and the verify
                                    // path re-saves (and re-activates) the device just forgotten.
                                    hrService?.forgetDevice(address)
                                    scope.launch {
                                        settingsRepository.removeDevice(address)
                                    }
                                },
                                onConnect = { address ->
                                    Log.d("MainActivity", "User tapped device in ManageDevices: $address")
                                    // Connect-only under #110: acquire/save the strap and return to the
                                    // record screen, where START owns the run and the plan-skip choice.
                                    // Deliberately no EXTRA_SKIP_PLAN — this action must not touch the
                                    // service's pending skip state that the eventual START will set.
                                    val intent = Intent(this@MainActivity, HrForegroundService::class.java).apply {
                                        action = HrForegroundService.ACTION_START_FOREGROUND
                                        putExtra(HrForegroundService.EXTRA_DEVICE_ADDRESS, address)
                                        putExtra(HrForegroundService.EXTRA_MAKE_ACTIVE, true)
                                    }
                                    ContextCompat.startForegroundService(this@MainActivity, intent)
                                    navigateTo(Routes.MAIN)
                                },
                                onScan = {
                                    // A fresh scan so a first (or replacement) strap can be discovered
                                    // and tapped to pair; the discovered list renders on this screen.
                                    // Without BLUETOOTH_SCAN (API 31+, e.g. a fresh install) the
                                    // service would go foreground only to dead-end in
                                    // startScanning()'s permission check — ask first and park the
                                    // scan; it fires from the permission callback once granted.
                                    val needsScanPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                        ContextCompat.checkSelfPermission(
                                            this@MainActivity, Manifest.permission.BLUETOOTH_SCAN
                                        ) != PackageManager.PERMISSION_GRANTED
                                    if (needsScanPermission) {
                                        pendingScan = true
                                        checkAndRequestPermissions()
                                    } else {
                                        sendForceScan()
                                    }
                                },
                                onBack = { navigateTo(Routes.MAIN) }
                            )
                        }
                        composable(Routes.SETTINGS) {
                            SettingsScreen(
                                settings = userSettings,
                                strapSummary = strapRowSummary(
                                    userSettings,
                                    serviceState?.value?.connectionStatus ?: "Disconnected"
                                ),
                                // Max HR goes through the repository rather than DataStore
                                // directly: the first deliberate set recomputes all history, and
                                // that must not be something a surface can forget to do (#112).
                                onMaxHrCommit = { maxHr ->
                                    scope.launch(Dispatchers.IO) { sessionRepository.setMaxHr(maxHr) }
                                },
                                onTargetZoneChange = { zone ->
                                    scope.launch(Dispatchers.IO) { settingsRepository.setTargetZone(zone) }
                                },
                                onCoachingEnabledChange = { enabled ->
                                    scope.launch(Dispatchers.IO) { settingsRepository.setCoachingEnabled(enabled) }
                                },
                                onSplitAnnouncementsChange = { enabled ->
                                    scope.launch(Dispatchers.IO) { settingsRepository.setSplitAnnouncementsEnabled(enabled) }
                                },
                                onAutoPauseChange = { enabled ->
                                    scope.launch(Dispatchers.IO) { settingsRepository.setAutoPauseEnabled(enabled) }
                                },
                                onAiDataSharingChange = { enabled ->
                                    scope.launch(Dispatchers.IO) { settingsRepository.setAiDataSharingEnabled(enabled) }
                                },
                                onTestingModeChange = { enabled ->
                                    scope.launch(Dispatchers.IO) { settingsRepository.setTestingModeEnabled(enabled) }
                                },
                                onManageStrap = { navigateTo(Routes.MANAGE_DEVICES) },
                                onBack = { navigateTo(Routes.MAIN) }
                            )
                        }
                        composable(Routes.HISTORY) {
                            HistoryScreen(
                                sessions = historySessions,
                                selectedSessionIds = selectedSessionIds,
                                onToggleSelection = { id -> historyViewModel.toggleSelection(id) },
                                onClearSelection = { historyViewModel.clearSelection() },
                                onDeleteSelected = { historyViewModel.deleteSelectedSessions() },
                                onSessionClick = { id ->
                                    navigateTo(Routes.sessionDetail(id))
                                },
                                onBack = { navigateTo(Routes.MAIN) }
                            )
                        }
                        composable(
                            route = Routes.SESSION_DETAIL,
                            arguments = listOf(navArgument(Routes.ARG_SESSION_ID) { type = NavType.LongType })
                        ) { backStackEntry ->
                            val sessionId = backStackEntry.arguments?.getLong(Routes.ARG_SESSION_ID)

                            val sessionSamples by produceState<List<com.example.runningapp.data.HrSample>>(initialValue = emptyList(), key1 = sessionId) {
                                sessionId?.let { id ->
                                    database.sampleDao().getSamplesForSession(id).collect { value = it }
                                }
                            }
                            val sessionIntervalStats by produceState<List<com.example.runningapp.data.RunWalkIntervalStat>>(initialValue = emptyList(), key1 = sessionId) {
                                sessionId?.let { id ->
                                    database.runWalkIntervalStatDao().getIntervalStatsForSessionFlow(id).collect { value = it }
                                }
                            }
                            val selectedSession by produceState<com.example.runningapp.data.RunnerSession?>(initialValue = null, key1 = sessionId) {
                                sessionId?.let { id ->
                                    database.sessionDao().getSessionByIdFlow(id).collect { value = it }
                                }
                            }

                            // Share is only offered for runs that actually recorded a route (#84).
                            val hasTrack by produceState(initialValue = false, key1 = sessionId) {
                                sessionId?.let { id ->
                                    sessionRepository.hasTrackFlow(id).collect { value = it }
                                }
                            }

                            // Inside this destination, and gated on the run that asked: an export is
                            // slow enough that the runner can be somewhere else by the time it
                            // lands, and a chooser opening over another screen interrupts whatever
                            // they went there to do. Keyed on the file, so one that arrived while
                            // this screen was being recreated still opens as soon as it is
                            // listening again.
                            LaunchedEffect(gpxShareReady, sessionId) {
                                gpxShareReady?.takeIf { it.sessionId == sessionId }?.let { file ->
                                    startActivity(gpxShareChooser(file))
                                    sessionDetailViewModel.gpxShareHandled()
                                }
                            }

                            SessionDetailScreen(
                                session = selectedSession,
                                samples = sessionSamples,
                                intervalStats = sessionIntervalStats,
                                onDeleteSession = { id ->
                                    sessionDetailViewModel.deleteSession(id)
                                },
                                onBack = { navigateTo(Routes.HISTORY) },
                                canShareGpx = hasTrack,
                                onShareGpx = { id -> sessionDetailViewModel.shareGpx(id) },
                                shareFailed = gpxShareFailed != null && gpxShareFailed == sessionId,
                                onShareFailureShown = { sessionDetailViewModel.gpxShareFailureShown() }
                            )
                        }
                        composable(Routes.TRAINING_PLAN) {
                            TrainingPlanScreen(
                                activePlanId = userSettings.activePlanId,
                                activeStageId = userSettings.activeStageId,
                                onActivatePlan = { planId, stageId ->
                                    scope.launch {
                                        settingsRepository.setActivePlan(planId, stageId)
                                    }
                                },
                                onBack = { navigateTo(Routes.MAIN) }
                            )
                        }
                        composable(Routes.MAP) {
                            FullScreenMapScreen(
                                state = serviceState.value,
                                sessionRepository = sessionRepository,
                                onBack = { navigateTo(Routes.MAIN) }
                            )
                        }
                    }

                    feelSheetSessionId?.let { sessionId ->
                        FeelFeedbackSheet(
                            onSave = { effort, note ->
                                scope.launch(Dispatchers.IO) {
                                    sessionRepository.saveFeelFeedback(sessionId, effort, note)
                                }
                                feelSheetSessionId = null
                            },
                            onDismiss = { feelSheetSessionId = null }
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
    coachPrescription: CoachPrescription?,
    sessionRepository: SessionRepository,
    paddingValues: PaddingValues = PaddingValues(0.dp),
    onRequestPermissions: () -> Unit,
    onStartRun: (Boolean, String) -> Unit,
    onRetryStrap: () -> Unit,
    onTogglePause: () -> Unit,
    onStopSession: () -> Unit,
    onConnectToDevice: (String, Boolean) -> Unit,
    onTestCue: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenManageDevices: () -> Unit,
    onOpenTrainingPlan: () -> Unit,
    onOpenFullScreenMap: () -> Unit,
    onToggleSimulation: (Boolean, Boolean) -> Unit,
    onRunModeChange: (String) -> Unit
) {
    // Skip today's plan (#107): a today-only choice that runs open-ended without touching the plan.
    // Defaults off every time the screen loads, so the plan is always queued unless actively skipped.
    var skipPlanToday by rememberSaveable { mutableStateOf(false) }

    // The selected run mode, held locally so a tap takes effect instantly for both the toggle
    // highlight and START — the settings write behind onRunModeChange is async, so reading it back
    // (via userSettings.runMode) would lag a just-made choice. Synced from settings when they change
    // externally; the toggle updates this and persists in the same tap.
    var selectedRunMode by rememberSaveable { mutableStateOf(userSettings.runMode) }
    LaunchedEffect(userSettings.runMode) { selectedRunMode = userSettings.runMode }

    val state = hrService?.hrState?.collectAsState()?.value ?: HrState()
    val activePlan = userSettings.activePlanId?.let { TrainingPlanProvider.getPlanById(it) }
    val activeStage = activePlan?.stages?.firstOrNull { it.id == userSettings.activeStageId } ?: activePlan?.stages?.firstOrNull()
    val baseWorkout = activeStage?.workouts?.firstOrNull()
    // No testing-mode check: turning testing mode on erases the debrief, and the coach is refused
    // the write while it stays on, so there is nothing left to filter out on read (#113).
    val coachMessage = userSettings.latestCoachMessage?.takeIf { it.isNotBlank() }
    // The card resolves today's workout itself (adaptation included) so the screen and the run
    // read the same numbers — see withCoachPrescription (#111).
    val todayCard = todayCardUiState(
        stageTitle = activeStage?.title,
        baseWorkout = baseWorkout,
        settings = userSettings,
        prescription = coachPrescription,
        nowEpochMillis = System.currentTimeMillis(),
        runMode = selectedRunMode,
        skippedToday = skipPlanToday
    )

    val isSessionActive = state.sessionStatus != SessionStatus.IDLE && state.sessionStatus != SessionStatus.STOPPED

    // Reach for the saved strap in the background while the record screen is up (#110): heart
    // rate is a sensor, so the app connects to it before you start and reports progress on the
    // sensor line — but it never blocks starting. Fires once per pre-run entry with a saved
    // device; skipped while simulating (no real strap) or once a run is active.
    //
    // Via the service intent, NOT hrService.connectToDevice(): a direct binder call races the
    // intent-based connects (Manage Devices, START) because it skips the onStartCommand queue —
    // the same race the onConnectToDevice comment documents. All connects funnel through
    // ACTION_START_FOREGROUND so they serialize on the service's main thread.
    val autoConnectContext = LocalContext.current
    val activeStrapAddress = userSettings.activeDeviceAddress
    LaunchedEffect(hrService, activeStrapAddress, isSessionActive, state.isSimulating) {
        // Checked at fire time, not as a key: without BLUETOOTH_CONNECT the service's connect
        // path dead-ends immediately, so promoting it to foreground here would strand an idle
        // notification + wake lock just from opening the record screen (Codex P2 #123). The
        // user can still connect explicitly — those taps run the permission prompt flow.
        val canConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                autoConnectContext, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        if (canConnect && !isSessionActive && !state.isSimulating && hrService != null &&
            activeStrapAddress != null && state.connectionStatus == "Disconnected"
        ) {
            val intent = Intent(autoConnectContext, HrForegroundService::class.java).apply {
                action = HrForegroundService.ACTION_START_FOREGROUND
                putExtra(HrForegroundService.EXTRA_DEVICE_ADDRESS, activeStrapAddress)
                // No EXTRA_MAKE_ACTIVE: this is a background attempt, not a user choice — its
                // verify must not out-promote a strap the user activates while it's in flight.
            }
            ContextCompat.startForegroundService(autoConnectContext, intent)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        bottomBar = {
            MainBottomBar(
                onOpenHistory = onOpenHistory,
                onOpenManageDevices = onOpenManageDevices,
                onOpenSettings = onOpenSettings
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(RunningUiTokens.PagePadding),
                verticalArrangement = Arrangement.spacedBy(RunningUiTokens.SectionSpacing),
                horizontalAlignment = Alignment.Start
            ) {
                item {
                    Text(text = "Running App", style = MaterialTheme.typography.headlineMedium)
                }
                item {
                    OutlinedButton(
                        onClick = onOpenTrainingPlan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = RunningUiTokens.MinTouchTarget)
                    ) {
                        Text("Open Training Plan")
                    }
                }

                // Treadmill / Outdoor is the one pre-run choice, pre-filled from last time (#107).
                if (!isSessionActive) {
                    item {
                        RunModeSelector(
                            runMode = selectedRunMode,
                            onRunModeChange = { mode ->
                                selectedRunMode = mode
                                onRunModeChange(mode)
                            }
                        )
                    }
                }

                if (!isSessionActive) {
                    item {
                        TodayCard(
                            state = todayCard,
                            onSkipToday = { skipPlanToday = true },
                            onUndoSkip = { skipPlanToday = false },
                            onChoosePlan = onOpenTrainingPlan
                        )
                    }

                    // Always shown when there is a debrief, adaptation or not. The card's note is
                    // only the debrief's first sentence (#113), so this is the one place the
                    // coach's full reasoning can be read — suppressing it when the card carried a
                    // note, as this used to, left the numbers changed and the reasoning nowhere.
                    // The two no longer duplicate each other: one line of what changed on the
                    // card, the whole argument here.
                    if (coachMessage != null) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
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
                }

                // In-run controls stay in the scroll area; the START button below is a pre-run
                // affordance only, so an active run shows Pause / Skip / Stop here instead.
                if (isSessionActive) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
                                Text("Controls", style = MaterialTheme.typography.labelLarge)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                    Button(
                                        onClick = onTogglePause,
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = RunningUiTokens.MinTouchTarget)
                                    ) {
                                        Text(if (state.sessionStatus == SessionStatus.PAUSED) "Resume" else "Pause")
                                    }
                                    Button(
                                        onClick = { hrService?.skipCurrentPhase() },
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = RunningUiTokens.MinTouchTarget)
                                    ) {
                                        val label = when (state.currentPhase) {
                                            SessionPhase.WARM_UP -> "Skip Warmup"
                                            SessionPhase.MAIN -> "Start Cooldown"
                                            SessionPhase.COOL_DOWN -> "End Session"
                                        }
                                        Text(label)
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = onStopSession,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = RunningUiTokens.MinTouchTarget)
                                ) {
                                    Text("Force Stop")
                                }
                            }
                        }
                    }
                }

                // Developer / testing tools. Kept off the clean pre-run path but retained because
                // the phone-first workflow drives runs through Simulate; user settings live behind
                // the Prefs tab in the bottom bar.
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = onRequestPermissions,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = RunningUiTokens.MinTouchTarget)
                        ) {
                            Text("Permissions")
                        }
                        Button(
                            onClick = { onToggleSimulation(!state.isSimulating, skipPlanToday) },
                            colors = if (state.isSimulating) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer) else ButtonDefaults.buttonColors(),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = RunningUiTokens.MinTouchTarget)
                        ) {
                            Text(if (state.isSimulating) "Stop Sim" else "Simulate")
                        }
                        OutlinedButton(
                            onClick = onTestCue,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = RunningUiTokens.MinTouchTarget)
                        ) {
                            Text("Test Cue")
                        }
                    }
                }

                item {
                    SettingsSummaryCard(settings = state.userSettings)
                }

                if (isSessionActive) {
                    item {
                        WorkoutView(state = state, sessionRepository = sessionRepository, onOpenFullScreenMap = onOpenFullScreenMap)
                    }
                }
            }

            // One sensor line and the always-live START, pinned to the bottom (#110). Pre-run
            // only: an active run shows its live controls in the scroll area above.
            if (!isSessionActive) {
                StartFooter(
                    connectionStatus = state.connectionStatus,
                    strapConnected = state.connectionStatus == "Connected",
                    isSimulating = state.isSimulating,
                    onStart = { onStartRun(skipPlanToday, selectedRunMode) },
                    // The activity-level handler re-acquires via the service intent (saved strap
                    // first, scan fallback) — no direct binder connect here, which would race the
                    // intent-based connect paths.
                    onRetryStrap = onRetryStrap
                )
            }
        }
    }
}

// Treadmill / Outdoor — the single pre-run choice, pre-filled from last time (#107).
@Composable
private fun RunModeSelector(runMode: String, onRunModeChange: (String) -> Unit) {
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
private fun StartFooter(
    connectionStatus: String,
    strapConnected: Boolean,
    isSimulating: Boolean,
    onStart: () -> Unit,
    onRetryStrap: () -> Unit
) {
    // "Looking for your strap…" is exactly an Acquisition in flight. One definition, shared with
    // the service's START guard and with Promotion — this used to be its own copy of the test.
    val looking = !isSimulating && isAcquiringStrap(connectionStatus)
    // The service's terminal give-up state (pre-run reconnect cap). The old key,
    // contains("Retrying"), matched a status that lives for milliseconds between retry cycles —
    // this state was designed for the strap-absent case but never actually rendered.
    val notFound = !isSimulating && connectionStatus == "Strap not found"

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

@Composable
private fun MainBottomBar(
    onOpenHistory: () -> Unit,
    onOpenManageDevices: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(shadowElevation = 6.dp, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilledTonalButton(
                onClick = { },
                enabled = false,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = RunningUiTokens.MinTouchTarget)
            ) {
                Text(
                    text = "Home",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }
            FilledTonalButton(
                onClick = onOpenHistory,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = RunningUiTokens.MinTouchTarget)
            ) {
                Text(
                    text = "History",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }
            FilledTonalButton(
                onClick = onOpenManageDevices,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = RunningUiTokens.MinTouchTarget)
            ) {
                Text(
                    text = "Devices",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }
            FilledTonalButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = RunningUiTokens.MinTouchTarget)
            ) {
                Text(
                    text = "Prefs",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * Renders [TodayCardUiState] — which is where what this card is and why lives.
 *
 * The one shape decision that belongs here: the link is a text link inside the card, bottom-right,
 * so it reads as an edit to the card it sits in rather than an alternative to starting — and undo
 * lands in the exact slot skip vacated, because the slot is the same one either way.
 */
@Composable
fun TodayCard(
    state: TodayCardUiState,
    onSkipToday: () -> Unit,
    onUndoSkip: () -> Unit,
    onChoosePlan: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(RunningUiTokens.CardPadding)) {
            Text(
                text = state.eyebrow,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = state.detailLine, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            TargetPill(text = state.targetPill)
            state.envelopeLine?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.coachNote?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.link.label,
                    style = MaterialTheme.typography.bodySmall,
                    textDecoration = TextDecoration.Underline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .clickable(role = Role.Button) {
                            when (state.link.kind) {
                                TodayCardLinkKind.SKIP -> onSkipToday()
                                TodayCardLinkKind.UNDO -> onUndoSkip()
                                TodayCardLinkKind.CHOOSE_PLAN -> onChoosePlan()
                            }
                        }
                        // The link stays small type by design, so the tap target is grown to the
                        // shared minimum around it rather than the text being made into a button.
                        .heightIn(min = RunningUiTokens.MinTouchTarget)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TargetPill(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun SettingsSummaryCard(
    settings: UserSettings
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(RunningUiTokens.CardPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val target = settings.targetHrZone
                Text(
                    "Zone ${target.number} · ${target.zoneName}: ${targetRangeLabel(target, settings.maxHr)} BPM",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                val modeLabel = if (settings.runMode == "outdoor") "Outdoor Run" else "Treadmill Run"
                Text("Mode: $modeLabel", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (settings.coachingEnabled) "Coaching ON" else "Coaching OFF",
                style = MaterialTheme.typography.bodySmall,
                color = if (settings.coachingEnabled) Color(0xFF9CF7AD) else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun WorkoutView(state: HrState, sessionRepository: SessionRepository, onOpenFullScreenMap: () -> Unit) {
    val uiState = remember(state) { mapWorkoutPlayerUiState(state) }
    val errorColor = MaterialTheme.colorScheme.error

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RunningUiTokens.CardPadding)
        ) {
            Text(uiState.intervalLabel, style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(uiState.phaseLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(uiState.countdownText, style = MaterialTheme.typography.displayLarge)
            LinearProgressIndicator(
                progress = uiState.progressFraction,
                modifier = Modifier.fillMaxWidth(),
                strokeCap = StrokeCap.Round
            )
            Text(uiState.progressLabel, style = MaterialTheme.typography.labelMedium)
            uiState.nextLabel?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(uiState.hrText, style = MaterialTheme.typography.titleLarge)
                    val zoneColor = zoneBandColor(uiState.zoneBand)
                    Text(uiState.zoneStatusText, color = zoneColor, style = MaterialTheme.typography.bodyMedium)
                }
                AssistChip(
                    onClick = { },
                    label = { Text(uiState.sensorFreshnessText) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (uiState.sensorStale) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                uiState.secondaryMetrics.forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }

            uiState.coachCue?.let { cue ->
                Spacer(modifier = Modifier.height(12.dp))
                val cueColor = when (cue.severity) {
                    CueSeverity.CRITICAL -> MaterialTheme.colorScheme.errorContainer
                    CueSeverity.WARNING -> MaterialTheme.colorScheme.primaryContainer
                    CueSeverity.INFO -> MaterialTheme.colorScheme.surfaceVariant
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = cueColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Coach", style = MaterialTheme.typography.labelMedium)
                        Text(cue.message, style = MaterialTheme.typography.bodyMedium)
                        Text("Reason: ${cue.reasonTag}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            uiState.timeline?.let { timeline ->
                Spacer(modifier = Modifier.height(12.dp))
                Text("Workout Timeline", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(6.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    if (timeline.segments.isEmpty()) return@Canvas
                    val segmentWidth = size.width / timeline.segments.size.toFloat()
                    timeline.segments.forEachIndexed { index, segment ->
                        val left = index * segmentWidth
                        val color = when (segment.type) {
                            TimelineSegmentType.RUN -> Color(0xFF78BDF4)
                            TimelineSegmentType.WALK -> Color(0xFFFFC261)
                            TimelineSegmentType.RECOVER -> Color(0xFF98E892)
                            TimelineSegmentType.OTHER -> Color(0xFF697789)
                        }
                        drawRect(
                            color = color,
                            topLeft = androidx.compose.ui.geometry.Offset(left, 8f),
                            size = androidx.compose.ui.geometry.Size(segmentWidth - 2f, 20f)
                        )
                    }
                    val currentLeft = timeline.currentSegmentIndex * segmentWidth
                    val markerX = currentLeft + (segmentWidth * timeline.currentSegmentFraction.coerceIn(0f, 1f))
                    drawLine(
                        color = Color.White,
                        start = androidx.compose.ui.geometry.Offset(markerX, 4f),
                        end = androidx.compose.ui.geometry.Offset(markerX, 36f),
                        strokeWidth = 4f
                    )
                    timeline.markers.forEach { marker ->
                        val markerBase = marker.segmentIndex * segmentWidth
                        val x = markerBase + (segmentWidth * marker.fractionInSegment.coerceIn(0f, 1f))
                        when (marker.type) {
                            TimelineMarkerType.PLANNED_TRANSITION -> {
                                drawLine(
                                    color = Color.White,
                                    start = androidx.compose.ui.geometry.Offset(x, 8f),
                                    end = androidx.compose.ui.geometry.Offset(x, 28f),
                                    strokeWidth = 2f
                                )
                            }
                            TimelineMarkerType.HR_TRIGGER -> {
                                drawCircle(
                                    color = errorColor,
                                    radius = 5f,
                                    center = androidx.compose.ui.geometry.Offset(x, 34f)
                                )
                                drawLine(
                                    color = errorColor,
                                    start = androidx.compose.ui.geometry.Offset(x - 5f, 29f),
                                    end = androidx.compose.ui.geometry.Offset(x + 5f, 39f),
                                    strokeWidth = 2f
                                )
                            }
                            TimelineMarkerType.HR_RECOVERY -> {
                                drawCircle(
                                    color = Color(0xFF9CF7AD),
                                    radius = 4f,
                                    center = androidx.compose.ui.geometry.Offset(x, 34f),
                                    style = Stroke(width = 2f)
                                )
                            }
                        }
                    }
                }
            }

            // The run's pinned mode, not the live setting (see HrState.activeRunMode): an outdoor
            // run started right after the mode toggle must render its map from the first second.
            if ((state.activeRunMode ?: state.userSettings.runMode) == "outdoor") {
                val mapSessionId = state.activeDbSessionId
                if (mapSessionId != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    MapCard(sessionId = mapSessionId, sessionRepository = sessionRepository, onClick = onOpenFullScreenMap)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Connection: ${state.connectionStatus}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Debug state: ${state.sessionStatus} • ${state.currentPhase}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (uiState.coachCue?.reasonTag == CUE_REASON_SENSOR_LOST || uiState.coachCue?.reasonTag == CUE_REASON_HR_HIGH) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Safety cue active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun ManageDevicesScreen(
    settings: UserSettings,
    connectionStatus: String,
    scannedDevices: List<BluetoothDevice>,
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
        Text("Status: $connectionStatus", style = MaterialTheme.typography.bodySmall)
        // Scan is the only way to pair a first strap (#110 removed the record-screen list): find
        // and tap a discovered strap here, and connecting it saves it below. Pairing is a pre-run
        // action — scanning drops the current strap, so it is disabled during an active run to keep
        // it from dropping the live session (the service ignores a mid-run scan for the same reason).
        val isScanning = connectionStatus.contains("Scanning", ignoreCase = true)
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

