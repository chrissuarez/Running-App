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
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import com.example.runningapp.data.DatabaseBackupManager
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.HrSample
import com.example.runningapp.data.RunWalkIntervalStat
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
import com.example.runningapp.foreground.ForegroundPromotion
import com.example.runningapp.foreground.PromotionHost
import com.example.runningapp.foreground.isAcquiringStrap
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

    // The target frozen for the active run (null when idle). The live screen prefers this over
    // userSettings.targetHrZone so its zone label and band match what the coach is actually
    // coaching, even if the global target is changed mid-run.
    val activeTargetZone: HrZone? = null,

    val walkBreaksCount: Int = 0,

    // Post-run "How did that feel?" sheet: DB row id for the session the UI should prompt about
    val activeDbSessionId: Long? = null,

    // The live run's pinned mode ("outdoor"/"treadmill"), published with RUNNING and cleared at
    // stop. Active-run UI must gate distance/map on this, not userSettings.runMode: the settings
    // write from a just-tapped mode toggle is async, so an outdoor run started immediately after
    // the tap would otherwise render as a treadmill run while GPS records underneath.
    val activeRunMode: String? = null
) {
    /**
     * Is an Acquisition in flight — scanning, connecting, or retrying a Strap?
     *
     * Derived, so it cannot go stale, and named once so the three things that need it (the START
     * guard, the record screen's spinner, and Promotion) cannot drift apart. They already had:
     * the service's copy of this test omitted "Scanning".
     */
    val acquiringStrap: Boolean get() = isAcquiringStrap(connectionStatus)
}

class HrForegroundService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Detached from serviceScope on purpose: the save-time weather fetch must survive
    // onDestroy() cancelling serviceScope when a run is stopped from the background.
    private val weatherFetchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // The run's per-sample writes (HR samples, GPS track points) live on their own scope so
    // stopSession() can wait for exactly these inserts to land before snapshotting the DB to
    // Downloads — otherwise the backup can race the tail writes and capture a run missing its
    // final seconds. Like weatherFetchScope, it survives onDestroy() so a background stop still
    // flushes the tail before the snapshot.
    private val recorderWriteScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
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
    // Volatile: written on the main thread (connect/forget/scan paths) and read by GATT
    // callbacks on binder threads to reject stale discoveries.
    @Volatile private var targetDeviceAddress: String? = null
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
    private var lastNotificationZone: HrZone? = null
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
    // Skip today's plan (#107): a per-run, today-only choice from the record screen. When set, the
    // run attaches no workout — an open-ended run with no warm-up/cool-down/intervals. It never
    // edits the plan, so tomorrow the plan is queued again.
    @Volatile private var skipPlanForToday: Boolean = false
    // Whether this run followed a structured plan workout, captured at start. Drives the recorded
    // RunnerSession.isRunWalkMode and whether the AI coach evaluates the run afterward.
    @Volatile private var sessionWasStructured: Boolean = false

    private lateinit var database: AppDatabase
    private var currentSessionId: Long? = null
    private var sessionMaxBpm = 0
    private var sessionBpmSum = 0L
    private var sessionNoDataSeconds = 0L
    private var sessionSampleCount = 0
    private var sessionAboveTargetSeconds = 0L
    private var lastRecordedSecond = -1L
    // The target this run is coached against, frozen at the start. The coach and the recorded
    // RunnerSession.targetZone both read this, so a mid-run change to the global target zone can
    // neither redirect the live coaching nor make "In Target" disagree with what was coached; it
    // takes effect on the next run. currentSettings still updates live for everything else.
    private var activeTargetZone: HrZone = HrZone.DEFAULT_TARGET
    
    // Mission 3: In-Memory Zone Tracking
    private val sessionZoneTimes = mutableMapOf(1 to 0L, 2 to 0L, 3 to 0L, 4 to 0L, 5 to 0L)
    private var isSimulationEnabled = false
    private var simulationBpm = 70
    private var simulationDirection = 1

    // --- Coaching Rules Engine State ---
    private val HISTORY_WINDOW_MS = 5000L
    
    // Pair<Timestamp, Bpm>
    private val bpmHistory = LinkedList<Pair<Long, Int>>()
    
    // Below/on/above target comes from HrZones.kt — the coach and the live UI cannot disagree.
    private var currentZone = ZoneBand.UNKNOWN
    private var baselineHr: Int? = null
    // The one clock for spoken zone cues (#108): silent until 30s out of target, then 30s / 60s /
    // every 5 min. Re-entry (judged at the zone midpoint) resets it to the top.
    private val cueLadder = CueLadder()
    // An unplanned run stays silent for this long before the ladder can speak — the fact that a
    // plan's warm-up step used to provide, hardcoded for runs that have no steps (#108).
    private val UNPLANNED_GRACE_SECONDS = 300L
    
    // --- Session Engine State ---
    @Volatile private var sessionSecondsRunning = 0L
    private var sessionSecondsPaused = 0L
    private var reconnectAttemptCount = 0
    private var lastHrTimestamp = 0L
    // The run mode this session actually started with (from EXTRA_RUN_MODE / effectiveRunMode). In-run
    // decisions that depend on mode — starting GPS on a (re)connect or resume — must read this, not
    // the live currentSettings.runMode, which can still hold a pre-START value during the async
    // settings write or be changed mid-run. Null when no run is active. @Volatile because the GATT
    // connect callback reads it from a Binder thread while START/session-setup write it.
    @Volatile
    private var activeSessionRunMode: String? = null
    private var firstDisconnectTime = 0L
    private val RECONNECT_TIMEOUT_MS = 120_000L // 2 minutes
    // With no run active, stop chasing an unreachable strap after this many attempts and land on
    // the terminal "Strap not found" state (each attempt already costs a ~30s connectGatt timeout
    // plus backoff). Mid-run reconnects are uncapped by design (#110).
    private val PRE_RUN_RECONNECT_MAX_ATTEMPTS = 3
    // A discovery scan with no user selection stops itself after this long (nothing ever
    // auto-connects from a scan, so an abandoned one would otherwise run forever).
    private val SCAN_TIMEOUT_MS = 60_000L
    // Bumped on every startScanning()/stopScanning(); lets the scan-timeout coroutine tell
    // whether ITS scan is still the live one.
    @Volatile private var scanEpoch = 0

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
    // Warm-up/cool-down are sourced from the active workout (#107); 0 for an unplanned/skipped run.
    @Volatile private var currentWarmupDuration = 0
    @Volatile private var currentCooldownDuration = 0
    @Volatile private var isStructuredWorkout = false
    @Volatile private var structuredWorkoutPhase = StructuredWorkoutPhase.RUN
    @Volatile private var phaseTimeRemainingSeconds = 0
    @Volatile private var currentRepeat = 1
    @Volatile private var isCreatingSession = false
    // Set (under sessionCreationLock) when STOP lands while startNewDatabaseSession()'s IO insert
    // is still queued — currentSessionId isn't set yet, so stopSession() can't finalize the row.
    // The creating coroutine reads this at its abort points and unwinds (deletes any inserted row,
    // leaves currentSessionId null) instead of bringing a just-stopped run to life (Codex P2 #123).
    private var stopDuringSessionCreation = false
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

    private val completedRunIntervalStats = mutableListOf<RunWalkIntervalStat>()

    companion object {
        const val CHANNEL_ID = "HrServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_FOREGROUND = "ACTION_START_FOREGROUND"
        // The explicit act of beginning a run (#110). Distinct from ACTION_START_FOREGROUND,
        // which now only acquires the strap as a sensor: connecting is no longer starting.
        const val ACTION_START_RUN = "ACTION_START_RUN"
        const val ACTION_STOP_FOREGROUND = "ACTION_STOP_FOREGROUND"
        const val ACTION_PAUSE_SESSION = "ACTION_PAUSE_SESSION"
        const val ACTION_RESUME_SESSION = "ACTION_RESUME_SESSION"
        const val ACTION_FORCE_SCAN = "ACTION_FORCE_SCAN"
        const val ACTION_SET_SIMULATION = "ACTION_SET_SIMULATION"
        const val EXTRA_DEVICE_ADDRESS = "EXTRA_DEVICE_ADDRESS"
        // Set only by explicit Connect taps: marks the connect as a user choice whose strap may
        // be promoted to active on verification. Background auto-connects omit it.
        const val EXTRA_MAKE_ACTIVE = "EXTRA_MAKE_ACTIVE"
        const val EXTRA_SKIP_PLAN = "SKIP_PLAN"
        // START carries the mode the user has selected right now, so a Treadmill/Outdoor switch made
        // just before tapping START is honoured even if its async settings write hasn't landed yet.
        const val EXTRA_RUN_MODE = "EXTRA_RUN_MODE"
        const val EXTRA_SIMULATION_ENABLED = "SIMULATION_ENABLED"
        const val TAG = "HrService"
    }

    private fun logBleDecision(reason: String, detail: String) {
        Log.d(TAG, "BLE decision: $reason | $detail")
    }

    private fun startHardwareSession(overrideAddress: String?, promoteOnVerify: Boolean = false) {
        if (overrideAddress != null) {
            logBleDecision("direct_connect", "Using override device address=$overrideAddress")
            connectToDevice(overrideAddress, promoteOnVerify)
            return
        }

        val savedAddress = currentSettings.activeDeviceAddress
        if (savedAddress != null) {
            // Reconnecting the already-active strap: verification promotes it anyway via the
            // activeDeviceAddress == deviceAddress term, no explicit promotion needed.
            logBleDecision("saved_device_reconnect", "Using saved activeDeviceAddress=$savedAddress")
            connectToDevice(savedAddress, promoteToActive = false)
        } else {
            logBleDecision("fresh_scan", "No saved device available; starting BLE scan")
            startScanning()
        }
    }

    private fun resetRunIntervalTracking() {
        activeRunIntervalTracker = null
        completedRunIntervalStats.clear()
    }

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
        if (!isStructuredWorkout || plannedDurationSeconds <= 0) return
        if (activeRunIntervalTracker != null) {
            finalizeActiveRunIntervalTracking()
        }
        resetCurrentIntervalTransparencyState()
        // Each run interval starts the cue ladder from scratch. The walk-step reset otherwise rides
        // on onSample(awake = false), so a BLE dropout spanning the whole walk lands no sample and
        // the next run step would reuse the previous interval's outSince/lastCueTime and fire an
        // immediate catch-up or return cue. This boundary is timer-driven, not packet-driven, so it
        // resets regardless of dropouts (Codex #124).
        cueLadder.reset()
        currentZone = ZoneBand.UNKNOWN
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
        if (currentPhase != SessionPhase.MAIN ||
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
        if (currentPhase != SessionPhase.MAIN ||
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

        // Promotion, derived. This is the whole of it: no code anywhere else promotes or demotes,
        // so there is no release to forget. distinctUntilChanged is load-bearing — demote() ends
        // in stopSelf(), and this sees every published state change, including each per-second
        // heartbeat. See docs/adr/0001-promotion-is-derived-not-claimed.md.
        serviceScope.launch {
            _hrState
                .map { it.sessionStatus to it.acquiringStrap }
                .distinctUntilChanged()
                .collect { (sessionStatus, acquiringStrap) ->
                    promotion.reconcile(sessionStatus, acquiringStrap)
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
                    recorderWriteScope.launch {
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
                        SessionPhase.WARM_UP -> currentWarmupDuration
                        // The main phase is open-ended (#107): an unplanned run goes until the user
                        // stops and a structured run's intervals self-terminate, so nothing here ends it.
                        SessionPhase.MAIN -> Int.MAX_VALUE
                        SessionPhase.COOL_DOWN -> currentCooldownDuration
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
                    } else if (currentPhase == SessionPhase.COOL_DOWN && phaseSecondsRunning >= phaseLimit) {
                        serviceScope.launch { stopSession() }
                        break
                    }

                    if (currentPhase == SessionPhase.MAIN && isStructuredWorkout) {
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
                            
                            val zone = hrZoneOf(currentBpm, currentSettings)
                            if (zone != null) {
                                sessionZoneTimes[zone.number] = (sessionZoneTimes[zone.number] ?: 0L) + 1
                                // Time above the easy cap is banked by band, not by zone number:
                                // zone 5 charts wider than it bands (see zoneBandOf), so zone
                                // arithmetic would find no zone above a target of 5. In-target
                                // time is no longer banked here at all — it is the target zone's
                                // own total, derived on read (see RunnerSession.inTargetZoneSeconds).
                                if (zoneBandOf(currentBpm, currentSettings.maxHr, activeTargetZone) == ZoneBand.ABOVE) {
                                    sessionAboveTargetSeconds += 1
                                }
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
                                recorderWriteScope.launch {
                                    database.sampleDao().insertSample(sample)
                                }
                            }
                        } else {
                            // No live HR this second — a strapless run, or a dropout that zeroed bpm
                            // (#110). The clock keeps running, so bank the second as "no data" rather
                            // than dropping it: otherwise a long dropout leaves the summary's zone
                            // breakdown and its "No Data" bar silently understating the run. (We don't
                            // write a 0-bpm HrSample: hrZoneOf(0) is null so it adds nothing to the
                            // zone recompute, and the detail chart scales its Y-axis to the sample
                            // min, which a 0 would distort.)
                            sessionNoDataSeconds += 1
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
                            SessionPhase.MAIN -> 0
                            else -> {
                            val limit = when (currentPhase) {
                                SessionPhase.WARM_UP -> currentWarmupDuration
                                SessionPhase.COOL_DOWN -> currentCooldownDuration
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
                            SessionPhase.MAIN -> 0
                            else -> {
                            val limit = when (currentPhase) {
                                SessionPhase.WARM_UP -> currentWarmupDuration
                                SessionPhase.COOL_DOWN -> currentCooldownDuration
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
        // A pulse already mid-execution when STOP runs can reach here after the notification was
        // removed. It used to need a status check to avoid resurrecting a zombie "HR Monitor"
        // notification with no service behind it — one more thing for a caller to remember.
        // Promotion drops text it has nowhere to put, so this is now just a call.
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
        } else if (state.currentPhase == SessionPhase.MAIN) {
            "Main elapsed ${formatTime(state.phaseSecondsElapsed.coerceAtLeast(0))}"
        } else {
            val remaining = formatTime(state.phaseSecondsRemaining.coerceAtLeast(0).toLong())
            "$phaseName • $remaining left"
        }
    }

    private fun resolveActiveWorkoutTemplate(): WorkoutTemplate? {
        val baseWorkout = TrainingPlanProvider.resolveBaseWorkout(
            currentSettings.activePlanId,
            currentSettings.activeStageId
        ) ?: return null
        // Shared with the record screen's card, so what it promises is what this runs (#111).
        return baseWorkout.withCoachAdaptation(currentSettings)
    }

    private fun initializeStructuredWorkoutState() {
        // The plan attaches automatically (#107); skipping today is the only thing that detaches it,
        // and it never edits the plan. Warm-up/cool-down come from the workout, so an unplanned or
        // skipped run has neither.
        activeWorkoutTemplate = if (skipPlanForToday) null else resolveActiveWorkoutTemplate()
        isStructuredWorkout = activeWorkoutTemplate != null
        sessionWasStructured = isStructuredWorkout
        currentWarmupDuration = activeWorkoutTemplate?.warmUpSeconds ?: 0
        currentCooldownDuration = activeWorkoutTemplate?.coolDownSeconds ?: 0
        resetCurrentIntervalTransparencyState()
        structuredWorkoutPhase = StructuredWorkoutPhase.RUN
        phaseTimeRemainingSeconds = activeWorkoutTemplate?.runDurationSeconds ?: 0
        currentRepeat = 1
        hasStructuredWorkoutStarted = false
    }

    private fun onStructuredWorkoutPhaseComplete(workout: WorkoutTemplate) {
        if (!isStructuredWorkout) return

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
                startRunIntervalTracking(
                    intervalIndex = currentRepeat,
                    plannedDurationSeconds = workout.runDurationSeconds
                )
                playCue("Start running, interval $currentRepeat of ${workout.totalRepeats}.")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android gives us roughly five seconds from startForegroundService() to startForeground(),
        // whatever this intent turns out to want — so promote before we know. The reconcile at the
        // tail takes it back if nothing earned it.
        promotion.promoteForStartCommand()

        if (intent == null) {
            // START_STICKY restart after process death: the run that justified the foreground
            // state died with the process (every session field reset with it). Nothing can be
            // resumed, so don't sit around as an idle notification holding a 10-hour wake lock.
            // The promote above is still required (the system expects startForeground after
            // restarting a foreground service); state says IDLE, so the reconcile drops it.
            Log.d(TAG, "onStartCommand: null intent (sticky restart) - nothing to resume, stopping")
            reconcileForegroundPromotion()
            return START_NOT_STICKY
        }

        // Only session-starting intents carry the skip choice; leave it untouched for pause/resume
        // and other control intents so it survives for the duration of the run.
        if (intent?.hasExtra(EXTRA_SKIP_PLAN) == true) {
            skipPlanForToday = intent.getBooleanExtra(EXTRA_SKIP_PLAN, false)
        }
        Log.d(
            TAG,
            "Service start action=${intent?.action ?: "null"} skipPlanForToday=$skipPlanForToday"
        )

        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                val overrideAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                val makeActive = intent.getBooleanExtra(EXTRA_MAKE_ACTIVE, false)
                if (!isSimulationEnabled) {
                    // Called inline, not launched: serviceScope is Dispatchers.Main, so a launch
                    // here would run after onStartCommand returned — and the reconcile at the tail
                    // would see an idle connection status and demote the acquisition it just
                    // started. startHardwareSession publishes "Scanning"/"Connecting" synchronously
                    // and only the GATT connect itself goes to IO, so inline is both safe and true.
                    startHardwareSession(overrideAddress, makeActive)
                } else {
                    Log.d(TAG, "ACTION_START_FOREGROUND received while simulation is active. Skipping hardware startup.")
                }
            }
            ACTION_START_RUN -> {
                // START is the explicit act that begins a run (#110): heart rate is a sensor,
                // not a gate. The clock, distance, and the plan's intervals begin now; the strap
                // is acquired alongside, and zone coaching joins if/when HR arrives.
                val status = _hrState.value.sessionStatus
                val alreadyActive = status != SessionStatus.IDLE && status != SessionStatus.STOPPED
                // A previous stop can still be finalizing: stopSession() publishes STOPPED
                // synchronously but clears currentSessionId only at the end of its async finalize
                // coroutine. Starting in that window would set RUNNING while startNewDatabaseSession()
                // bails on the still-set id, stranding the UI in a run with no DB row or timer. Read
                // both flags under the same lock startNewDatabaseSession() creates the session under.
                val sessionInFlight = synchronized(sessionCreationLock) {
                    isCreatingSession || currentSessionId != null
                }
                if (alreadyActive || sessionInFlight) {
                    Log.d(TAG, "ACTION_START_RUN ignored - session busy (status=$status, inFlight=$sessionInFlight)")
                    // Nothing to undo. onStartCommand promoted before dispatch, and the reconcile
                    // at the tail takes that back on its own if no run is live — which is exactly
                    // the leak d335ef3 had to patch by hand here. A genuinely active run keeps the
                    // promotion, for the same reason and without a second code path.
                } else {
                    // Pin the run mode BEFORE publishing RUNNING. startNewDatabaseSession() also sets
                    // it, but on an IO coroutine; a GATT STATE_CONNECTED landing in the gap would see
                    // isRunning() true with activeSessionRunMode still null and start GPS off the
                    // stale currentSettings.runMode. Setting it here, ahead of the RUNNING write,
                    // closes that window. The run mode comes from the START intent when present so a
                    // just-tapped Treadmill/Outdoor choice wins over a not-yet-persisted setting.
                    val startRunMode = intent.getStringExtra(EXTRA_RUN_MODE) ?: currentSettings.runMode
                    activeSessionRunMode = startRunMode
                    // activeRunMode rides along so the live UI gates distance/map on the mode this
                    // run actually started with, not the possibly-lagging settings value.
                    _hrState.update { it.copy(
                        sessionStatus = SessionStatus.RUNNING,
                        errorMessage = null,
                        activeRunMode = startRunMode
                    ) }
                    // Creates the DB record, starts the 1 Hz tick, and (outdoor) starts location.
                    startNewDatabaseSession(startRunMode)
                    // Acquire the strap as a sensor unless we already have it, HR is simulated, or a
                    // connection is already in flight. Kicking off a fresh acquisition mid-connect
                    // would call startHardwareSession(null) -> startScanning() (no saved address yet
                    // for a first pairing), tearing down the pending GATT and dropping the strap the
                    // user just chose in Manage Devices. Let an in-progress connect finish and join
                    // the run instead.
                    //
                    // "Retrying" ("Disconnected (Retrying)") counts as in flight: a reconnect
                    // coroutine is already scheduled and a parallel connect here would be torn down
                    // by it. A bare scan does NOT: nothing ever auto-connects from scan results
                    // (the callback only fills the Discovered list), so deferring to one leaves the
                    // whole run strapless while the scanner burns battery — let startHardwareSession
                    // take over (connectToDevice stops the scan first; with no saved strap it just
                    // rescans, which is where we already were).
                    val connStatus = _hrState.value.connectionStatus
                    // "Connected" completes acquisition only when the connected strap IS the
                    // active one: Set Active in Manage Devices writes only the settings and
                    // leaves the old GATT up, so a START after switching straps must re-acquire
                    // the newly chosen device instead of recording HR from the old one (Codex P2
                    // #123). With no saved active strap, whatever is connected is the sensor.
                    val activeAddress = currentSettings.activeDeviceAddress
                    val connectedActiveStrap = connStatus == "Connected" &&
                        (activeAddress == null || targetDeviceAddress == activeAddress)
                    // A bare scan deliberately does NOT count (see above), so this is the shared
                    // Acquisition test minus scanning — not a fourth hand-rolled copy of it.
                    val acquisitionInFlight = connectedActiveStrap ||
                        (_hrState.value.acquiringStrap &&
                            !connStatus.contains("Scanning", ignoreCase = true))
                    if (!isSimulationEnabled && !acquisitionInFlight) {
                        val overrideAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                        // Inline for the same reason as ACTION_START_FOREGROUND above: the
                        // connection status must be true before the tail reconcile reads it.
                        startHardwareSession(overrideAddress)
                    }
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
                val status = _hrState.value.sessionStatus
                val runActive = status == SessionStatus.RUNNING || status == SessionStatus.PAUSED
                if (runActive) {
                    // Scanning tears down the current strap, and a scan-only disconnect sets STOPPED
                    // without going through stopSession()'s finalization (see disconnect()) — so a
                    // scan mid-run would silently drop the active run and orphan its DB row. Pairing
                    // is a pre-run action; never scan while a run is live.
                    Log.d(TAG, "Ignoring Force Scan - a run is active (status=$status)")
                } else if (!isSimulationEnabled) {
                    logBleDecision("force_scan", "User requested a fresh scan; skipping saved-device reconnect")
                    if (_hrState.value.connectionStatus == "Connected") {
                        disconnect()
                    }
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
        // Take back the eager promotion above if the dispatch earned nothing. An intent that
        // changes no state publishes nothing for the subscription in onCreate() to react to, so
        // this call is not redundant with it.
        reconcileForegroundPromotion()
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
        // Only a live run can pause. A stale notification action (the shade lags status changes)
        // landing after STOP must not flip a STOPPED/IDLE service to PAUSED.
        if (_hrState.value.sessionStatus != SessionStatus.RUNNING) {
            Log.d(TAG, "pauseSession ignored - status=${_hrState.value.sessionStatus}")
            return
        }
        isAutoPaused = false
        _hrState.update { it.copy(sessionStatus = SessionStatus.PAUSED) }
        locationTracker?.stop()
        updateNotification(forceUpdate = true)
        Log.d(TAG, "Session PAUSED")
    }

    private fun resumeSession() {
        // Same guard class as ACTION_START_RUN (23babf1): a stale notification Resume tapped after
        // STOP would otherwise republish RUNNING while finalize still holds currentSessionId —
        // a ghost run with a ticking timer and no DB session that blocks every later START — or,
        // after finalize clears the id, silently create a brand-new phantom session.
        if (_hrState.value.sessionStatus != SessionStatus.PAUSED) {
            Log.d(TAG, "resumeSession ignored - status=${_hrState.value.sessionStatus}")
            return
        }
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
        // Resume the mode the run started with, not whatever the global setting says now.
        locationTracker?.restartIfNeeded("resumeSession", activeSessionRunMode ?: currentSettings.runMode, isSimulationEnabled)
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

    private fun startNewDatabaseSession(runModeOverride: String? = null) {
        // Reserve the creation synchronously on the caller's thread. Every caller reaches here from
        // onStartCommand (the service main thread), and STOP is dispatched on that same thread, so
        // setting isCreatingSession here guarantees a STOP tapped right after START observes it and
        // can hand finalization off to this coroutine. If the reservation lived inside the launched
        // IO coroutine, a STOP that ran before the coroutine was scheduled would see the flag still
        // false, skip the handoff, and let this coroutine strand a just-stopped run (Codex P2 #123).
        synchronized(sessionCreationLock) {
            if (isCreatingSession || currentSessionId != null) {
                Log.d(TAG, "Skipping DB session start: creating=$isCreatingSession sessionId=$currentSessionId")
                return
            }
            isCreatingSession = true
            stopDuringSessionCreation = false
        }
        serviceScope.launch(Dispatchers.IO) {
            try {
            // A STOP may already have completed on the main thread before this IO coroutine was
            // scheduled. If so, unwind before touching GPS, the timer, or the DB — the run is
            // already stopped. Matches stopSession()'s idle reset.
            if (synchronized(sessionCreationLock) { stopDuringSessionCreation }) {
                Log.d(TAG, "Aborting DB session start: STOP landed before creation began")
                resetRunIntervalTracking()
                currentSessionIncludeInAiTraining = true
                return@launch
            }

            // The mode the user selected at START (if supplied) wins over currentSettings.runMode,
            // whose async write from the mode toggle may not have reached the service yet. Everything
            // downstream in this run — the DB record and whether GPS starts — reads this value.
            val effectiveRunMode = runModeOverride ?: currentSettings.runMode
            // Pin it for the run so a strap (re)connecting before the settings write lands doesn't
            // start GPS off a stale currentSettings.runMode (see the connect/resume callbacks).
            activeSessionRunMode = effectiveRunMode

            // Mission: Reset Phase Engine for a fresh session
            currentPhase = SessionPhase.WARM_UP
            phaseSecondsRunning = 0
            isWarmupSkipped = false
            initializeStructuredWorkoutState()
            resetRunIntervalTracking()
            currentSessionIncludeInAiTraining = currentSettings.aiDataSharingEnabled && !currentSettings.testingModeEnabled
            
            // Reset session-level counters only when a new database session begins
            sessionSecondsRunning = 0
            sessionSecondsPaused = 0
            isAutoPaused = false

            // Mission 4: Reset Location/Pace variables
            locationTracker?.resetSessionState()

            // GPS deliberately does NOT start here: it starts after the commit point below has
            // adopted the session id. onRawFix drops TrackPoints while currentSessionId is null,
            // and fused location can deliver a cached first fix immediately — starting earlier
            // clipped the beginning of the route off the map (Codex P2 #123).

            // Mission: Immediate UI State Reset
            _hrState.update { it.copy(
                currentPhase = SessionPhase.WARM_UP,
                phaseSecondsRemaining = currentWarmupDuration,
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

            // The workout sets the target when a plan is attached; otherwise the global is the
            // fallback (#107). Frozen for the whole run: the coach reads activeTargetZone every
            // second, so recording from it keeps "In Target" and the live coaching in agreement
            // even if the global target zone is changed mid-run.
            activeTargetZone = activeWorkoutTemplate
                ?.let { HrZone.ofNumberOrDefault(it.targetZone) }
                ?: currentSettings.targetHrZone
            val session = RunnerSession(
                startTime = System.currentTimeMillis(),
                targetZone = activeTargetZone.number,
                runMode = effectiveRunMode,
                includeInAiTraining = currentSessionIncludeInAiTraining
            )
            val newSessionId = database.sessionDao().insertSession(session)
            // Commit point: adopt the id only if no STOP arrived while we were creating. If one did
            // (it set stopDuringSessionCreation but couldn't finalize a row that didn't exist yet),
            // delete the row we just inserted and leave currentSessionId null so the run is fully
            // gone and the next START isn't blocked. GPS hasn't started yet (it starts below, after
            // this commit), so there's nothing location-side to unwind.
            val aborted = synchronized(sessionCreationLock) {
                if (stopDuringSessionCreation) {
                    true
                } else {
                    currentSessionId = newSessionId
                    false
                }
            }
            if (aborted) {
                Log.d(TAG, "Aborting DB session start: STOP landed during creation (id=$newSessionId)")
                // stopSession() cleared the pin, but this coroutine re-set it above after that
                // clear; the run is dead, so restore the "null when no run is active" invariant.
                activeSessionRunMode = null
                database.sessionDao().deleteSessionById(newSessionId)
                resetRunIntervalTracking()
                currentSessionIncludeInAiTraining = true
                return@launch
            }

            // Scaffold the live run atomically with respect to STOP. stopSession() raises
            // stopDuringSessionCreation under this lock before any of its teardown, so exactly
            // one of two orderings exists: the flag is visible here and the whole scaffold is
            // skipped (the stop finalized the just-committed row and there is nothing to tear
            // down), or the scaffold completes first and the stop's teardown — timer removal,
            // GPS stop, UI id clear — runs after it and wins. A STOP in the commit-to-scaffold
            // window can no longer leave GPS or the timer running for a finalized run (Codex
            // P2 #123). Everything inside is in-memory or a non-blocking post/request.
            val stoppedAfterCommit = synchronized(sessionCreationLock) {
                if (stopDuringSessionCreation) {
                    true
                } else {
                    // Outdoor distance/pace must run whether or not a strap ever connects (#110),
                    // so location starts with the run itself — now that the session id is
                    // committed, every fix (including an immediate cached one) lands in a
                    // TrackPoint.
                    if (effectiveRunMode == "outdoor") {
                        locationTracker?.restartIfNeeded("run_start", effectiveRunMode, isSimulationEnabled)
                    }
                    _hrState.update { it.copy(activeDbSessionId = currentSessionId, activeTargetZone = activeTargetZone) }
                    sessionMaxBpm = 0
                    sessionBpmSum = 0
                    sessionSampleCount = 0
                    baselineHr = null
                    currentZone = ZoneBand.UNKNOWN
                    cueLadder.reset()
                    sessionAboveTargetSeconds = 0
                    // Must be cleared per run now that it actually accumulates: pulseSession()
                    // banks a no-data second whenever bpm is 0 (strapless run / dropout). It was
                    // previously dead (hrZoneOf only returns null for bpm <= 0, never inside the
                    // bpm > 0 branch that held the old increment), so a stale value would
                    // otherwise leak into every later run's finalized RunnerSession and corrupt
                    // its No-Data/zone summary.
                    sessionNoDataSeconds = 0L
                    lastRecordedSecond = -1
                    // Clear the HR-freshness clock so age is measured within this run, not from a
                    // packet in a previous one. Otherwise a strapless run started after an earlier
                    // run that had HR would inherit a stale timestamp, read as a huge
                    // lastHrAgeSeconds, and trip the >= 8s sensor-lost safety cue — nagging a run
                    // the user deliberately started without a strap (#110). A real packet re-sets
                    // this the moment HR arrives.
                    lastHrTimestamp = 0L
                    // Clear the live reading and smoothing history too, in lock-step with the
                    // freshness clock: a pre-run connected strap can leave bpm/avgBpm holding an
                    // old packet, and with lastHrTimestamp reset that stale value would look fresh
                    // (age 0) and keep being banked by pulseSession() if no in-run packet ever
                    // arrives (silent stall / non-GATT drop). A real packet repopulates both
                    // within ~1s.
                    synchronized(bpmHistory) { bpmHistory.clear() }
                    _hrState.update { it.copy(bpm = 0, avgBpm = 0) }

                    // Mission 3: Reset Zone Timers
                    sessionZoneTimes.keys.forEach { sessionZoneTimes[it] = 0L }

                    // Mission: Session Phases
                    currentPhase = SessionPhase.WARM_UP
                    phaseSecondsRunning = 0
                    walkBreaksCount = 0
                    isWarmupSkipped = false
                    initializeStructuredWorkoutState()

                    // Start the 1 Hz tick only after EVERY per-session reset above. The timer runs
                    // on its own HandlerThread while this block runs on IO; posting the runnable
                    // earlier relied on the ~2s grace tick to outrun the remaining resets — a
                    // timing bet, not an ordering guarantee (an IO stall mid-block would let the
                    // first accounting tick read half-reset counters). Handler.post() also
                    // publishes every write made before it to the timer thread, which the tail
                    // resets otherwise lacked.
                    startSessionTimerLoop()
                    false
                }
            }
            if (stoppedAfterCommit) {
                // The stop owned finalization of the committed row; nothing was scaffolded here.
                Log.d(TAG, "Skipping run scaffold: STOP landed after session commit (id=$newSessionId)")
                return@launch
            }

            Log.d(TAG, "Started DB Session: $currentSessionId (Mode: $effectiveRunMode)")
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
                    currentPhase = currentPhase,
                phaseSecondsRemaining = when (currentPhase) {
                    SessionPhase.MAIN -> 0
                    else -> {
                        val limit = when (currentPhase) {
                            SessionPhase.WARM_UP -> currentWarmupDuration
                            SessionPhase.COOL_DOWN -> currentCooldownDuration
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
        // Idempotency: STOP can arrive from three places at once (the Force Stop button, the
        // notification's always-present Stop action, and the cool-down auto-stop). A second call
        // after the first already published STOPPED must not launch a second finalize for the same
        // session — that would double the DB update, the backup, and the AI plan adjustment (two
        // Gemini calls, nondeterministic second write wins). With no live run left, just honor the
        // explicit kill switch (a pre-run notification Stop still dismisses the service).
        val entryStatus = _hrState.value.sessionStatus
        if (entryStatus == SessionStatus.STOPPED || entryStatus == SessionStatus.IDLE) {
            Log.d(TAG, "stopSession: no live run (status=$entryStatus) - release the strap only")
            releaseStrapAndTimer()
            return
        }
        // Raise the stop flag FIRST, before any teardown below. The creation coroutine may be
        // past its commit point (currentSessionId set) but not yet scaffolded the live run; its
        // scaffold — GPS start, UI id, timer post — runs under this same lock and checks this
        // flag. Setting it here guarantees either the scaffold sees it and skips, or the
        // scaffold completed before this stop proceeds — whose teardown (timer removal, GPS
        // stop, UI id clear) then runs after it and wins. Without this, a STOP landing in the
        // commit-to-scaffold window left GPS registered until service destruction (Codex P2
        // #123). The pre-commit case still also sets it below when deferring finalization.
        synchronized(sessionCreationLock) {
            if (isCreatingSession) stopDuringSessionCreation = true
        }
        _hrState.update { it.copy(
            sessionStatus = SessionStatus.STOPPING,
            activeTargetZone = null,
            activeRunMode = null,
            // Clear the finished run's id from the UI now. It used to linger until the NEXT run's
            // creation coroutine reset it, so in the gap after a quick re-START the UI was RUNNING
            // with the PREVIOUS run's id — and a Force Stop there attached "How did that feel?"
            // feedback to the wrong session.
            activeDbSessionId = null
        ) }
        // Kill the pending tick immediately. Leaving it queued until stopForegroundService() at
        // the end widens the window where a mid-execution pulse can repost itself around the
        // removeCallbacks and bank into (or auto-stop) a run started right after this one.
        sessionHandler?.removeCallbacks(sessionTimerRunnable)
        stopScanning()
        
        // FIX: Capture final counters BEFORE disconnect() resets BLE state
        val finalSecondsRunning = sessionSecondsRunning
        val finalSecondsPaused = sessionSecondsPaused
        val finalDistanceKm = locationTracker?.getDistanceKm() ?: 0.0
        val finalAvgPace = locationTracker?.getPaceMinPerKm() ?: 0.0
        val finalStartLocation = locationTracker?.getFirstLocation()
        val finalWalkBreaksCount = walkBreaksCount
        // The run followed a structured plan workout (#107): drives whether the AI coach evaluates
        // it and is recorded as the run's run/walk flag.
        val finalIsRunWalkMode = sessionWasStructured
        finalizeActiveRunIntervalTracking()

        disconnect()
        locationTracker?.stop()
        // The run is over; the pinned mode must not leak into a later reconnect/resume.
        activeSessionRunMode = null

        // Finalize DB session. Read the id and the in-flight flag together so a START whose DB
        // insert is still queued on IO (currentSessionId not yet set) is treated as "creation in
        // flight", not "idle". Otherwise stopSession() would take the else-branch and the queued
        // coroutine would later insert an unclosed row and leave currentSessionId set, silently
        // blocking every later START (Codex P2 #123). When creation is in flight we hand ownership
        // to that coroutine — it deletes the row it inserts and leaves currentSessionId null.
        val sessionId: Long?
        val deferToCreation: Boolean
        synchronized(sessionCreationLock) {
            sessionId = currentSessionId
            deferToCreation = sessionId == null && isCreatingSession
            if (deferToCreation) {
                stopDuringSessionCreation = true
            }
        }
        if (sessionId != null) {
            // weatherFetchScope, not serviceScope: a background STOP (notification action with the
            // activity unbound) reaches stopSelf() -> onDestroy -> serviceScope.cancel() on the
            // next main-loop message, and a launch that hasn't been dequeued by an IO worker yet
            // dies before its body — the NonCancellable inside can't protect a coroutine that
            // never starts. weatherFetchScope is detached from the service lifecycle precisely so
            // finalization work survives destruction.
            weatherFetchScope.launch {
                // MISSION: Ensure DB update is not cancelled by service destruction
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    val session = database.sessionDao().getSessionById(sessionId)
                    if (session != null) {
                        val avgBpm = if (sessionSampleCount > 0) (sessionBpmSum / sessionSampleCount).toInt() else 0
                        val updatedSession = session.copy(
                            endTime = System.currentTimeMillis(),
                            durationSeconds = finalSecondsRunning,
                            avgBpm = avgBpm,
                            maxBpm = sessionMaxBpm,
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
                            isRunWalkMode = finalIsRunWalkMode
                        )
                        database.sessionDao().updateSession(updatedSession)
                        Log.d(TAG, "Finalized DB Session: $sessionId. Evidence: duration=${updatedSession.durationSeconds}")
                        persistRunIntervalStats(sessionId)

                        // Snapshot run history to Downloads so it survives "Clear storage"
                        // (reinstall is covered separately by Auto Backup). Fire-and-forget on
                        // weatherFetchScope (not cancelled by onDestroy) so stopping from the
                        // background can't skip it.
                        //
                        // First let any still-queued HR sample / track-point inserts land, so the
                        // snapshot captures the whole run rather than the finalized session minus
                        // its final seconds. GPS and the pulse timer are already stopped by now, so
                        // no new writes start once these drain.
                        recorderWriteScope.coroutineContext.job.children.toList().joinAll()
                        weatherFetchScope.launch {
                            DatabaseBackupManager.backup(applicationContext, database)
                        }

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

                        // Release the session guard BEFORE the awaited AI evaluation. The Gemini
                        // call can take seconds to tens of seconds, and holding currentSessionId
                        // through it kept "session busy" true for the whole call — a dead START
                        // button (and a torn-down strap, pre-demote-fix) after every coached run.
                        // Everything below reads only locals, captured values, and the DB. Under
                        // the creation lock: START's reservation reads this field under the same
                        // lock, and the field isn't volatile — a lock-free clear from this IO
                        // thread has no guaranteed visibility on the main thread.
                        synchronized(sessionCreationLock) {
                            currentSessionId = null
                        }
                        currentSessionIncludeInAiTraining = true

                        // finalIsRunWalkMode is sessionWasStructured captured at stop time: now
                        // that the guard is released above, a new run could start and reset the
                        // live field while this evaluation is still deciding.
                        val stageId = currentSettings.activeStageId
                        if (stageId != null &&
                            finalIsRunWalkMode &&
                            updatedSession.includeInAiTraining &&
                            !currentSettings.testingModeEnabled
                        ) {
                            Log.d("AiCoach", "Triggering AI evaluation after session finalization for stage: $stageId")
                            sessionRepository.evaluateAndAdjustPlan(stageId)
                        } else if (stageId != null &&
                            finalIsRunWalkMode &&
                            (!updatedSession.includeInAiTraining || currentSettings.testingModeEnabled)
                        ) {
                            Log.d(
                                "AiCoach",
                                "Skipping AI evaluation: session opted out or testing mode enabled for stage=$stageId"
                            )
                        }
                    } else {
                        resetRunIntervalTracking()
                        synchronized(sessionCreationLock) {
                            currentSessionId = null
                        }
                        currentSessionIncludeInAiTraining = true
                    }
                }
            }
        } else if (!deferToCreation) {
            resetRunIntervalTracking()
            currentSessionIncludeInAiTraining = true
        }

        audioCueManager?.releaseForSessionStop()
        // Publishing STOPPED is what ends the Promotion; releasing the strap is a separate act.
        _hrState.update { it.copy(sessionStatus = SessionStatus.STOPPED) }
        releaseStrapAndTimer()
    }
    
    override fun onInit(status: Int) {
        audioCueManager?.onTtsInit(status)
    }
    
    fun playCue(text: String) {
        audioCueManager?.playCue(text)
    }


    private var lastNotificationTime = 0L
    private val NOTIFICATION_THROTTLE_MS = 10_000L // 10 seconds in background
    
    private fun updateNotification(forceUpdate: Boolean = false, overrideText: String? = null) {
        val now = System.currentTimeMillis()
        val isBackground = !isActivityBound
        
        // Critical State Detection
        val currentState = _hrState.value
        val notificationZone = hrZoneOf(currentState.bpm, currentSettings)
        val zoneChanged = notificationZone != lastNotificationZone
        val phaseChanged = currentPhase != lastNotificationPhase
        
        val isCritical = forceUpdate || zoneChanged || phaseChanged
        
        if (!isCritical && isBackground && (now - lastNotificationTime < NOTIFICATION_THROTTLE_MS)) {
            // Skip non-critical update while in background to save system resources
            return
        }
        
        lastNotificationTime = now
        lastNotificationZone = notificationZone
        lastNotificationPhase = currentPhase
        
        val defaultContent = buildNotificationContent(currentState)
        // Deciding what to say stays here, where zones and phases are known. Posting it belongs
        // to Promotion, which drops the text when there is no notification to put it on — without
        // that, an update landing just after a demotion posts one nothing owns and nothing clears.
        promotion.showNotification(overrideText ?: defaultContent)
    }

    /**
     * The Android half of Promotion. Every call the platform needs lives here; the decision to
     * make them lives in [ForegroundPromotion], which is why the decision has tests and this
     * doesn't. Nothing else in the app may call these.
     */
    private val promotionHost = object : PromotionHost {
        override fun promote(): Boolean {
            val notification = createNotification("Service is running...")
            try {
                startForegroundInternal(notification)
            } catch (e: Exception) {
                // Promotion is now derived, so it can be requested at moments the old
                // caller-driven code never reached — a pre-run reconnect starting on its own
                // while the app is backgrounded, say. Android 12+ answers that with
                // ForegroundServiceStartNotAllowedException. Losing the notification is a
                // degraded run; crashing the service mid-run is not a trade worth making.
                // Reporting false matters: claiming success would have us posting run updates
                // to a notification the platform never created.
                Log.w(TAG, "Foreground promotion refused: ${e.message}")
                return false
            }
            acquireWakeLock()
            return true
        }

        override fun demote() {
            // Reached with nothing promoted too, when a refused promotion's start has to be
            // handed back: releaseWakeLock and stopForeground are both no-ops in that case, and
            // stopSelf is the whole point of the call.
            Log.d(TAG, "demote - dropping notification/wake lock, BLE untouched")
            releaseWakeLock()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            // Safe mid-finalize: the activity's binding keeps the service alive while the app is
            // on screen, and the finalize coroutine runs on a scope that survives destruction.
            stopSelf()
        }

        override fun showNotification(text: String) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, createNotification(text))
        }
    }

    private val promotion = ForegroundPromotion(promotionHost)

    private fun startForegroundInternal(notification: Notification) {
        // Mission: Specify foreground service types for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 throws if we claim a foreground-service type whose permission we don't hold,
            // so claim only the types actually granted. When neither location nor Bluetooth is
            // granted (simulate mode on a fresh install) fall back to DATA_SYNC — a type that needs
            // only the normal, auto-granted FOREGROUND_SERVICE_DATA_SYNC permission. The 2-arg
            // startForeground() is NOT a valid fallback: on 14 it defaults back to the manifest's
            // protected types and throws the same SecurityException.
            val granted = grantedForegroundServiceTypes()
            val types = if (granted != 0) granted else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIFICATION_ID, notification, types)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * The foreground-service types we may legally claim right now. Android 14 rejects a
     * startForeground whose declared type lacks its runtime permission, so LOCATION and
     * CONNECTED_DEVICE are each included only when granted. Returns 0 when neither is held.
     */
    private fun grantedForegroundServiceTypes(): Int {
        var types = 0
        val hasLocation =
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasLocation) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val hasConnectedDevice =
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        if (hasConnectedDevice) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        return types
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

    /**
     * Let go of the Strap: what "Stop Workout" means once the Run itself is already over.
     *
     * This used to also drop the foreground, which made it the second owner of Promotion. It no
     * longer touches it: disconnect() publishes "Disconnected", the Acquisition ends, and — if no
     * Run is live — [reconcileForegroundPromotion] demotes in response. The notification clears a
     * beat later than it did, rather than in the same instant.
     */
    private fun releaseStrapAndTimer() {
        Log.d(TAG, "releaseStrapAndTimer - letting go of the strap")
        stopScanning()
        disconnect()

        // Mission: Stop the zombie timer loop immediately
        sessionHandler?.removeCallbacks(sessionTimerRunnable)
    }

    /**
     * The one place Promotion is re-decided. Subscribed once in [onCreate] and called once more
     * at the tail of [onStartCommand] — an intent that changes no state (an ignored START) emits
     * nothing for the subscription to see, and its eager promotion still has to be taken back.
     */
    private fun reconcileForegroundPromotion() {
        val state = _hrState.value
        promotion.reconcile(state.sessionStatus, state.acquiringStrap)
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
            // Publishing the terminal status IS the release: the Acquisition is over, so
            // Promotion is no longer earned. Nothing to demote by hand (0beef0f).
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

        // ABORT any current connection attempts or reconnect loops. Supersede any queued
        // connect and close under the connect lock (see disconnect()).
        isReconnecting = false
        targetDeviceAddress = null
        synchronized(gattConnectLock) {
            ++connectRequestSeq
            bluetoothGatt?.close()
            bluetoothGatt = null
        }

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

        // A scan has no natural end: nothing auto-connects from it, so an abandoned one would
        // burn the scanner — and the Promotion it earns as an Acquisition — indefinitely.
        // Time-box it; the epoch guard cancels the timeout when a tap/connect stops this scan
        // and possibly starts another.
        val myEpoch = ++scanEpoch
        serviceScope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (myEpoch == scanEpoch &&
                _hrState.value.connectionStatus.contains("Scanning", ignoreCase = true)
            ) {
                Log.d(TAG, "Scan timed out after ${SCAN_TIMEOUT_MS / 1000}s with no selection")
                stopScanning()
                _hrState.update { it.copy(connectionStatus = "Disconnected") }
            }
        }
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
            // Only report the failure if we're still in the scanning state: a late callback
            // delivered after the user already tapped a discovered strap must not overwrite
            // "Connecting to X..." and mask a genuinely in-flight GATT connect.
            _hrState.update {
                if (it.connectionStatus.contains("Scanning", ignoreCase = true)) {
                    it.copy(connectionStatus = "Scan Failed: $errorCode")
                } else {
                    it
                }
            }
        }
    }
    
    fun connectToDevice(address: String, promoteToActive: Boolean = true) {
        logBleDecision("connect_by_address", "Preparing direct connection to address=$address promote=$promoteToActive")
        stopScanning()

        // Step 3: Deep Cleanup / State Sanitization
        reconnectAttemptCount = 0
        reconnectDelay = 3000L

        // Only an explicit user tap (EXTRA_MAKE_ACTIVE on the connect intent) may promote the
        // strap to active on verification — and only THIS strap: the pending promotion is
        // address-typed, so a stale verify of some other strap can never consume it. Background
        // paths (record-screen auto-connect, saved-strap reconnect, retries via the device
        // overload) clear it instead, so auto-connecting strap A while the user makes strap B
        // active can no longer steal the active slot back (Codex P2 #123).
        promoteOnVerifyAddress = if (promoteToActive) address else null
        targetDeviceAddress = address
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            // No adapter (Bluetooth unavailable): a dead-end, so say so. Ending the Acquisition
            // is what releases the Promotion (0beef0f).
            _hrState.update { it.copy(connectionStatus = "Bluetooth Off/Unavailable") }
            return
        }
        connectToDevice(device)
    }

    /**
     * Manage Devices "Forget" (#110): if the forgotten strap is the one we're connected to or
     * chasing, release it — otherwise the retry loop keeps reconnecting it and the verify path
     * re-saves (and re-activates) a device the user just removed. Deliberately narrower than
     * [disconnect]: touches only connection state, never the run or workout state, so forgetting
     * a strap mid-run behaves like a plain dropout (#110: a sensor going away never ends a run).
     */
    fun forgetDevice(address: String) {
        // Before the target check: a pending promotion must never outlive the user forgetting
        // the strap — a late onServicesDiscovered would otherwise re-save it as active right
        // after removeDevice (Codex P2 #123).
        if (promoteOnVerifyAddress == address) promoteOnVerifyAddress = null
        if (targetDeviceAddress != address) return
        logBleDecision("forget_device", "Releasing forgotten device address=$address")
        targetDeviceAddress = null
        isReconnecting = false
        // Supersede any queued connect and close under the same lock, so an in-flight
        // connect coroutine can't re-establish the strap right after this teardown.
        synchronized(gattConnectLock) { ++connectRequestSeq }
        serviceScope.launch(Dispatchers.IO) {
            if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                synchronized(gattConnectLock) {
                    bluetoothGatt?.disconnect()
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }
        _hrState.update { it.copy(
            connectionStatus = "Disconnected",
            connectedDeviceName = null,
            bpm = 0,
            avgBpm = 0
        ) }
    }
    
    private fun stopScanning() {
         ++scanEpoch // cancels any pending scan-timeout for the scan being stopped
         if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
             bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
             Log.d(TAG, "stopScanning() - stopScan invoked")
         }
    }

    // Serializes the close-old/connect-new handoff and makes the LAST requested connect win.
    // Two connect requests in quick succession (auto-connect effect vs a Manage Devices tap,
    // or a double-tap in the device list) each launch an IO coroutine; without this, their
    // close()/connectGatt() steps can interleave — leaking a live GATT whose callbacks keep
    // firing for a strap nobody asked for, unreachable by disconnect().
    private val gattConnectLock = Any()
    @Volatile private var connectRequestSeq = 0

    // Address of the strap an explicit user tap chose, pending promotion to active when its HR
    // service verifies; consumed by onServicesDiscovered only on an exact address match.
    // Background connects (auto-connect, reconnects, retries) null it rather than set it.
    @Volatile private var promoteOnVerifyAddress: String? = null

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            // A silent return here would dead-end the Acquisition without ending it: no retry, no
            // scan timeout, and a status still reading "Connecting" — so Promotion would stay
            // earned forever (0beef0f). Say why; that publish is the release.
            _hrState.update { it.copy(connectionStatus = "Permission Missing") }
            return
        }

        isReconnecting = false
        val deviceName = if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            device.name ?: device.address
        } else {
            device.address
        }
        _hrState.update { it.copy(connectionStatus = "Connecting to $deviceName...") }

        // Mission: Robust Bluetooth - Close old connection and connect on IO
        val mySeq = synchronized(gattConnectLock) { ++connectRequestSeq }
        serviceScope.launch(Dispatchers.IO) {
            synchronized(gattConnectLock) {
                if (mySeq != connectRequestSeq) {
                    Log.d(TAG, "Skipping superseded connect request #$mySeq (latest=$connectRequestSeq)")
                    return@launch
                }
                bluetoothGatt?.close()
                bluetoothGatt = null

                Log.d(TAG, "Connecting to GATT on IO thread...")
                // connectGatt() is non-blocking (results arrive on the callback), so holding the
                // lock across it is safe.
                bluetoothGatt = device.connectGatt(this@HrForegroundService, false, gattCallback)
            }
        }
    }
    
    private fun attemptReconnect() {
         if (targetDeviceAddress == null) return

         // A live run retries forever — a dropout never ends or freezes a run (#110). With no run,
         // the endless loop served no one: a strap left in a drawer kept the record screen on
         // "Looking for your strap…" indefinitely (the terminal "Strap not found" state existed in
         // the footer but was keyed to a status string that only lived for milliseconds). Give up
         // after a few attempts and land on the stable, actionable state instead; the footer's
         // Retry button and START itself both re-acquire from there.
         val status = _hrState.value.sessionStatus
         val runActive = status == SessionStatus.RUNNING || status == SessionStatus.PAUSED
         if (!runActive && reconnectAttemptCount >= PRE_RUN_RECONNECT_MAX_ATTEMPTS) {
             Log.d(TAG, "Giving up pre-run reconnect after $reconnectAttemptCount attempts")
             targetDeviceAddress = null
             isReconnecting = false
             // The chase is over. Publishing the terminal status ends the Acquisition, which is
             // what releases the Promotion — no thread hop needed, unlike the hand-rolled demote
             // this replaces (4fe74cd).
             _hrState.update { it.copy(connectionStatus = "Strap not found") }
             return
         }

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
             val addr = targetDeviceAddress
             if (addr != null) {
                 // Retry via the device overload directly: the String overload is the fresh-connect
                 // entry point and zeroes reconnectAttemptCount/reconnectDelay, which made the
                 // attempt counter reset every cycle — the give-up cap above could never trip.
                 val device = bluetoothAdapter?.getRemoteDevice(addr)
                 if (device != null) connectToDevice(device)
             }
         }
    }

    fun disconnect() {
        targetDeviceAddress = null
        // Supersede any queued connect and close under the same lock (see forgetDevice).
        synchronized(gattConnectLock) { ++connectRequestSeq }
        serviceScope.launch(Dispatchers.IO) {
            if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                synchronized(gattConnectLock) {
                    bluetoothGatt?.disconnect()
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }
        // No sessionStatus write here: disconnecting is no longer stopping (#110). Every current
        // caller runs when no run is live (stopSession sets STOPPED itself; FORCE_SCAN is blocked
        // mid-run), and the old unconditional STOPPED write was a landmine — any future mid-run
        // caller would have silently killed the run and orphaned its DB row.
        _hrState.update { it.copy(
            connectionStatus = "Disconnected",
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
        currentZone = ZoneBand.UNKNOWN
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
                val deviceAddress = gatt?.device?.address ?: ""
                // A delayed connect from a GATT that forgetDevice()/startScanning()/a superseding
                // connect already abandoned must not publish "Connected", discover services, or
                // subscribe — it would keep feeding HR from a strap the user just forgot or
                // replaced (Codex P2 #123). Those paths clear or repoint targetDeviceAddress
                // before closing the old GATT, so a mismatch here is always stale: close it and
                // bail before touching any state. Every legitimate connect sets the target first.
                if (deviceAddress != targetDeviceAddress) {
                    Log.d(TAG, "Ignoring stale STATE_CONNECTED for $deviceAddress (target=$targetDeviceAddress)")
                    serviceScope.launch(Dispatchers.IO) {
                        synchronized(gattConnectLock) {
                            gatt?.close()
                            if (bluetoothGatt == gatt) bluetoothGatt = null
                        }
                    }
                    return
                }

                reconnectDelay = 3000L
                isReconnecting = false
                reconnectAttemptCount = 0
                firstDisconnectTime = 0

                val deviceName = gatt?.device?.name ?: "Unknown"
                
                // The strap is a sensor, not the run's gate (#110): connecting only reports the
                // sensor, it never starts a run or opens a DB record. START owns that now. A run
                // that is already going (including reconnecting after a dropout) simply keeps its
                // status; a bare connect with no run leaves the session IDLE.
                _hrState.update { it.copy(
                    connectionStatus = "Connected",
                    connectedDeviceName = deviceName,
                    reconnectAttempts = 0,
                    errorMessage = null
                ) }

                // Mission 4 FIX: Ensure location updates start if in outdoor mode (only while a
                // run is active; a bare sensor connect must not spin up GPS on its own). Use the
                // run's pinned mode, not currentSettings.runMode, so a strap that connects during
                // the async settings write doesn't start GPS for a treadmill run (or vice versa).
                // Also require the session id to be committed: RUNNING is published before the
                // creation coroutine's DB insert, and onRawFix drops fixes while currentSessionId
                // is null — a fast connect in that window would start GPS early, turning the
                // scaffold's own post-commit start into a no-op and clipping the route start off
                // the map (Codex P2 #123). Mid-run reconnects always have the id set; during
                // creation the scaffold owns the GPS start.
                val sessionRunMode = activeSessionRunMode ?: currentSettings.runMode
                val sessionCommitted = synchronized(sessionCreationLock) { currentSessionId != null }
                if (sessionRunMode == "outdoor" && isRunning() && sessionCommitted) {
                    locationTracker?.restartIfNeeded("session_start", sessionRunMode, isSimulationEnabled)
                }
                
                gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                gatt?.discoverServices()

                // A bare sensor connect (pre-run pairing) doesn't need — or deserve — a
                // Promotion; only a Run does. Publishing "Connected" above ends the Acquisition
                // and says so (4fe74cd).
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (targetDeviceAddress != null) {
                    // Unexpected disconnect while a run is going.
                    if (firstDisconnectTime == 0L) {
                        firstDisconnectTime = System.currentTimeMillis()
                    }

                    // Heart rate can't gate the middle of a run any more than it gates the start
                    // (#110): a strap dropout leaves the run RUNNING and merely stops zone cues.
                    // The elapsed clock, distance, pace and the plan's intervals keep advancing;
                    // only the (HR-driven) coaching goes quiet until the strap reconnects. We keep
                    // retrying in the background, but a lost strap never freezes or ends the run.
                    //
                    // Zero the live HR so the outage isn't banked as data: pulseSession() records a
                    // sample and banks zone/above-cap seconds every second while bpm > 0, so a stale
                    // last reading held across the whole dropout would fabricate HR and skew zone
                    // totals and downstream coaching/AI. bpm returns when a fresh packet arrives.
                    _hrState.update { it.copy(
                        connectionStatus = "Disconnected (Retrying)",
                        bpm = 0,
                        avgBpm = 0
                    ) }

                    serviceScope.launch(Dispatchers.IO) {
                        gatt?.close()
                        if (bluetoothGatt == gatt) bluetoothGatt = null
                        attemptReconnect()
                    }
                } else {
                     // Intentional disconnect (no target to chase). Session status is untouched:
                     // a sensor going away is never a run ending (#110).
                     _hrState.update { it.copy(connectionStatus = "Disconnected") }
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
                        // Persist only the strap still being chased. forgetDevice() and every
                        // superseding path (a new connect, startScanning) clear or repoint
                        // targetDeviceAddress before closing the old GATT, but its callbacks can
                        // still land afterwards — and even a makeActive=false save would re-add a
                        // just-forgotten strap to the saved list (Codex P2 #123). A closed GATT's
                        // discovery has no business persisting anything.
                        if (deviceAddress != targetDeviceAddress) {
                            Log.d(TAG, "Ignoring stale onServicesDiscovered for $deviceAddress (target=$targetDeviceAddress)")
                            return@let
                        }
                        // Promote to active ONLY the strap an explicit Connect tap chose. No
                        // fallback terms: currentSettings is an async DataStore snapshot that can
                        // lag the user's latest selection, so "already active" / "nothing active"
                        // read from it could re-promote a strap the user just replaced or forgot
                        // (Codex P2 #123). Neither fallback is needed — saveDevice(makeActive =
                        // false) leaves the active preference untouched for an already-active
                        // strap, and every first-pairing path is an explicit tap that sets
                        // promoteOnVerifyAddress.
                        val makeActive = deviceAddress == promoteOnVerifyAddress
                        if (makeActive) promoteOnVerifyAddress = null
                        serviceScope.launch {
                            settingsRepository.saveDevice(deviceAddress, deviceName, makeActive)
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
        
        // The one gate for spoken zone cues (#108). Awake only during the run steps of a plan, or an
        // unplanned run once past its 5-minute grace; warm-up, walk and cool-down steps stay silent.
        val awake = when {
            currentPhase != SessionPhase.MAIN -> false
            isStructuredWorkout -> structuredWorkoutPhase == StructuredWorkoutPhase.RUN
            else -> sessionSecondsRunning >= UNPLANNED_GRACE_SECONDS
        }

        // One band, one clock. Hysteresis judges re-entry at the zone midpoint; the ladder decides
        // when to speak; the band below decides what to say. Hysteresis only carries across
        // consecutive awake samples: while asleep (warm-up, walk, grace) we reset it to UNKNOWN, so
        // the first awake sample is judged by the plain band. A run step can then never inherit a
        // stale ABOVE/BELOW and speak over a heart rate that has actually settled into target.
        val band = if (awake) {
            bandWithHysteresis(currentZone, avgBpm, currentSettings.maxHr, activeTargetZone)
        } else {
            ZoneBand.UNKNOWN
        }
        currentZone = band

        when (cueLadder.onSample(now, band, awake)) {
            CueAction.SPEAK -> when (band) {
                ZoneBand.ABOVE -> speakHighCue(avgBpm)
                ZoneBand.BELOW -> speakLowCue()
                else -> {}
            }
            CueAction.RETURN -> speakReturnCue()
            CueAction.SILENT -> {}
        }
    }

    /**
     * The words for an above-target cue — wording only; the ladder already decided it is time to
     * speak. The sentence-picker ([highCueCondition] + [coachingCue], #109) chooses between drift,
     * the structured walk-break, and the plain ease-off; only the walk-break counts toward a run's
     * walk breaks. The recovery-window trigger fires for every above-target cue.
     */
    private fun speakHighCue(avgBpm: Int) {
        val condition = highCueCondition(sessionSecondsRunning, baselineHr, avgBpm, isStructuredWorkout)
        coachingCue(condition).spoken?.let { playCue(it) }
        if (condition == CueCondition.ABOVE_WALK_BREAK) walkBreaksCount++
        recordRunWalkHighHrTriggerEvent(avgBpm)
    }

    private fun speakLowCue() {
        coachingCue(CueCondition.BELOW).spoken?.let { playCue(it) }
    }

    /**
     * The closing bracket of a spoken cue (#108): you were told you had drifted out of target, you
     * came back past the midpoint, and this tells you you are home so you stop guessing. It fires
     * only because the ladder saw a cue was actually spoken while out. The wording is direction-
     * neutral now (#109) — you can re-enter from above or below, so it no longer says "light jog".
     * [recordRunWalkRecoveryCueEvent] self-guards, closing only a recovery window an above-target
     * cue actually opened.
     */
    private fun speakReturnCue() {
        coachingCue(CueCondition.RETURNED).spoken?.let { playCue(it) }
        recordRunWalkRecoveryCueEvent()
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
        val timeOut = cueLadder.secondsOutOfTarget(now)
        val statusStr = when (currentZone) {
            ZoneBand.IN, ZoneBand.UNKNOWN -> "Ready"
            else -> "Next: ${cueLadder.secondsUntilNextCue(now)}s"
        }
        return DebugInfo(avg, zoneStr, "${timeOut}s", statusStr)
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

    fun setSimulationEnabled(enabled: Boolean) {
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
            // No promotion here. Simulation is not a reason to hold one — the Run it starts is,
            // and that Run earns it below. Promoting for simulation itself would strand the
            // notification and wake lock after every simulated run, because isSimulationEnabled
            // is never cleared by STOP.
            val status = _hrState.value.sessionStatus
            val runActive = status == SessionStatus.RUNNING || status == SessionStatus.PAUSED
            // Same busy-guard as ACTION_START_RUN: while a previous run is still finalizing (or a
            // deferred creation is aborting), starting a sim session would either be skipped by
            // the reservation — leaving the UI wedged RUNNING with no session and no timer — or
            // resurrect a just-stopped run. Refuse and undo; the toggle works a moment later.
            val sessionBusy = !runActive && synchronized(sessionCreationLock) {
                isCreatingSession || currentSessionId != null
            }
            if (sessionBusy) {
                Log.d(TAG, "Simulation enable refused - previous session still finalizing")
                isSimulationEnabled = false
                _hrState.update { it.copy(isSimulating = false) }
                return
            }
            if (currentSessionId == null && (status == SessionStatus.IDLE || status == SessionStatus.STOPPED)) {
                // Publish RUNNING BEFORE creating the row, matching the order ACTION_START_RUN
                // already uses. The other way round leaves a window where a Run is being created
                // but nothing published says so — and Promotion, derived from published state,
                // would demote (stopSelf included) in the middle of session creation.
                _hrState.update { it.copy(
                    sessionStatus = SessionStatus.RUNNING,
                    activeRunMode = currentSettings.runMode
                ) }
                startNewDatabaseSession()
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

        // The one wake-lock release outside Promotion, and deliberately so: destruction can be
        // system-initiated, arriving without any demotion having happened. A wake lock must never
        // outlive the service that took it, so this is a last-resort safety net, not a second
        // owner of the decision. acquire/release are idempotent, so a preceding demote is fine.
        releaseWakeLock()
        audioCueManager?.shutdown()
        Log.d(TAG, "Service destroyed")
    }
}
