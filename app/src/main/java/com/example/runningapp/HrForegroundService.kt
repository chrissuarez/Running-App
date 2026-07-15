package com.example.runningapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Binder
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.IBinder
import android.app.PendingIntent
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.jvm.Volatile
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.util.UUID
import java.util.LinkedList
import kotlin.math.roundToInt
import com.example.runningapp.data.AppDatabase
import com.example.runningapp.data.AiCoachClient
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunWalkIntervalStat
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
import com.example.runningapp.data.computeEasyFixedDurationSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SessionStatus { IDLE, CONNECTING, RUNNING, PAUSED, STOPPING, STOPPED, ERROR }
enum class SessionPhase { WARM_UP, MAIN, COOL_DOWN }
enum class StructuredWorkoutPhase { RUN, WALK }

// simple data class to hold the state
data class HrState(
    val connectionStatus: String = "Disconnected",
    val sessionStatus: SessionStatus = SessionStatus.IDLE,
    val sessionType: String = HrForegroundService.SESSION_TYPE_RUN_WALK,
    val bpm: Int = 0,
    val lastUpdateTimestamp: Long = 0,
    val connectedDeviceName: String? = null,
    val scannedDevices: List<BluetoothDevice> = emptyList(),
    val discoveredServices: List<String> = emptyList(),
    val lastPacketTimeFormatted: String = "--:--:--.---",
    val dataBits: String = "Unknown",
    
    // Coaching Debug Info
    val avgBpm: Int = 0,
    val currentZone: String = "No Data", 
    val timeInZoneString: String = "0s", 
    val cooldownWithHysteresisString: String = "Ready",
    
    // Session Engine Debug Info
    val secondsRunning: Long = 0,
    val secondsPaused: Long = 0,
    val reconnectAttempts: Int = 0,
    val lastHrAgeSeconds: Long = 0,
    val errorMessage: String? = null,
    
    // Mission 3: In-Memory Zone Timers
    val zoneTimes: Map<Int, Long> = mapOf(1 to 0L, 2 to 0L, 3 to 0L, 4 to 0L, 5 to 0L),
    val isSimulating: Boolean = false,

    val currentPhase: SessionPhase = SessionPhase.WARM_UP,
    val phaseSecondsRemaining: Int = 0,
    val phaseSecondsElapsed: Long = 0,
    val isStructuredWorkout: Boolean = false,
    val structuredWorkoutPhase: StructuredWorkoutPhase = StructuredWorkoutPhase.RUN,
    val phaseTimeRemainingSeconds: Int = 0,
    val currentRepeat: Int = 1,
    val totalRepeats: Int = 0,
    val currentIntervalPlannedSeconds: Int = 0,
    val nextIntervalType: StructuredWorkoutPhase? = null,
    val nextIntervalDurationSeconds: Int = 0,
    val workoutProgressPercent: Int = 0,
    val currentIntervalElapsedSeconds: Int = 0,
    val currentWalkReason: String = "Planned",
    val hrCapExceededInCurrentInterval: Boolean = false,
    val hrCapExceededAtSecond: Int? = null,
    
    // Mission 4: Outdoor Running
    val distanceKm: Double = 0.0,
    val paceMinPerKm: Double = 0.0,
    val runMode: String = "treadmill",

    // Mission 2: Settings Summary
    val userSettings: UserSettings = UserSettings(),

    val walkBreaksCount: Int = 0,

    // Post-run "How did that feel?" sheet: DB row id for the session the UI should prompt about
    val activeDbSessionId: Long? = null
)

class HrForegroundService : Service(), TextToSpeech.OnInitListener {
    private val easyFixedDurationMainSeconds = 30 * 60

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Detached from serviceScope on purpose: the save-time weather fetch must survive
    // onDestroy() cancelling serviceScope when a run is stopped from the background.
    private val weatherFetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Exposed state for UI
    private val _hrState = MutableStateFlow(HrState())
    val hrState: StateFlow<HrState> = _hrState.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    // UUIDs for Heart Rate Service and Measurement Characteristic
    private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HEART_RATE_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Reconnection & Rate Limiting State
    private var targetDeviceAddress: String? = null
    private var reconnectDelay = 3000L
    private var isReconnecting = false
    private var isActivityBound = false
    
    // TTS & Audio Focus
    private var tts: TextToSpeech? = null
    private var audioManager: AudioManager? = null
    private var audioCueManager: AudioCueManager? = null

    // Mission 4: Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationTracker: LocationTracker? = null
    private var lastNotificationZone = -1
    private var lastNotificationPhase = SessionPhase.WARM_UP
    
    // Mission: Resilient Tracking Loop
    private var sessionHandlerThread: HandlerThread? = null
    private var sessionHandler: Handler? = null
    private val sessionTimerRunnable = object : Runnable {
        override fun run() {
            pulseSession()
            // Mission: Stop the zombie loop if status is STOPPED or IDLE
            val status = _hrState.value.sessionStatus
            if (status != SessionStatus.STOPPED && status != SessionStatus.IDLE) {
                sessionHandler?.postDelayed(this, 1000)
            } else {
                Log.d(TAG, "Timer loop exiting - status is $status")
            }
        }
    }
    
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var sessionRepository: SessionRepository
    private var currentSettings = UserSettings()
    @Volatile private var currentSessionType: String = SESSION_TYPE_RUN_WALK

    private lateinit var database: AppDatabase
    private var currentSessionId: Long? = null
    private var sessionMaxBpm = 0
    private var sessionBpmSum = 0L
    private var sessionNoDataSeconds = 0L
    private var sessionSampleCount = 0
    private var sessionInTargetZoneSeconds = 0L
    private var lastRecordedSecond = -1L
    
    // Mission 3: In-Memory Zone Tracking
    private val sessionZoneTimes = mutableMapOf(1 to 0L, 2 to 0L, 3 to 0L, 4 to 0L, 5 to 0L)
    private var isSimulationEnabled = false
    private var simulationBpm = 70
    private var simulationDirection = 1

    // --- Coaching Rules Engine State ---
    private val HISTORY_WINDOW_MS = 5000L
    
    // Pair<Timestamp, Bpm>
    private val bpmHistory = LinkedList<Pair<Long, Int>>()
    
    private enum class Zone { LOW, TARGET, HIGH, UNKNOWN }
    private var currentZone = Zone.UNKNOWN
    private var zoneEnterTime = 0L
    
    private var lastCueTime = 0L
    private var baselineHr: Int? = null
    private var lastDriftCueTime = 0L
    private var wasInHighZoneSinceRunIntervalStart = false
    private var recoveryCueEligibleSinceMs = 0L
    private var lastRecoveryCueBpm = 0
    
    // --- Session Engine State ---
    @Volatile private var sessionSecondsRunning = 0L
    private var sessionSecondsPaused = 0L
    private var reconnectAttemptCount = 0
    private var lastHrTimestamp = 0L
    private var firstDisconnectTime = 0L
    private val RECONNECT_TIMEOUT_MS = 120_000L // 2 minutes

    // Auto-pause on standstill (#39). Distinguishes a PAUSED session that SessionRecorder itself
    // triggered from a manual pause, so togglePause()/pauseSession() take precedence and GPS
    // (which must keep running at 1 Hz through an auto-pause to detect resume) is only stopped
    // on a manual pause.
    @Volatile private var isAutoPaused = false
    
    // Mission: Session Phases
    @Volatile private var currentPhase = SessionPhase.WARM_UP
    private var phaseSecondsRunning = 0L
    private var walkBreaksCount = 0
    @Volatile private var isWarmupSkipped = false
    @Volatile private var currentWarmupDuration = 480
    @Volatile private var isStructuredWorkout = false
    @Volatile private var structuredWorkoutPhase = StructuredWorkoutPhase.RUN
    @Volatile private var phaseTimeRemainingSeconds = 0
    @Volatile private var currentRepeat = 1
    @Volatile private var isCreatingSession = false
    private var activeWorkoutTemplate: WorkoutTemplate? = null
    private var hasStructuredWorkoutStarted = false
    private val sessionCreationLock = Any()
    private var currentSessionIncludeInAiTraining = true
    private data class RunIntervalTracker(
        val intervalIndex: Int,
        val plannedDurationSeconds: Int,
        val startSessionSecond: Long,
        var elapsedSeconds: Int = 0,
        var firstHrTriggerSecondIntoInterval: Int? = null,
        var actualRunningDurationBeforeHrTriggerSeconds: Int? = null,
        var hrTriggerEvents: Int = 0,
        var walkingRecoverySeconds: Int = 0,
        var isInRecoveryWindow: Boolean = false,
        var triggerHrSum: Double = 0.0,
        var triggerHrCount: Int = 0,
        var recoveryDurationSumSeconds: Int = 0,
        var recoveryEventCount: Int = 0,
        var activeRecoveryStartSecond: Int? = null
    )
    private var activeRunIntervalTracker: RunIntervalTracker? = null

    private fun resetRunIntervalRecoveryCueState() {
        wasInHighZoneSinceRunIntervalStart = false
        recoveryCueEligibleSinceMs = 0L
        lastRecoveryCueBpm = 0
    }
    private val completedRunIntervalStats = mutableListOf<RunWalkIntervalStat>()

    companion object {
        const val CHANNEL_ID = "HrServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_FOREGROUND = "ACTION_START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "ACTION_STOP_FOREGROUND"
        const val ACTION_PAUSE_SESSION = "ACTION_PAUSE_SESSION"
        const val ACTION_RESUME_SESSION = "ACTION_RESUME_SESSION"
        const val ACTION_FORCE_SCAN = "ACTION_FORCE_SCAN"
        const val ACTION_SET_SIMULATION = "ACTION_SET_SIMULATION"
        const val EXTRA_DEVICE_ADDRESS = "EXTRA_DEVICE_ADDRESS"
        const val EXTRA_SESSION_TYPE = "SESSION_TYPE"
        const val LEGACY_EXTRA_SESSION_TYPE = "EXTRA_SESSION_TYPE"
        const val EXTRA_SIMULATION_ENABLED = "SIMULATION_ENABLED"
        const val SESSION_TYPE_RUN_WALK = "Run/Walk"
        const val SESSION_TYPE_EASY_FIXED_DURATION = "Easy Fixed Duration"
        const val SESSION_TYPE_ZONE2_WALK = "Zone 2 Walk"
        const val SESSION_TYPE_FREE_TRACK = "Free Track"
        const val TAG = "HrService"
    }

    private fun sanitizeSessionType(value: String?): String {
        return when (value) {
            SESSION_TYPE_RUN_WALK,
            SESSION_TYPE_EASY_FIXED_DURATION,
            SESSION_TYPE_ZONE2_WALK,
            SESSION_TYPE_FREE_TRACK -> value
            else -> SESSION_TYPE_RUN_WALK
        }
    }

    private fun logBleDecision(reason: String, detail: String) {
        Log.d(TAG, "BLE decision: $reason | $detail")
    }

    private fun startHardwareSession(overrideAddress: String?) {
        if (overrideAddress != null) {
            logBleDecision("direct_connect", "Using override device address=$overrideAddress")
            connectToDevice(overrideAddress)
            return
        }

        val savedAddress = currentSettings.activeDeviceAddress
        if (savedAddress != null) {
            logBleDecision("saved_device_reconnect", "Using saved activeDeviceAddress=$savedAddress")
            connectToDevice(savedAddress)
        } else {
            logBleDecision("fresh_scan", "No saved device available; starting BLE scan")
            startScanning()
        }
    }

    private fun resetRunIntervalTracking() {
        activeRunIntervalTracker = null
        completedRunIntervalStats.clear()
    }

    private fun getMainPhaseLimitSeconds(): Int {
        // Easy Fixed Duration is the only non-structured mode with a real main-phase timer.
        return when (currentSessionType) {
            SESSION_TYPE_EASY_FIXED_DURATION -> easyFixedDurationMainSeconds
            else -> Int.MAX_VALUE
        }
    }

    private suspend fun buildEasyFixedDurationSummary(
        sessionId: Long,
        actualDurationSeconds: Int,
        avgBpm: Int,
        maxBpm: Int
    ) = computeEasyFixedDurationSummary(
        plannedDurationSeconds = easyFixedDurationMainSeconds,
        actualDurationSeconds = actualDurationSeconds.coerceAtLeast(0),
        avgBpm = avgBpm,
        maxBpm = maxBpm,
        // For easy sessions, time above the easy cap is simply time above Zone 2.
        timeAboveEasyCapSeconds = ((sessionZoneTimes[3] ?: 0L) + (sessionZoneTimes[4] ?: 0L) + (sessionZoneTimes[5] ?: 0L))
            .toInt(),
        noDataSeconds = sessionNoDataSeconds,
        samples = database.sampleDao().getSamplesForSessionOnce(sessionId)
    )

    private data class StructuredProgressUiState(
        val totalRepeats: Int = 0,
        val currentIntervalPlannedSeconds: Int = 0,
        val nextIntervalType: StructuredWorkoutPhase? = null,
        val nextIntervalDurationSeconds: Int = 0,
        val workoutProgressPercent: Int = 0,
        val currentIntervalElapsedSeconds: Int = 0
    )

    private var currentWalkReasonState = "Planned"
    private var hrCapExceededInCurrentIntervalState = false
    private var hrCapExceededAtSecondState: Int? = null

    private fun resetCurrentIntervalTransparencyState() {
        currentWalkReasonState = "Planned"
        hrCapExceededInCurrentIntervalState = false
        hrCapExceededAtSecondState = null
    }

    private fun buildStructuredProgressUiState(): StructuredProgressUiState {
        val workout = activeWorkoutTemplate
        if (currentPhase != SessionPhase.MAIN ||
            !isStructuredWorkout ||
            currentSessionType != SESSION_TYPE_RUN_WALK ||
            workout == null ||
            workout.totalRepeats <= 0
        ) {
            return StructuredProgressUiState()
        }

        val totalRepeats = workout.totalRepeats
        val walkSeconds = workout.walkDurationSeconds.coerceAtLeast(0)
        val currentPlannedSeconds = when (structuredWorkoutPhase) {
            StructuredWorkoutPhase.RUN -> workout.runDurationSeconds.coerceAtLeast(0)
            StructuredWorkoutPhase.WALK -> walkSeconds
        }

        val remainingSeconds = phaseTimeRemainingSeconds.coerceAtLeast(0)
        val elapsedSeconds = (currentPlannedSeconds - remainingSeconds).coerceIn(0, currentPlannedSeconds)

        val (nextType, nextDurationSeconds) = when (structuredWorkoutPhase) {
            StructuredWorkoutPhase.RUN -> {
                if (walkSeconds > 0) {
                    StructuredWorkoutPhase.WALK to walkSeconds
                } else if (currentRepeat < totalRepeats) {
                    StructuredWorkoutPhase.RUN to workout.runDurationSeconds.coerceAtLeast(0)
                } else {
                    null to 0
                }
            }
            StructuredWorkoutPhase.WALK -> {
                if (currentRepeat < totalRepeats) {
                    StructuredWorkoutPhase.RUN to workout.runDurationSeconds.coerceAtLeast(0)
                } else {
                    null to 0
                }
            }
        }

        val totalSegments = if (walkSeconds > 0) totalRepeats * 2 else totalRepeats
        val completedSegmentsBeforeCurrent = when (structuredWorkoutPhase) {
            StructuredWorkoutPhase.RUN -> {
                if (walkSeconds > 0) (currentRepeat - 1).coerceAtLeast(0) * 2
                else (currentRepeat - 1).coerceAtLeast(0)
            }
            StructuredWorkoutPhase.WALK -> ((currentRepeat - 1).coerceAtLeast(0) * 2) + 1
        }.coerceAtLeast(0)

        val segmentFraction = if (currentPlannedSeconds > 0) {
            elapsedSeconds.toDouble() / currentPlannedSeconds.toDouble()
        } else {
            0.0
        }

        val workoutProgressPercent = if (totalSegments > 0) {
            (((completedSegmentsBeforeCurrent.toDouble() + segmentFraction) / totalSegments.toDouble()) * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
        } else {
            0
        }

        return StructuredProgressUiState(
            totalRepeats = totalRepeats,
            currentIntervalPlannedSeconds = currentPlannedSeconds,
            nextIntervalType = nextType,
            nextIntervalDurationSeconds = nextDurationSeconds,
            workoutProgressPercent = workoutProgressPercent,
            currentIntervalElapsedSeconds = elapsedSeconds
        )
    }

    private fun startRunIntervalTracking(intervalIndex: Int, plannedDurationSeconds: Int) {
        if (currentSessionType != SESSION_TYPE_RUN_WALK || plannedDurationSeconds <= 0) return
        if (activeRunIntervalTracker != null) {
            finalizeActiveRunIntervalTracking()
        }
        resetCurrentIntervalTransparencyState()
        activeRunIntervalTracker = RunIntervalTracker(
            intervalIndex = intervalIndex,
            plannedDurationSeconds = plannedDurationSeconds,
            startSessionSecond = sessionSecondsRunning
        )
    }

    private fun recordRunIntervalSecond() {
        val tracker = activeRunIntervalTracker ?: return
        tracker.elapsedSeconds += 1
        if (tracker.isInRecoveryWindow) {
            tracker.walkingRecoverySeconds += 1
        }
    }

    private fun recordRunWalkHighHrTriggerEvent(avgBpm: Int) {
        if (currentSessionType != SESSION_TYPE_RUN_WALK ||
            currentPhase != SessionPhase.MAIN ||
            !isStructuredWorkout ||
            structuredWorkoutPhase != StructuredWorkoutPhase.RUN
        ) {
            return
        }
        val tracker = activeRunIntervalTracker ?: return
        val elapsedAtTrigger = maxOf(
            tracker.elapsedSeconds,
            (sessionSecondsRunning - tracker.startSessionSecond).coerceAtLeast(0).toInt()
        )
        if (tracker.firstHrTriggerSecondIntoInterval == null) {
            tracker.firstHrTriggerSecondIntoInterval = elapsedAtTrigger
            tracker.actualRunningDurationBeforeHrTriggerSeconds = elapsedAtTrigger
        }
        if (!hrCapExceededInCurrentIntervalState) {
            hrCapExceededInCurrentIntervalState = true
            hrCapExceededAtSecondState = elapsedAtTrigger
        }
        currentWalkReasonState = "HR-triggered"
        tracker.hrTriggerEvents += 1
        tracker.triggerHrSum += avgBpm.toDouble()
        tracker.triggerHrCount += 1
        tracker.isInRecoveryWindow = true
        if (tracker.activeRecoveryStartSecond == null) {
            tracker.activeRecoveryStartSecond = elapsedAtTrigger
        }
    }

    private fun recordRunWalkRecoveryCueEvent() {
        if (currentSessionType != SESSION_TYPE_RUN_WALK ||
            currentPhase != SessionPhase.MAIN ||
            !isStructuredWorkout ||
            structuredWorkoutPhase != StructuredWorkoutPhase.RUN
        ) {
            return
        }
        val tracker = activeRunIntervalTracker ?: return
        val elapsedAtRecovery = maxOf(
            tracker.elapsedSeconds,
            (sessionSecondsRunning - tracker.startSessionSecond).coerceAtLeast(0).toInt()
        )
        closeActiveRecoveryWindow(tracker, elapsedAtRecovery)
        tracker.isInRecoveryWindow = false
    }

    private fun closeActiveRecoveryWindow(
        tracker: RunIntervalTracker,
        endElapsedSeconds: Int
    ) {
        val start = tracker.activeRecoveryStartSecond ?: return
        val duration = (endElapsedSeconds - start).coerceAtLeast(0)
        tracker.recoveryDurationSumSeconds += duration
        tracker.recoveryEventCount += 1
        tracker.activeRecoveryStartSecond = null
    }

    private fun finalizeActiveRunIntervalTracking() {
        val tracker = activeRunIntervalTracker ?: return
        val sessionId = currentSessionId
        closeActiveRecoveryWindow(tracker, tracker.elapsedSeconds)
        val actualBeforeTrigger = tracker.actualRunningDurationBeforeHrTriggerSeconds
            ?: tracker.elapsedSeconds
        val avgHrAtTrigger = if (tracker.triggerHrCount > 0) {
            tracker.triggerHrSum / tracker.triggerHrCount.toDouble()
        } else {
            null
        }
        val avgRecoverySeconds = if (tracker.recoveryEventCount > 0) {
            tracker.recoveryDurationSumSeconds.toDouble() / tracker.recoveryEventCount.toDouble()
        } else {
            null
        }
        if (sessionId != null) {
            completedRunIntervalStats += RunWalkIntervalStat(
                sessionId = sessionId,
                intervalIndex = tracker.intervalIndex,
                plannedDurationSeconds = tracker.plannedDurationSeconds,
                actualRunningDurationBeforeHrTriggerSeconds = actualBeforeTrigger,
                timeIntoIntervalWhenHrExceededCapSeconds = tracker.firstHrTriggerSecondIntoInterval,
                hrTriggerEvents = tracker.hrTriggerEvents,
                totalTimeSpentWalkingDuringRunIntervalSeconds = tracker.walkingRecoverySeconds,
                avgHrAtTriggerInInterval = avgHrAtTrigger,
                avgRecoverySecondsAfterTriggerInInterval = avgRecoverySeconds
            )
        }
        activeRunIntervalTracker = null
    }

    private suspend fun persistRunIntervalStats(sessionId: Long) {
        if (completedRunIntervalStats.isEmpty()) return
        val statsToPersist = completedRunIntervalStats
            .filter { it.sessionId == sessionId }
        if (statsToPersist.isNotEmpty()) {
            database.runWalkIntervalStatDao().insertIntervalStats(statsToPersist)
            Log.d(TAG, "Persisted ${statsToPersist.size} run interval stats for session $sessionId")
        }
        completedRunIntervalStats.clear()
    }

    inner class LocalBinder : Binder() {
        fun getService(): HrForegroundService = this@HrForegroundService
    }

    fun isRunning(): Boolean {
        return _hrState.value.sessionStatus == SessionStatus.RUNNING || 
               _hrState.value.sessionStatus == SessionStatus.PAUSED ||
               _hrState.value.sessionStatus == SessionStatus.CONNECTING ||
               _hrState.value.sessionStatus == SessionStatus.ERROR
    }

    fun isSessionActive(): Boolean = isRunning()

    override fun onBind(intent: Intent): IBinder {
        isActivityBound = true
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isActivityBound = false
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        isActivityBound = true
        super.onRebind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        val appContainer = runningAppContainer()
        settingsRepository = appContainer.settingsRepository
        
        serviceScope.launch {
            settingsRepository.userSettingsFlow.collect { settings ->
                currentSettings = settings
                _hrState.update { it.copy(userSettings = settings) }
            }
        }
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
        tts?.let { audioCueManager = AudioCueManager(it, audioManager, serviceScope, TAG) }
        
        
        database = appContainer.database
        sessionRepository = appContainer.sessionRepository
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationTracker = LocationTracker(
            context = this,
            fusedLocationClient = fusedLocationClient,
            logTag = TAG,
            playCue = { playCue(it) },
            getSessionStatus = { _hrState.value.sessionStatus },
            getShouldTrack = { currentSettings.runMode == "outdoor" && !isSimulationEnabled },
            isSplitAnnouncementsEnabled = { currentSettings.splitAnnouncementsEnabled },
            onMetricsUpdated = { distanceKm, paceMinPerKm, lastLocation ->
                _hrState.update { it.copy(distanceKm = distanceKm, paceMinPerKm = paceMinPerKm) }
            },
            isAutoPauseEnabled = { currentSettings.autoPauseEnabled },
            onAutoPause = { serviceScope.launch { autoPauseSession() } },
            onAutoResume = { serviceScope.launch { autoResumeSession() } },
            onRawFix = { location, barometerPressureHpa ->
                val sessionId = currentSessionId
                if (sessionId != null) {
                    val trackPoint = TrackPoint(
                        sessionId = sessionId,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                        horizontalAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                        verticalAccuracyMeters = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
                        speedMps = if (location.hasSpeed()) location.speed else null,
                        barometerPressureHpa = barometerPressureHpa,
                        timestampMillis = location.time,
                        source = TrackPointSource.GPS
                    )
                    serviceScope.launch(Dispatchers.IO) {
                        database.trackPointDao().insertTrackPoint(trackPoint)
                    }
                }
            }
        )
        
        // Mission: Dedicated Session Thread
        sessionHandlerThread = HandlerThread("SessionTrackingThread").apply { start() }
        sessionHandler = Handler(sessionHandlerThread!!.looper)
        
        createNotificationChannel()
    }

    private fun startSessionTimerLoop() {
        sessionHandler?.removeCallbacks(sessionTimerRunnable)
        lastPulseTime = 0L
        sessionHandler?.post(sessionTimerRunnable)
    }

    private var lastPulseTime = 0L
    private fun pulseSession() {
        val now = System.currentTimeMillis()
        if (lastPulseTime == 0L) {
            lastPulseTime = now
            return
        }
        val deltaSeconds = (now - lastPulseTime) / 1000
        if (deltaSeconds < 1) return 
        lastPulseTime = now

        if (isSimulationEnabled) {
            updateSimulationData()
        }

        val currentState = _hrState.value
        val hrAge = if (lastHrTimestamp > 0) (now - lastHrTimestamp) / 1000 else 0
        
        // Mission: 1Hz Heartbeat for log verification
        Log.d(TAG, "Timer heartbeat: running=${sessionSecondsRunning}s, age=${hrAge}s, status=${currentState.sessionStatus}")
        Log.d(
            TAG,
            "Phase debug: phase=$currentPhase phaseElapsed=${phaseSecondsRunning}s totalElapsed=${sessionSecondsRunning}s " +
                "repeat=$currentRepeat structured=$isStructuredWorkout " +
                "segment=${if (isStructuredWorkout) structuredWorkoutPhase else "NONE"} segmentRemaining=${phaseTimeRemainingSeconds}s"
        )

        when (currentState.sessionStatus) {
            SessionStatus.RUNNING -> {
                val startSecond = sessionSecondsRunning
                val endSecond = sessionSecondsRunning + deltaSeconds
                
                for (sec in (startSecond + 1)..endSecond) {
                    sessionSecondsRunning = sec
                    phaseSecondsRunning += 1
                    
                    val phaseLimit = when (currentPhase) {
                        SessionPhase.WARM_UP -> currentSettings.warmUpDurationSeconds
                        SessionPhase.MAIN -> getMainPhaseLimitSeconds()
                        SessionPhase.COOL_DOWN -> currentSettings.coolDownDurationSeconds
                    }
                    
                    val remaining = (phaseLimit - phaseSecondsRunning).toInt()
                    
                    if (currentPhase != SessionPhase.MAIN && remaining == 10) {
                        val phaseName = if (currentPhase == SessionPhase.WARM_UP) "warm up" else "cool down"
                        playCue("10 seconds of $phaseName remaining")
                    }
                    
                    if (currentPhase == SessionPhase.WARM_UP && phaseSecondsRunning >= phaseLimit) {
                        currentPhase = SessionPhase.MAIN
                        phaseSecondsRunning = 0
                        playCue("Starting main workout")
                    } else if (
                        currentPhase == SessionPhase.MAIN &&
                        currentSessionType == SESSION_TYPE_EASY_FIXED_DURATION &&
                        phaseSecondsRunning >= phaseLimit
                    ) {
                        // Reuse the existing cool-down/stop flow once the fixed 30-minute main block is done.
                        playCue("Easy session complete, beginning cool down.")
                        currentPhase = SessionPhase.COOL_DOWN
                        phaseSecondsRunning = 0
                    } else if (currentPhase == SessionPhase.COOL_DOWN && phaseSecondsRunning >= phaseLimit) {
                        serviceScope.launch { stopSession() }
                        break
                    }

                    if (currentPhase == SessionPhase.MAIN && isStructuredWorkout && currentSessionType == SESSION_TYPE_RUN_WALK) {
                        val workout = activeWorkoutTemplate
                        if (workout != null && workout.totalRepeats > 0 &&
                            (isWarmupSkipped || sessionSecondsRunning >= currentWarmupDuration)
                        ) {
                            if (!hasStructuredWorkoutStarted) {
                                hasStructuredWorkoutStarted = true
                                structuredWorkoutPhase = StructuredWorkoutPhase.RUN
                                if (phaseTimeRemainingSeconds <= 0) {
                                    phaseTimeRemainingSeconds = workout.runDurationSeconds
                                }
                                startRunIntervalTracking(
                                    intervalIndex = currentRepeat,
                                    plannedDurationSeconds = workout.runDurationSeconds
                                )
                                playCue("Start running, interval $currentRepeat of ${workout.totalRepeats}.")
                            }

                            if (hasStructuredWorkoutStarted && phaseTimeRemainingSeconds > 0) {
                                phaseTimeRemainingSeconds -= 1
                                if (structuredWorkoutPhase == StructuredWorkoutPhase.RUN) {
                                    recordRunIntervalSecond()
                                }
                                if (phaseTimeRemainingSeconds <= 0) {
                                    onStructuredWorkoutPhaseComplete(workout)
                                }
                            }
                        }
                    }

                    if (sec == 600L && currentState.bpm > 0) {
                        baselineHr = currentState.bpm
                        Log.d(TAG, "Drift Baseline captured at 10m: $baselineHr")
                    }

                    if (sessionSecondsRunning > lastRecordedSecond) {
                        lastRecordedSecond = sessionSecondsRunning
                        val currentBpm = currentState.bpm
                        if (currentBpm > 0) {
                            sessionMaxBpm = maxOf(sessionMaxBpm, currentBpm)
                            sessionBpmSum += currentBpm
                            sessionSampleCount += 1
                            
                            val zone = calculateZone(currentBpm, currentSettings)
                            if (zone in 1..5) {
                                sessionZoneTimes[zone] = (sessionZoneTimes[zone] ?: 0L) + 1
                                if (zone == 2) sessionInTargetZoneSeconds += 1
                            } else {
                                sessionNoDataSeconds += 1
                            }
                            
                            val sessionId = currentSessionId
                            if (sessionId != null) {
                                val sample = HrSample(
                                    sessionId = sessionId,
                                    elapsedSeconds = sessionSecondsRunning,
                                    rawBpm = currentBpm,
                                    smoothedBpm = currentState.avgBpm,
                                    connectionState = currentState.connectionStatus,
                                    paceMinPerKm = currentState.paceMinPerKm
                                )
                                serviceScope.launch(Dispatchers.IO) {
                                    database.sampleDao().insertSample(sample)
                                }
                            }
                        }
                    }
                }

                // Mission: Throttled Notification Updates (10s in background)
                val statusChanged = currentPhase != currentState.currentPhase || 
                                   currentState.connectionStatus.contains("Failed")
                
                if (sessionSecondsRunning % 10L == 0L || deltaSeconds > 1 || statusChanged) {
                    updateNotification(forceUpdate = statusChanged)
                }

                _hrState.update { 
                    val structuredProgress = buildStructuredProgressUiState()
                    it.copy(
                        secondsRunning = sessionSecondsRunning,
                        lastHrAgeSeconds = hrAge,
                        zoneTimes = sessionZoneTimes.toMap(),
                        isSimulating = isSimulationEnabled,
                        currentPhase = currentPhase,
                        phaseSecondsRemaining = when (currentPhase) {
                            SessionPhase.MAIN -> {
                                val mainLimit = getMainPhaseLimitSeconds()
                                if (mainLimit == Int.MAX_VALUE) 0 else (mainLimit - phaseSecondsRunning).toInt().coerceAtLeast(0)
                            }
                            else -> {
                            val limit = when (currentPhase) {
                                SessionPhase.WARM_UP -> currentSettings.warmUpDurationSeconds
                                SessionPhase.COOL_DOWN -> currentSettings.coolDownDurationSeconds
                                else -> 0
                            }
                                (limit - phaseSecondsRunning).toInt().coerceAtLeast(0)
                            }
                        },
                        phaseSecondsElapsed = phaseSecondsRunning,
                        isStructuredWorkout = isStructuredWorkout,
                        structuredWorkoutPhase = structuredWorkoutPhase,
                        phaseTimeRemainingSeconds = phaseTimeRemainingSeconds.coerceAtLeast(0),
                        currentRepeat = currentRepeat,
                        totalRepeats = structuredProgress.totalRepeats,
                        currentIntervalPlannedSeconds = structuredProgress.currentIntervalPlannedSeconds,
                        nextIntervalType = structuredProgress.nextIntervalType,
                        nextIntervalDurationSeconds = structuredProgress.nextIntervalDurationSeconds,
                        workoutProgressPercent = structuredProgress.workoutProgressPercent,
                        currentIntervalElapsedSeconds = structuredProgress.currentIntervalElapsedSeconds,
                        currentWalkReason = currentWalkReasonState,
                        hrCapExceededInCurrentInterval = hrCapExceededInCurrentIntervalState,
                        hrCapExceededAtSecond = hrCapExceededAtSecondState,
                        walkBreaksCount = walkBreaksCount
                    )
                }
            }
            SessionStatus.PAUSED -> {
                sessionSecondsPaused += deltaSeconds
                _hrState.update { 
                    val structuredProgress = buildStructuredProgressUiState()
                    it.copy(
                        secondsPaused = sessionSecondsPaused,
                        lastHrAgeSeconds = hrAge,
                        currentPhase = currentPhase,
                        phaseSecondsRemaining = when (currentPhase) {
                            SessionPhase.MAIN -> {
                                val mainLimit = getMainPhaseLimitSeconds()
                                if (mainLimit == Int.MAX_VALUE) 0 else (mainLimit - phaseSecondsRunning).toInt().coerceAtLeast(0)
                            }
                            else -> {
                            val limit = when (currentPhase) {
                                SessionPhase.WARM_UP -> currentSettings.warmUpDurationSeconds
                                SessionPhase.COOL_DOWN -> currentSettings.coolDownDurationSeconds
                                else -> 0
                            }
                                (limit - phaseSecondsRunning).toInt().coerceAtLeast(0)
                            }
                        },
                        phaseSecondsElapsed = phaseSecondsRunning,
                        isStructuredWorkout = isStructuredWorkout,
                        structuredWorkoutPhase = structuredWorkoutPhase,
                        phaseTimeRemainingSeconds = phaseTimeRemainingSeconds.coerceAtLeast(0),
                        currentRepeat = currentRepeat,
                        totalRepeats = structuredProgress.totalRepeats,
                        currentIntervalPlannedSeconds = structuredProgress.currentIntervalPlannedSeconds,
                        nextIntervalType = structuredProgress.nextIntervalType,
                        nextIntervalDurationSeconds = structuredProgress.nextIntervalDurationSeconds,
                        workoutProgressPercent = structuredProgress.workoutProgressPercent,
                        currentIntervalElapsedSeconds = structuredProgress.currentIntervalElapsedSeconds,
                        currentWalkReason = currentWalkReasonState,
                        hrCapExceededInCurrentInterval = hrCapExceededInCurrentIntervalState,
                        hrCapExceededAtSecond = hrCapExceededAtSecondState,
                        walkBreaksCount = walkBreaksCount
                    )
                }
            }
            SessionStatus.CONNECTING -> {
                if (firstDisconnectTime > 0 && (now - firstDisconnectTime > RECONNECT_TIMEOUT_MS)) {
                    _hrState.update { 
                        it.copy(
                            sessionStatus = SessionStatus.ERROR,
                            errorMessage = "Reconnect Timeout (2m)",
                            lastHrAgeSeconds = hrAge
                        )
                    }
                } else {
                    _hrState.update { it.copy(lastHrAgeSeconds = hrAge) }
                }
            }
            else -> _hrState.update { it.copy(lastHrAgeSeconds = hrAge) }
        }
        updateNotification()
    }
    
    private fun formatTime(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return "%02d:%02d".format(mins, secs)
    }

    private fun buildNotificationContent(state: HrState): String {
        val phaseName = when (state.currentPhase) {
            SessionPhase.WARM_UP -> "Warm-up"
            SessionPhase.MAIN -> "Main"
            SessionPhase.COOL_DOWN -> "Cooldown"
        }

        return if (state.isStructuredWorkout && state.totalRepeats > 0) {
            val segment = when (state.structuredWorkoutPhase) {
                StructuredWorkoutPhase.RUN -> "RUN"
                StructuredWorkoutPhase.WALK -> "WALK"
            }
            val remaining = formatTime(state.phaseTimeRemainingSeconds.coerceAtLeast(0).toLong())
            "Int ${state.currentRepeat}/${state.totalRepeats} • $segment • $remaining left"
        } else if (state.currentPhase == SessionPhase.MAIN && state.sessionType == SESSION_TYPE_EASY_FIXED_DURATION) {
            val remaining = formatTime(state.phaseSecondsRemaining.coerceAtLeast(0).toLong())
            "Easy session • $remaining left"
        } else if (state.currentPhase == SessionPhase.MAIN) {
            "Main elapsed ${formatTime(state.phaseSecondsElapsed.coerceAtLeast(0))}"
        } else {
            val remaining = formatTime(state.phaseSecondsRemaining.coerceAtLeast(0).toLong())
            "$phaseName • $remaining left"
        }
    }

    private fun resolveActiveWorkoutTemplate(): WorkoutTemplate? {
        val planId = currentSettings.activePlanId ?: return null
        val plan = TrainingPlanProvider.getPlanById(planId) ?: return null
        val stage = plan.stages.firstOrNull { it.id == currentSettings.activeStageId } ?: plan.stages.firstOrNull()
        val baseWorkout = stage?.workouts?.firstOrNull() ?: return null
        if (currentSettings.testingModeEnabled) return baseWorkout
        val run = currentSettings.aiRunIntervalSeconds
        val walk = currentSettings.aiWalkIntervalSeconds
        val repeats = currentSettings.aiRepeats
        return if (run != null && walk != null && repeats != null) {
            baseWorkout.copy(
                runDurationSeconds = run,
                walkDurationSeconds = walk,
                totalRepeats = repeats
            )
        } else {
            baseWorkout
        }
    }

    private fun initializeStructuredWorkoutState() {
        activeWorkoutTemplate = resolveActiveWorkoutTemplate()
        isStructuredWorkout = activeWorkoutTemplate != null && currentSessionType == SESSION_TYPE_RUN_WALK
        resetCurrentIntervalTransparencyState()
        structuredWorkoutPhase = if (currentSessionType == SESSION_TYPE_RUN_WALK) {
            StructuredWorkoutPhase.RUN
        } else {
            StructuredWorkoutPhase.WALK
        }
        phaseTimeRemainingSeconds = if (currentSessionType == SESSION_TYPE_RUN_WALK) {
            activeWorkoutTemplate?.runDurationSeconds ?: 0
        } else {
            0
        }
        currentRepeat = 1
        hasStructuredWorkoutStarted = false
        resetRunIntervalRecoveryCueState()
    }

    private fun onStructuredWorkoutPhaseComplete(workout: WorkoutTemplate) {
        if (currentSessionType != SESSION_TYPE_RUN_WALK) return

        if (structuredWorkoutPhase == StructuredWorkoutPhase.RUN) {
            finalizeActiveRunIntervalTracking()
            if (workout.walkDurationSeconds > 0) {
                currentWalkReasonState = if (hrCapExceededInCurrentIntervalState) "HR-triggered" else "Planned"
                structuredWorkoutPhase = StructuredWorkoutPhase.WALK
                phaseTimeRemainingSeconds = workout.walkDurationSeconds
                playCue("Transition to walking, ${workout.walkDurationSeconds} seconds.")
            } else {
                currentRepeat += 1
                if (currentRepeat > workout.totalRepeats) {
                    resetCurrentIntervalTransparencyState()
                    playCue("Main workout complete, beginning cool down.")
                    isStructuredWorkout = false
                    hasStructuredWorkoutStarted = false
                    phaseTimeRemainingSeconds = 0
                } else {
                    structuredWorkoutPhase = StructuredWorkoutPhase.RUN
                    phaseTimeRemainingSeconds = workout.runDurationSeconds
                    resetRunIntervalRecoveryCueState()
                    startRunIntervalTracking(
                        intervalIndex = currentRepeat,
                        plannedDurationSeconds = workout.runDurationSeconds
                    )
                    playCue("Start running, interval $currentRepeat of ${workout.totalRepeats}.")
                }
            }
        } else {
            currentRepeat += 1
            if (currentRepeat > workout.totalRepeats) {
                resetCurrentIntervalTransparencyState()
                playCue("Main workout complete, beginning cool down.")
                isStructuredWorkout = false
                hasStructuredWorkoutStarted = false
                phaseTimeRemainingSeconds = 0
            } else {
                structuredWorkoutPhase = StructuredWorkoutPhase.RUN
                phaseTimeRemainingSeconds = workout.runDurationSeconds
                resetRunIntervalRecoveryCueState()
                startRunIntervalTracking(
                    intervalIndex = currentRepeat,
                    plannedDurationSeconds = workout.runDurationSeconds
                )
                playCue("Start running, interval $currentRepeat of ${workout.totalRepeats}.")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Mission: Build persistent notification and call startForeground immediately
        startForegroundService()

        val explicitSessionType = intent?.getStringExtra(EXTRA_SESSION_TYPE)
        val legacySessionType = intent?.getStringExtra(LEGACY_EXTRA_SESSION_TYPE)
        val sessionTypeSource = when {
            !explicitSessionType.isNullOrBlank() -> "intent:$EXTRA_SESSION_TYPE"
            !legacySessionType.isNullOrBlank() -> "intent:$LEGACY_EXTRA_SESSION_TYPE"
            currentSettings.lastSessionType.isNotBlank() -> "settings:lastSessionType"
            else -> "service:currentSessionType"
        }
        currentSessionType = sanitizeSessionType(
            explicitSessionType
                ?: legacySessionType
                ?: currentSettings.lastSessionType
        )
        _hrState.update { it.copy(sessionType = currentSessionType) }
        Log.d(
            TAG,
            "Service start action=${intent?.action ?: "null"} sessionType=$currentSessionType source=$sessionTypeSource"
        )

        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                val overrideAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (!isSimulationEnabled) {
                    serviceScope.launch {
                        startHardwareSession(overrideAddress)
                    }
                } else {
                    Log.d(TAG, "ACTION_START_FOREGROUND received while simulation is active. Skipping hardware startup.")
                }
            }
            ACTION_STOP_FOREGROUND -> {
                stopSession()
            }
            ACTION_PAUSE_SESSION -> {
                pauseSession()
            }
            ACTION_RESUME_SESSION -> {
                resumeSession()
            }
            ACTION_FORCE_SCAN -> {
                Log.d(TAG, "ACTION_FORCE_SCAN received")
                startForegroundService()
                if (!isSimulationEnabled) {
                    logBleDecision("force_scan", "User requested a fresh scan; skipping saved-device reconnect")
                    startScanning()
                } else {
                    Log.d(TAG, "Ignoring Force Scan - Simulation Mode is active.")
                }
            }
            ACTION_SET_SIMULATION -> {
                val enabled = intent.getBooleanExtra(EXTRA_SIMULATION_ENABLED, isSimulationEnabled)
                setSimulationEnabled(enabled)
            }
        }
        return START_STICKY
    }
    
    fun togglePause() {
        if (_hrState.value.sessionStatus == SessionStatus.RUNNING) {
            pauseSession()
        } else if (_hrState.value.sessionStatus == SessionStatus.PAUSED) {
            resumeSession()
        }
    }

    private fun pauseSession() {
        isAutoPaused = false
        _hrState.update { it.copy(sessionStatus = SessionStatus.PAUSED) }
        locationTracker?.stop()
        updateNotification(forceUpdate = true)
        Log.d(TAG, "Session PAUSED")
    }

    private fun resumeSession() {
        if (currentSessionId == null) {
            startNewDatabaseSession()
        }
        // A manual resume always wins, including when the session is currently auto-paused (GPS
        // was never stopped in that case, so there's no stop()/discardLastFix() call to clear
        // SessionRecorder's own auto-pause flag - do it explicitly instead) (#39).
        isAutoPaused = false
        locationTracker?.clearAutoPauseState()
        _hrState.update { it.copy(sessionStatus = SessionStatus.RUNNING) }
        startSessionTimerLoop()
        locationTracker?.restartIfNeeded("resumeSession", currentSettings.runMode, isSimulationEnabled)
        updateNotification(forceUpdate = true)
        Log.d(TAG, "Session RESUMED")
    }

    /**
     * Called from [SessionRecorder]'s auto-pause callback (via [LocationTracker], off the main
     * thread - hence [serviceScope].launch at the call site) once a sustained standstill is
     * detected (#39). Reuses [SessionStatus.PAUSED] so [pulseSession]'s existing RUNNING/PAUSED
     * branching freezes the session clock and interval timers exactly like a manual pause, but -
     * unlike [pauseSession] - deliberately leaves GPS running so movement can still be detected.
     */
    private fun autoPauseSession() {
        if (_hrState.value.sessionStatus != SessionStatus.RUNNING) return
        isAutoPaused = true
        _hrState.update { it.copy(sessionStatus = SessionStatus.PAUSED) }
        updateNotification(forceUpdate = true)
        playCue("Auto-paused.")
        Log.d(TAG, "Session AUTO-PAUSED (standstill)")
    }

    /** Counterpart to [autoPauseSession] - fired once [SessionRecorder] detects movement again. */
    private fun autoResumeSession() {
        if (_hrState.value.sessionStatus != SessionStatus.PAUSED || !isAutoPaused) return
        isAutoPaused = false
        _hrState.update { it.copy(sessionStatus = SessionStatus.RUNNING) }
        startSessionTimerLoop()
        updateNotification(forceUpdate = true)
        playCue("Resuming.")
        Log.d(TAG, "Session AUTO-RESUMED (movement detected)")
    }

    private fun startNewDatabaseSession() {
        serviceScope.launch(Dispatchers.IO) {
            synchronized(sessionCreationLock) {
                if (isCreatingSession || currentSessionId != null) {
                    Log.d(TAG, "Skipping DB session start: creating=$isCreatingSession sessionId=$currentSessionId")
                    return@launch
                }
                isCreatingSession = true
            }

            try {
            // Mission: Reset Phase Engine for a fresh session
            currentPhase = SessionPhase.WARM_UP
            phaseSecondsRunning = 0
            isWarmupSkipped = false
            currentWarmupDuration = currentSettings.warmUpDurationSeconds
            initializeStructuredWorkoutState()
            resetRunIntervalTracking()
            currentSessionIncludeInAiTraining = currentSettings.aiDataSharingEnabled && !currentSettings.testingModeEnabled
            
            // Reset session-level counters only when a new database session begins
            sessionSecondsRunning = 0
            sessionSecondsPaused = 0
            isAutoPaused = false

            // Mission 4: Reset Location/Pace variables
            locationTracker?.resetSessionState()

            // Mission: Immediate UI State Reset 
            _hrState.update { it.copy(
                sessionType = currentSessionType,
                currentPhase = SessionPhase.WARM_UP,
                phaseSecondsRemaining = currentSettings.warmUpDurationSeconds,
                phaseSecondsElapsed = 0,
                isStructuredWorkout = isStructuredWorkout,
                structuredWorkoutPhase = structuredWorkoutPhase,
                phaseTimeRemainingSeconds = phaseTimeRemainingSeconds,
                currentRepeat = currentRepeat,
                totalRepeats = 0,
                currentIntervalPlannedSeconds = 0,
                nextIntervalType = null,
                nextIntervalDurationSeconds = 0,
                workoutProgressPercent = 0,
                currentIntervalElapsedSeconds = 0,
                currentWalkReason = "Planned",
                hrCapExceededInCurrentInterval = false,
                hrCapExceededAtSecond = null,
                secondsRunning = 0,
                secondsPaused = 0,
                distanceKm = 0.0,
                paceMinPerKm = 0.0,
                activeDbSessionId = null
            )}

            val session = RunnerSession(
                startTime = System.currentTimeMillis(),
                runMode = currentSettings.runMode,
                sessionType = currentSessionType,
                includeInAiTraining = currentSessionIncludeInAiTraining
            )
            currentSessionId = database.sessionDao().insertSession(session)
            _hrState.update { it.copy(activeDbSessionId = currentSessionId) }
            startSessionTimerLoop()
            sessionMaxBpm = 0
            sessionBpmSum = 0
            sessionSampleCount = 0
            baselineHr = null
            lastDriftCueTime = 0L
            sessionInTargetZoneSeconds = 0
            lastRecordedSecond = -1
            
            // Mission 3: Reset Zone Timers
            sessionZoneTimes.keys.forEach { sessionZoneTimes[it] = 0L }
            
            // Mission: Session Phases
            currentPhase = SessionPhase.WARM_UP
            phaseSecondsRunning = 0
            walkBreaksCount = 0
            isWarmupSkipped = false
            currentWarmupDuration = currentSettings.warmUpDurationSeconds
            initializeStructuredWorkoutState()
            
            Log.d(TAG, "Started DB Session: $currentSessionId (Mode: ${currentSettings.runMode})")
            } finally {
                synchronized(sessionCreationLock) {
                    isCreatingSession = false
                }
            }
        }
    }

    fun skipCurrentPhase() {
        when (currentPhase) {
            SessionPhase.WARM_UP -> {
                currentPhase = SessionPhase.MAIN
                phaseSecondsRunning = 0
                isWarmupSkipped = true
                Log.d(TAG, "Skip Warm-up: isWarmupSkipped set to true. Buffer should now be disabled.")
                playCue("Warm up skipped. Starting workout.")
            }
            SessionPhase.MAIN -> {
                finalizeActiveRunIntervalTracking()
                currentPhase = SessionPhase.COOL_DOWN
                phaseSecondsRunning = 0
                isStructuredWorkout = false
                hasStructuredWorkoutStarted = false
                resetCurrentIntervalTransparencyState()
                phaseTimeRemainingSeconds = 0
                playCue("Starting cool down.")
            }
            SessionPhase.COOL_DOWN -> {
                stopSession()
            }
        }

        // Mission: Immediate UI Sync
        _hrState.update { currentState ->
            val structuredProgress = buildStructuredProgressUiState()
                currentState.copy(
                    sessionType = currentSessionType,
                    currentPhase = currentPhase,
                phaseSecondsRemaining = when (currentPhase) {
                    SessionPhase.MAIN -> {
                        val mainLimit = getMainPhaseLimitSeconds()
                        if (mainLimit == Int.MAX_VALUE) 0 else (mainLimit - phaseSecondsRunning).toInt().coerceAtLeast(0)
                    }
                    else -> {
                        val limit = when (currentPhase) {
                            SessionPhase.WARM_UP -> currentSettings.warmUpDurationSeconds
                            SessionPhase.COOL_DOWN -> currentSettings.coolDownDurationSeconds
                            else -> 0
                        }
                        (limit - phaseSecondsRunning).toInt().coerceAtLeast(0)
                    }
                },
                phaseSecondsElapsed = phaseSecondsRunning,
                isStructuredWorkout = isStructuredWorkout,
                structuredWorkoutPhase = structuredWorkoutPhase,
                phaseTimeRemainingSeconds = phaseTimeRemainingSeconds.coerceAtLeast(0),
                currentRepeat = currentRepeat,
                totalRepeats = structuredProgress.totalRepeats,
                currentIntervalPlannedSeconds = structuredProgress.currentIntervalPlannedSeconds,
                nextIntervalType = structuredProgress.nextIntervalType,
                nextIntervalDurationSeconds = structuredProgress.nextIntervalDurationSeconds,
                workoutProgressPercent = structuredProgress.workoutProgressPercent,
                currentIntervalElapsedSeconds = structuredProgress.currentIntervalElapsedSeconds,
                currentWalkReason = currentWalkReasonState,
                hrCapExceededInCurrentInterval = hrCapExceededInCurrentIntervalState,
                hrCapExceededAtSecond = hrCapExceededAtSecondState
            )
        }
    }

    fun stopSession() {
        _hrState.update { it.copy(sessionStatus = SessionStatus.STOPPING) }
        stopScanning()
        
        // FIX: Capture final counters BEFORE disconnect() resets BLE state
        val finalSecondsRunning = sessionSecondsRunning
        val finalSecondsPaused = sessionSecondsPaused
        val finalDistanceKm = locationTracker?.getDistanceKm() ?: 0.0
        val finalAvgPace = locationTracker?.getPaceMinPerKm() ?: 0.0
        val finalStartLocation = locationTracker?.getFirstLocation()
        val finalWalkBreaksCount = walkBreaksCount
        val finalIsRunWalkMode = currentSettings.runWalkCoachEnabled
        finalizeActiveRunIntervalTracking()

        disconnect()
        locationTracker?.stop()
        
        // Finalize DB session
        val sessionId = currentSessionId
        if (sessionId != null) {
            serviceScope.launch(Dispatchers.IO) {
                // MISSION: Ensure DB update is not cancelled by service destruction
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val session = database.sessionDao().getSessionById(sessionId)
                    if (session != null) {
                        val avgBpm = if (sessionSampleCount > 0) (sessionBpmSum / sessionSampleCount).toInt() else 0
                        val easySummary = if (currentSessionType == SESSION_TYPE_EASY_FIXED_DURATION) {
                            buildEasyFixedDurationSummary(
                                sessionId = sessionId,
                                actualDurationSeconds = finalSecondsRunning.toInt(),
                                avgBpm = avgBpm,
                                maxBpm = sessionMaxBpm
                            )
                        } else {
                            null
                        }
                        val updatedSession = session.copy(
                            endTime = System.currentTimeMillis(),
                            durationSeconds = finalSecondsRunning,
                            avgBpm = avgBpm,
                            maxBpm = sessionMaxBpm,
                            timeInTargetZoneSeconds = sessionInTargetZoneSeconds,
                            distanceKm = finalDistanceKm,
                            avgPaceMinPerKm = finalAvgPace,
                            startLatitude = finalStartLocation?.latitude,
                            startLongitude = finalStartLocation?.longitude,
                            zone1Seconds = sessionZoneTimes[1] ?: 0L,
                            zone2Seconds = sessionZoneTimes[2] ?: 0L,
                            zone3Seconds = sessionZoneTimes[3] ?: 0L,
                            zone4Seconds = sessionZoneTimes[4] ?: 0L,
                            zone5Seconds = sessionZoneTimes[5] ?: 0L,
                            noDataSeconds = sessionNoDataSeconds,
                            walkBreaksCount = finalWalkBreaksCount,
                            isRunWalkMode = finalIsRunWalkMode,
                            sessionType = currentSessionType,
                            easyPlannedDurationSeconds = easySummary?.plannedDurationSeconds,
                            easyActualDurationSeconds = easySummary?.actualDurationSeconds,
                            easyTotalJogSeconds = easySummary?.totalJogSeconds,
                            easyTotalWalkSeconds = easySummary?.totalWalkSeconds,
                            easyJogPercent = easySummary?.jogPercent,
                            easyLongestJogBoutSeconds = easySummary?.longestJogBoutSeconds,
                            easyWalkInterruptions = easySummary?.walkInterruptions,
                            easyHrSummary = easySummary?.hrSummary,
                            easyTimeAboveCapSeconds = easySummary?.timeAboveEasyCapSeconds,
                            easyDataQualitySummary = easySummary?.dataQualitySummary
                        )
                        database.sessionDao().updateSession(updatedSession)
                        Log.d(TAG, "Finalized DB Session: $sessionId. Evidence: duration=${updatedSession.durationSeconds}")
                        persistRunIntervalStats(sessionId)

                        // Weather snapshot: fire-and-forget on weatherFetchScope, which is not
                        // cancelled by onDestroy(), so stopping from the background can't skip
                        // it. Not awaited, so a slow/unreachable weather service can't delay
                        // currentSessionId being cleared below. Missed fetches are retried at
                        // next launch.
                        val startLatitude = updatedSession.startLatitude
                        val startLongitude = updatedSession.startLongitude
                        if (updatedSession.runMode == "outdoor" && startLatitude != null && startLongitude != null) {
                            weatherFetchScope.launch {
                                sessionRepository.fetchAndSaveWeather(
                                    sessionId = sessionId,
                                    latitude = startLatitude,
                                    longitude = startLongitude,
                                    atEpochMillis = updatedSession.startTime
                                )
                            }
                        }

                        val stageId = currentSettings.activeStageId
                        if (stageId != null &&
                            currentSessionType == SESSION_TYPE_RUN_WALK &&
                            updatedSession.includeInAiTraining &&
                            !currentSettings.testingModeEnabled
                        ) {
                            Log.d("AiCoach", "Triggering AI evaluation after session finalization for stage: $stageId")
                            sessionRepository.evaluateAndAdjustPlan(stageId)
                        } else if (stageId != null &&
                            currentSessionType == SESSION_TYPE_RUN_WALK &&
                            (!updatedSession.includeInAiTraining || currentSettings.testingModeEnabled)
                        ) {
                            Log.d(
                                "AiCoach",
                                "Skipping AI evaluation: session opted out or testing mode enabled for stage=$stageId"
                            )
                        }
                    } else {
                        resetRunIntervalTracking()
                    }
                    currentSessionId = null
                    currentSessionIncludeInAiTraining = true
                }
            }
        } else {
            resetRunIntervalTracking()
            currentSessionIncludeInAiTraining = true
        }

        audioCueManager?.releaseForSessionStop()
        _hrState.update { it.copy(sessionStatus = SessionStatus.STOPPED) }
        stopForegroundService()
    }
    
    override fun onInit(status: Int) {
        audioCueManager?.onTtsInit(status)
    }
    
    fun playCue(text: String) {
        if (currentSessionType == SESSION_TYPE_FREE_TRACK) return
        audioCueManager?.playCue(text)
    }


    private var lastNotificationTime = 0L
    private val NOTIFICATION_THROTTLE_MS = 10_000L // 10 seconds in background
    
    private fun updateNotification(forceUpdate: Boolean = false, overrideText: String? = null) {
        val now = System.currentTimeMillis()
        val isBackground = !isActivityBound
        
        // Critical State Detection
        val currentState = _hrState.value
        val currentZone = calculateZone(currentState.bpm, currentSettings)
        val zoneChanged = currentZone != lastNotificationZone
        val phaseChanged = currentPhase != lastNotificationPhase
        
        val isCritical = forceUpdate || zoneChanged || phaseChanged
        
        if (!isCritical && isBackground && (now - lastNotificationTime < NOTIFICATION_THROTTLE_MS)) {
            // Skip non-critical update while in background to save system resources
            return
        }
        
        lastNotificationTime = now
        lastNotificationZone = currentZone
        lastNotificationPhase = currentPhase
        
        val defaultContent = buildNotificationContent(currentState)
        val notification = createNotification(overrideText ?: defaultContent)
        
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundService() {
        val notification = createNotification("Service is running...")
        
        // Mission: Specify foreground service types for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        acquireWakeLock()
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RunningApp::SessionWakeLock")
            wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hours max
            Log.d(TAG, "WakeLock acquired")
        }
    }
    
    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null
            Log.d(TAG, "WakeLock released")
        }
    }

    private fun stopForegroundService() {
        Log.d(TAG, "stopForegroundService called - Kill Switch")
        stopScanning() 
        disconnect() 
        releaseWakeLock()
        
        // Mission: Stop the zombie timer loop immediately
        sessionHandler?.removeCallbacks(sessionTimerRunnable)
        
        // Mission: Explicit Kill Switch - ensure notification vanishes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }
    
    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, HrForegroundService::class.java).apply {
            action = ACTION_STOP_FOREGROUND
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val activityIntent = Intent(this, MainActivity::class.java)
        val activityPendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HR Monitor")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(activityPendingIntent)

        when (_hrState.value.sessionStatus) {
            SessionStatus.RUNNING -> {
                val pauseIntent = Intent(this, HrForegroundService::class.java).apply {
                    action = ACTION_PAUSE_SESSION
                }
                val pausePendingIntent = PendingIntent.getService(
                    this, 2, pauseIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent)
            }
            SessionStatus.PAUSED -> {
                val resumeIntent = Intent(this, HrForegroundService::class.java).apply {
                    action = ACTION_RESUME_SESSION
                }
                val resumePendingIntent = PendingIntent.getService(
                    this, 3, resumeIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(android.R.drawable.ic_media_play, "Resume", resumePendingIntent)
            }
            else -> Unit
        }

        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Workout", stopPendingIntent)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "HR Monitor Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    // --- BLE Logic ---

    fun startScanning() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            _hrState.update { it.copy(connectionStatus = "Permission Missing") }
            return
        }
        
        // Allow scanning even if connecting/reconnecting, but NOT if already connected
        if (_hrState.value.connectionStatus == "Connected") {
            Log.d(TAG, "startScanning() - Already connected, ignoring")
            return
        }

        Log.d(TAG, "startScanning() - Resetting connection state and starting fresh scan")
        logBleDecision("scan_reset", "Clearing reconnect target and scanned device list before scan")
        
        // ABORT any current connection attempts or reconnect loops
        isReconnecting = false
        targetDeviceAddress = null
        bluetoothGatt?.close()
        bluetoothGatt = null

        _hrState.update { it.copy(connectionStatus = "Scanning...", scannedDevices = emptyList()) }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "startScanning() - Bluetooth scanner unavailable!")
            _hrState.update { it.copy(connectionStatus = "Bluetooth Off/Unavailable") }
            return
        }

        try {
            // Some devices need a stop before a start or it fails silently
            scanner.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Stop scan failed during reset: ${e.message}")
        }
        
        scanner.startScan(scanCallback)
        Log.d(TAG, "startScanning() - BLE scan started")
    }

    private val scanCallback = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
            result?.device?.let { device ->
                if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Scan result: name=${device.name ?: "<unnamed>"} address=${device.address}")
                }
                _hrState.update { currentState ->
                    val currentList = currentState.scannedDevices
                    if (currentList.none { it.address == device.address }) {
                        if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            if (device.name != null) {
                                currentState.copy(scannedDevices = currentList + device)
                            } else {
                                currentState
                            }
                        } else {
                            currentState
                        }
                    } else {
                        currentState
                    }
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with errorCode=$errorCode")
            _hrState.update { it.copy(connectionStatus = "Scan Failed: $errorCode") }
        }
    }
    
    fun connectToDevice(address: String) {
        logBleDecision("connect_by_address", "Preparing direct connection to address=$address")
        stopScanning()
        
        // Step 3: Deep Cleanup / State Sanitization
        reconnectAttemptCount = 0
        reconnectDelay = 3000L
        
        targetDeviceAddress = address
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        connectToDevice(device)
    }
    
    private fun stopScanning() {
         if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
             bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
             Log.d(TAG, "stopScanning() - stopScan invoked")
         }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

        isReconnecting = false
        val deviceName = if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            device.name ?: device.address
        } else {
            device.address
        }
        _hrState.update { it.copy(connectionStatus = "Connecting to $deviceName...") }
        
        // Mission: Robust Bluetooth - Close old connection and connect on IO
        serviceScope.launch(Dispatchers.IO) {
            bluetoothGatt?.close()
            bluetoothGatt = null
            
            Log.d(TAG, "Connecting to GATT on IO thread...")
            bluetoothGatt = device.connectGatt(this@HrForegroundService, false, gattCallback)
        }
    }
    
    private fun attemptReconnect() {
         if (targetDeviceAddress == null) return
         
         isReconnecting = true
         val delayMs = reconnectDelay
         
         reconnectAttemptCount++
         _hrState.update { it.copy(
             connectionStatus = "Reconnecting in ${delayMs/1000}s...",
             reconnectAttempts = reconnectAttemptCount
         ) }
         
         serviceScope.launch {
             delay(delayMs)
             reconnectDelay = (reconnectDelay * 2).coerceAtMost(30000L)
             if (targetDeviceAddress != null) {
                 connectToDevice(targetDeviceAddress!!)
             }
         }
    }

    fun disconnect() {
        targetDeviceAddress = null 
        serviceScope.launch(Dispatchers.IO) {
            if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }
        _hrState.update { it.copy(
            connectionStatus = "Disconnected", 
            sessionStatus = SessionStatus.STOPPED,
            sessionType = currentSessionType,
            bpm = 0, 
            connectedDeviceName = null, 
            discoveredServices = emptyList(),
            isStructuredWorkout = false,
            phaseSecondsElapsed = 0,
            phaseTimeRemainingSeconds = 0,
            totalRepeats = 0,
            currentIntervalPlannedSeconds = 0,
            nextIntervalType = null,
            nextIntervalDurationSeconds = 0,
            workoutProgressPercent = 0,
            currentIntervalElapsedSeconds = 0,
            currentWalkReason = "Planned",
            hrCapExceededInCurrentInterval = false,
            hrCapExceededAtSecond = null
        ) }
        synchronized(bpmHistory) {
            bpmHistory.clear()
        }
        currentZone = Zone.UNKNOWN
        isStructuredWorkout = false
        hasStructuredWorkoutStarted = false
        phaseTimeRemainingSeconds = 0
        currentRepeat = 1
        resetCurrentIntervalTransparencyState()
        activeWorkoutTemplate = null
        
        // Counters are now reset in startNewDatabaseSession() to persist until stopSession() finishes
        reconnectAttemptCount = 0
        firstDisconnectTime = 0
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectDelay = 3000L 
                isReconnecting = false
                reconnectAttemptCount = 0
                firstDisconnectTime = 0
                
                val deviceName = gatt?.device?.name ?: "Unknown"
                val deviceAddress = gatt?.device?.address ?: ""
                
                _hrState.update { it.copy(
                    connectionStatus = "Connected", 
                    sessionStatus = SessionStatus.RUNNING,
                    connectedDeviceName = deviceName,
                    reconnectAttempts = 0,
                    errorMessage = null
                ) }

                // FIX: Ensure a database session exists immediately upon connection
                if (currentSessionId == null) {
                    startNewDatabaseSession()
                }

                // Mission 4 FIX: Ensure location updates start if in outdoor mode
                if (currentSettings.runMode == "outdoor") {
                    locationTracker?.restartIfNeeded("session_start", currentSettings.runMode, isSimulationEnabled)
                }
                
                gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (targetDeviceAddress != null) {
                    // Unexpected disconnect while RUNNING or PAUSED
                    if (firstDisconnectTime == 0L) {
                        firstDisconnectTime = System.currentTimeMillis()
                    }
                    
                    _hrState.update { it.copy(
                        connectionStatus = "Disconnected (Retrying)",
                        sessionStatus = if (it.sessionStatus == SessionStatus.RUNNING) SessionStatus.CONNECTING else it.sessionStatus
                    ) }
                    
                    serviceScope.launch(Dispatchers.IO) {
                        gatt?.close()
                        if (bluetoothGatt == gatt) bluetoothGatt = null
                        attemptReconnect()
                    }
                } else {
                     _hrState.update { it.copy(
                         connectionStatus = "Disconnected",
                         sessionStatus = SessionStatus.STOPPED
                     ) }
                     serviceScope.launch(Dispatchers.IO) {
                        gatt?.close()
                        if (bluetoothGatt == gatt) bluetoothGatt = null
                     }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val servicesList = mutableListOf<String>()
                gatt?.services?.forEach { service ->
                    servicesList.add(service.uuid.toString())
                }
                _hrState.update { it.copy(discoveredServices = servicesList) }

                val service = gatt?.getService(HEART_RATE_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)
                
                if (characteristic != null) {
                    // Mission: Post-Connection Persistence - Save as active ONLY when HR service is verified
                    gatt?.device?.let { device ->
                        val deviceName = if (ActivityCompat.checkSelfPermission(this@HrForegroundService, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            device.name ?: "Unknown"
                        } else "Unknown"
                        val deviceAddress = device.address
                        serviceScope.launch {
                            settingsRepository.saveDevice(deviceAddress, deviceName)
                        }
                    }

                    if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                handleHeartRate(value)
            }
        }
    }

    private fun handleHeartRate(data: ByteArray) {
        if (isSimulationEnabled) return // Mission 3: Ignore real data during simulation
        if (data.isEmpty()) return
        val flag = data[0].toInt()
        val is16Bit = (flag and 0x01) != 0
        var bpm = 0
        if (is16Bit) {
             if (data.size >= 3) {
                 bpm = ((data[2].toInt() and 0xFF) shl 8) + (data[1].toInt() and 0xFF)
             }
        } else {
             if (data.size >= 2) {
                 bpm = data[1].toInt() and 0xFF
             }
        }
        
        val timestamp = System.currentTimeMillis()
        lastHrTimestamp = timestamp // Track for session engine age
        
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val formattedTime = sdf.format(Date(timestamp))
        val formatString = if (is16Bit) "16-bit (UINT16)" else "8-bit (UINT8)"
        
        // --- PROCESSS COACHING RULES (Session must be RUNNING) ---
        if (_hrState.value.sessionStatus == SessionStatus.RUNNING) {
            processCoachingRules(bpm, timestamp)
        }
        
        val debugInfo = getCoachingDebugInfo(timestamp)

        _hrState.update { currentState ->
            currentState.copy(
                bpm = bpm, 
                lastUpdateTimestamp = timestamp,
                lastPacketTimeFormatted = formattedTime,
                dataBits = formatString,
                avgBpm = debugInfo.avg,
                currentZone = debugInfo.zone,
                timeInZoneString = debugInfo.timeInZone,
                cooldownWithHysteresisString = debugInfo.cooldown
            ) 
        }
    }
    
    private fun processCoachingRules(bpm: Int, now: Long) {
        // MISSION: Block coaching cues outside WARM_UP and MAIN phase
        if (currentPhase != SessionPhase.MAIN && currentPhase != SessionPhase.WARM_UP) {
            _hrState.update { it.copy(currentZone = "NONE", timeInZoneString = "N/A") }
            return
        }

        if (!currentSettings.coachingEnabled) return

        var avgBpm = 0
        synchronized(bpmHistory) {
            bpmHistory.add(Pair(now, bpm))
            while (bpmHistory.isNotEmpty() && (now - bpmHistory.first.first > HISTORY_WINDOW_MS)) {
                bpmHistory.removeFirst()
            }
            if (bpmHistory.isEmpty()) return
            avgBpm = bpmHistory.map { it.second }.average().roundToInt()
        }
        
        val isRunWalk = currentSettings.runWalkCoachEnabled && currentSessionType == SESSION_TYPE_RUN_WALK
        
        val newZone = when {
            isRunWalk -> {
                // MISSION: Wider Hysteresis for Run/Walk
                // Recover as soon as HR drops to the MIDPOINT of the target zone
                val recoveryPoint = currentSettings.zone2Low + ((currentSettings.zone2High - currentSettings.zone2Low) / 2)
                if (currentZone == Zone.LOW) {
                    if (avgBpm >= recoveryPoint) Zone.TARGET else Zone.LOW
                } else if (avgBpm < currentSettings.zone2Low) {
                    Zone.LOW
                } else if (avgBpm > currentSettings.zone2High) {
                    Zone.HIGH
                } else {
                    Zone.TARGET
                }
            }
            avgBpm < currentSettings.zone2Low -> Zone.LOW
            avgBpm > currentSettings.zone2High -> Zone.HIGH
            else -> Zone.TARGET
        }
        
        if (newZone != currentZone) {
            currentZone = newZone
            zoneEnterTime = now
        }
        
        val timeInCurrentZone = now - zoneEnterTime
        val cooldownMs = currentSettings.cooldownSeconds * 1000L
        val cooldownRemaining = (lastCueTime + cooldownMs) - now
        
        val isBufferActive = sessionSecondsRunning < currentWarmupDuration && !isWarmupSkipped
        val criticalThreshold = currentSettings.zone2High + 15
        val isRunWalkRecoveryScope = isRunWalk &&
            currentPhase == SessionPhase.MAIN &&
            isStructuredWorkout &&
            structuredWorkoutPhase == StructuredWorkoutPhase.RUN
        val recoveryThresholdReached = avgBpm <= currentSettings.zone2Low

        if (isRunWalkRecoveryScope && wasInHighZoneSinceRunIntervalStart && recoveryThresholdReached) {
            if (recoveryCueEligibleSinceMs == 0L) {
                recoveryCueEligibleSinceMs = now
                Log.d(
                    TAG,
                    "Recovery threshold reached in RUN interval. avgBpm=$avgBpm threshold=${currentSettings.zone2Low}"
                )
            }
            lastRecoveryCueBpm = avgBpm
        } else {
            if (recoveryCueEligibleSinceMs != 0L && isRunWalk) {
                val reason = when {
                    !isRunWalkRecoveryScope -> "scope_inactive"
                    !wasInHighZoneSinceRunIntervalStart -> "no_prior_high_trigger"
                    !recoveryThresholdReached -> "above_recovery_threshold"
                    else -> "reset"
                }
                Log.d(TAG, "Recovery eligibility reset: reason=$reason avgBpm=$avgBpm")
            }
            recoveryCueEligibleSinceMs = 0L
        }

        if (cooldownRemaining <= 0) {
             val persistenceHighMs = currentSettings.persistenceHighSeconds * 1000L
             val persistenceLowMs = currentSettings.persistenceLowSeconds * 1000L

             if (currentZone == Zone.HIGH && timeInCurrentZone >= persistenceHighMs) {
                 // MISSION: Cardiac Drift Detection - > 20m, <= baseline + 12
                 val isDrifting = sessionSecondsRunning > 1200 && 
                                 baselineHr != null && 
                                 avgBpm <= (baselineHr!! + 12)

                 if (isDrifting && avgBpm > currentSettings.zone2High) {
                    val driftCooldownMs = 300_000L // 5 mins
                    if (now - lastDriftCueTime >= driftCooldownMs) {
                        if (isRunWalkRecoveryScope) wasInHighZoneSinceRunIntervalStart = true
                        playCue("Heart rate drifting up. Keep effort steady, or take a short walk break.")
                        lastDriftCueTime = now
                        lastCueTime = now
                        if (isRunWalk) recordRunWalkHighHrTriggerEvent(avgBpm)
                        Log.d(TAG, "Drift Cue Played (Time: ${sessionSecondsRunning}s, Avg: $avgBpm, Base: $baselineHr)")
                    } else {
                        Log.d(TAG, "Drift detected but suppressed by anti-nag cooldown")
                    }
                 } else if (baselineHr != null && avgBpm > (baselineHr!! + 12)) {
                     // Danger cue if significantly above baseline (usually after 10m)
                     val text = if (isRunWalk) "Heart rate high. Walk until your breathing settles." else {
                         if (currentSettings.voiceStyle == "short") "Ease off" else "Ease off slightly."
                     }
                     if (isRunWalkRecoveryScope) wasInHighZoneSinceRunIntervalStart = true
                     playCue(text)
                     lastCueTime = now
                     Log.d(TAG, "HR above drift ceiling! Playing danger cue. Avg: $avgBpm, Ceiling: ${baselineHr!! + 12}")
                     if (isRunWalk) {
                         walkBreaksCount++
                         recordRunWalkHighHrTriggerEvent(avgBpm)
                     }
                 } else if (avgBpm > criticalThreshold) {
                     // MISSION: Safety Override - Play cue regardless of buffer if HR is in danger zone
                     val text = if (isRunWalk) "Heart rate high. Walk until your breathing settles." else {
                         if (currentSettings.voiceStyle == "short") "Ease off" else "Ease off slightly."
                     }
                     if (isRunWalkRecoveryScope) wasInHighZoneSinceRunIntervalStart = true
                     playCue(text)
                     lastCueTime = now
                     Log.d(TAG, "Safety Override Triggered! HR: $avgBpm > Limit: $criticalThreshold")
                     if (isRunWalk) {
                         walkBreaksCount++
                         recordRunWalkHighHrTriggerEvent(avgBpm)
                     }
                 } else if (isBufferActive) {
                     // MISSION: Total Silence during Warm-up Buffer
                     Log.d(TAG, "Warm-up Buffer Active: Muting High HR cue (Time: ${sessionSecondsRunning}s, Avg: $avgBpm)")
                 } else {
                     // Normal HIGH cue
                     val text = if (isRunWalk) "Heart rate high. Walk until your breathing settles." else {
                         if (currentSettings.voiceStyle == "short") "Ease off" else "Ease off slightly."
                     }
                     if (isRunWalkRecoveryScope) wasInHighZoneSinceRunIntervalStart = true
                     playCue(text)
                     lastCueTime = now
                     if (isRunWalk) {
                         walkBreaksCount++
                         recordRunWalkHighHrTriggerEvent(avgBpm)
                     }
                 }
             } else if (
                 isRunWalkRecoveryScope &&
                 wasInHighZoneSinceRunIntervalStart &&
                 recoveryCueEligibleSinceMs != 0L &&
                 (now - recoveryCueEligibleSinceMs) >= persistenceLowMs
             ) {
                 if (isBufferActive) {
                     Log.d(TAG, "Warm-up Buffer Active: Muting recovery cue (Time: ${sessionSecondsRunning}s)")
                 } else {
                     val text = "Heart rate recovered. Transition to a light jog."
                     playCue(text)
                     lastCueTime = now
                     recordRunWalkRecoveryCueEvent()
                     Log.d(
                         TAG,
                         "Recovery cue fired in RUN interval. avgBpm=$lastRecoveryCueBpm threshold=${currentSettings.zone2Low}"
                     )
                 }
             } else if (!isRunWalk && currentZone == Zone.LOW && timeInCurrentZone >= persistenceLowMs) {
                 // MISSION: Total Silence during Warm-up Buffer for LOW cues too
                 if (isBufferActive) {
                     Log.d(TAG, "Warm-up Buffer Active: Muting Low HR cue (Time: ${sessionSecondsRunning}s)")
                 } else {
                     val text = if (currentSettings.voiceStyle == "short") "Faster" else "Gently increase pace."
                     playCue(text)
                     lastCueTime = now
                 }
             }
        } else if (isRunWalkRecoveryScope && recoveryCueEligibleSinceMs != 0L) {
            Log.d(TAG, "Recovery cue waiting for cooldown: remainingMs=$cooldownRemaining")
        }
    }
    
    private data class DebugInfo(val avg: Int, val zone: String, val timeInZone: String, val cooldown: String)
    
    private fun getCoachingDebugInfo(now: Long): DebugInfo {
        var avg = 0
        synchronized(bpmHistory) {
            if (bpmHistory.isEmpty()) return DebugInfo(0, "Init", "0s", "Ready")
            if (!currentSettings.coachingEnabled) return DebugInfo(bpmHistory.last().second, "Disabled", "--", "Off")

            avg = bpmHistory.map { it.second }.average().roundToInt()
        }

        val zoneStr = currentZone.name
        val timeInZone = (now - zoneEnterTime) / 1000
        val cooldownMs = currentSettings.cooldownSeconds * 1000L
        val cooldownRem = ((lastCueTime + cooldownMs) - now).coerceAtLeast(0) / 1000
        val statusStr = if (cooldownRem > 0) "Cool: ${cooldownRem}s" else "Ready"
        return DebugInfo(avg, zoneStr, "${timeInZone}s", statusStr)
    }

    private fun calculateZone(bpm: Int, settings: UserSettings): Int {
        val maxHr = settings.maxHr
        if (maxHr <= 0 || bpm <= 0) return 0
        
        // 1. Zone 2 is defined by user settings (Target)
        if (bpm >= settings.zone2Low && bpm <= settings.zone2High) return 2
        
        // 2. Derive other zones relative to Zone 2 and Max HR
        val percent = (bpm.toFloat() / maxHr * 100).toInt()
        
        return when {
            bpm < settings.zone2Low -> 1
            bpm > settings.zone2High && percent < 80 -> 3
            percent < 90 -> 4
            else -> 5
        }
    }

    private fun updateSimulationData() {
        // Simple sawtooth simulation to sweep through zones
        simulationBpm += (5 * simulationDirection)
        if (simulationBpm >= currentSettings.maxHr + 10) simulationDirection = -1
        if (simulationBpm <= 60) simulationDirection = 1
        
        handleHeartRateForSimulation(simulationBpm)
    }

    private fun handleHeartRateForSimulation(bpm: Int) {
        val timestamp = System.currentTimeMillis()
        lastHrTimestamp = timestamp
        
        // Use a simpler version of handleHeartRate for simulated data
        if (_hrState.value.sessionStatus == SessionStatus.RUNNING) {
            processCoachingRules(bpm, timestamp)
        }
        
        val debugInfo = getCoachingDebugInfo(timestamp)
        _hrState.update { it.copy(
            bpm = bpm,
            lastUpdateTimestamp = timestamp,
            lastPacketTimeFormatted = "SIMULATED",
            dataBits = "Simulation Mode",
            avgBpm = debugInfo.avg,
            currentZone = debugInfo.zone,
            timeInZoneString = debugInfo.timeInZone,
            cooldownWithHysteresisString = debugInfo.cooldown
        ) }
    }

    fun setSimulationEnabled(enabled: Boolean, sessionType: String? = null) {
        if (sessionType != null && currentSessionId == null) {
            currentSessionType = sanitizeSessionType(sessionType)
            _hrState.update { it.copy(sessionType = currentSessionType) }
        }
        if (isSimulationEnabled == enabled) {
            _hrState.update { it.copy(isSimulating = isSimulationEnabled) }
            Log.d(
                TAG,
                "Simulation unchanged: enabled=$isSimulationEnabled sessionId=$currentSessionId status=${_hrState.value.sessionStatus} phase=$currentPhase running=${sessionSecondsRunning}s"
            )
            return
        }

        isSimulationEnabled = enabled
        _hrState.update { it.copy(isSimulating = isSimulationEnabled) }
        
        if (isSimulationEnabled) {
            // Ensure simulation sessions remain visible/controllable from lock screen.
            startForegroundService()
            val status = _hrState.value.sessionStatus
            if (currentSessionId == null && (status == SessionStatus.IDLE || status == SessionStatus.STOPPED)) {
                startNewDatabaseSession()
                _hrState.update { it.copy(sessionStatus = SessionStatus.RUNNING) }
            } else if (status == SessionStatus.RUNNING) {
                startSessionTimerLoop()
            }
        } else {
            Log.d(TAG, "Simulation Mode DISABLED")
        }
        Log.d(
            TAG,
            "Simulation toggled: enabled=$isSimulationEnabled sessionId=$currentSessionId status=${_hrState.value.sessionStatus} phase=$currentPhase running=${sessionSecondsRunning}s"
        )
    }

    fun toggleSimulation() {
        setSimulationEnabled(!isSimulationEnabled)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called - Clean Exit")
        
        // 1. Clean up Bluetooth precisely
        stopScanning()
        targetDeviceAddress = null
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
        
        // 2. Kill all background loops and threads
        serviceScope.cancel() 
        sessionHandler?.removeCallbacks(sessionTimerRunnable)
        sessionHandlerThread?.quitSafely()
        sessionHandlerThread = null
        
        locationTracker?.shutdown()
        
        releaseWakeLock()
        audioCueManager?.shutdown()
        Log.d(TAG, "Service destroyed")
    }
}
