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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
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
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.location.Location
import java.util.LinkedList
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import com.example.runningapp.data.AppDatabase
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.HrSample
import java.text.SimpleDateFormat
import java.util.Date

enum class SessionStatus { IDLE, CONNECTING, RUNNING, PAUSED, STOPPING, STOPPED, ERROR }
enum class SessionPhase { WARM_UP, MAIN, COOL_DOWN }

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
    
    // Mission 4: Outdoor Running
    val distanceKm: Double = 0.0,
    val paceMinPerKm: Double = 0.0,
    val runMode: String = "treadmill",

    // Mission 2: Settings Summary
    val userSettings: UserSettings = UserSettings()
)

class HrForegroundService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
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
    private var focusRequest: AudioFocusRequest? = null

    // Mission 4: Location
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var locationHandlerThread: HandlerThread? = null
    private var locationHandler: Handler? = null
    private var lastValidLocationTime = 0L
    private var lastLocation: Location? = null
    private var sessionDistanceMeters = 0.0
    private var lastSplitAnnouncedKm = 0
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
    
    // Pace Smoothing (15s window)
    private val PACE_WINDOW_MS = 15000L
    private val paceHistory = LinkedList<Pair<Long, Double>>() // Pair<Timestamp, MetersPerSecond>

    private lateinit var settingsRepository: SettingsRepository
    private var currentSettings = UserSettings()

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
    
    // --- Session Engine State ---
    private var sessionSecondsRunning = 0L
    private var sessionSecondsPaused = 0L
    private var reconnectAttemptCount = 0
    private var lastHrTimestamp = 0L
    private var firstDisconnectTime = 0L
    private val RECONNECT_TIMEOUT_MS = 120_000L // 2 minutes
    
    // Mission: Session Phases
    private var currentPhase = SessionPhase.WARM_UP
    private var phaseSecondsRunning = 0L

    companion object {
        const val CHANNEL_ID = "HrServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_FOREGROUND = "ACTION_START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "ACTION_STOP_FOREGROUND"
        const val ACTION_PAUSE_SESSION = "ACTION_PAUSE_SESSION"
        const val ACTION_RESUME_SESSION = "ACTION_RESUME_SESSION"
        const val ACTION_FORCE_SCAN = "ACTION_FORCE_SCAN"
        const val EXTRA_DEVICE_ADDRESS = "EXTRA_DEVICE_ADDRESS"
        const val TAG = "HrService"
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
        settingsRepository = SettingsRepository(this)
        
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
        
        
        database = AppDatabase.getDatabase(this)
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Mission: Dedicated Session Thread
        sessionHandlerThread = HandlerThread("SessionTrackingThread").apply { start() }
        sessionHandler = Handler(sessionHandlerThread!!.looper)
        
        createNotificationChannel()
    }

    private fun startSessionTimerLoop() {
        sessionHandler?.removeCallbacks(sessionTimerRunnable)
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

        when (currentState.sessionStatus) {
            SessionStatus.RUNNING -> {
                val startSecond = sessionSecondsRunning
                val endSecond = sessionSecondsRunning + deltaSeconds
                
                for (sec in (startSecond + 1)..endSecond) {
                    sessionSecondsRunning = sec
                    phaseSecondsRunning += 1
                    
                    val phaseLimit = when (currentPhase) {
                        SessionPhase.WARM_UP -> currentSettings.warmUpDurationSeconds
                        SessionPhase.MAIN -> Int.MAX_VALUE
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
                    } else if (currentPhase == SessionPhase.COOL_DOWN && phaseSecondsRunning >= phaseLimit) {
                        serviceScope.launch { stopSession() }
                        break
                    }

                    if (sec == 600L && currentState.bpm > 0) {
                        baselineHr = currentState.bpm
                        Log.d(TAG, "Baseline HR captured at 10m: $baselineHr BPM")
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
                                    latitude = lastLocation?.latitude,
                                    longitude = lastLocation?.longitude,
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
                    val distStr = "%.2f".format(sessionDistanceMeters / 1000.0)
                    updateNotification(
                        forceUpdate = statusChanged,
                        overrideText = "Dist: ${distStr}km | HR: ${currentState.bpm} BPM"
                    )
                }

                _hrState.update { 
                    it.copy(
                        secondsRunning = sessionSecondsRunning,
                        lastHrAgeSeconds = hrAge,
                        zoneTimes = sessionZoneTimes.toMap(),
                        isSimulating = isSimulationEnabled,
                        currentPhase = currentPhase,
                        phaseSecondsRemaining = if (currentPhase == SessionPhase.MAIN) 0 else {
                            val limit = when (currentPhase) {
                                SessionPhase.WARM_UP -> currentSettings.warmUpDurationSeconds
                                SessionPhase.COOL_DOWN -> currentSettings.coolDownDurationSeconds
                                else -> 0
                            }
                            (limit - phaseSecondsRunning).toInt()
                        }
                    )
                }
            }
            SessionStatus.PAUSED -> {
                sessionSecondsPaused += deltaSeconds
                _hrState.update { 
                    it.copy(
                        secondsPaused = sessionSecondsPaused,
                        lastHrAgeSeconds = hrAge,
                        currentPhase = currentPhase,
                        phaseSecondsRemaining = if (currentPhase == SessionPhase.MAIN) 0 else {
                            val limit = when (currentPhase) {
                                SessionPhase.WARM_UP -> currentSettings.warmUpDurationSeconds
                                SessionPhase.COOL_DOWN -> currentSettings.coolDownDurationSeconds
                                else -> 0
                            }
                            (limit - phaseSecondsRunning).toInt()
                        }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Mission: Build persistent notification and call startForeground immediately
        startForegroundService()

        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                val overrideAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (!isSimulationEnabled) {
                    serviceScope.launch {
                        if (overrideAddress != null) {
                            Log.d(TAG, "EXTRA_DEVICE_ADDRESS found: $overrideAddress. Overriding saved state.")
                            connectToDevice(overrideAddress)
                        } else {
                            // Try to connect to active device first, else scan
                            val settings = currentSettings
                            if (settings.activeDeviceAddress != null) {
                                connectToDevice(settings.activeDeviceAddress!!)
                            } else {
                                startScanning()
                            }
                        }
                    }
                } else {
                    Log.d(TAG, "ACTION_START_FOREGROUND received but Simulation Mode is active. Bypassing hardware.")
                    if (currentSessionId == null) {
                        startNewDatabaseSession()
                    }
                    _hrState.update { it.copy(sessionStatus = SessionStatus.RUNNING) }
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
                    startScanning()
                } else {
                    Log.d(TAG, "Ignoring Force Scan - Simulation Mode is active.")
                }
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
        _hrState.update { it.copy(sessionStatus = SessionStatus.PAUSED) }
        stopLocationUpdates()
        Log.d(TAG, "Session PAUSED")
    }

    private fun resumeSession() {
        if (currentSessionId == null) {
            startNewDatabaseSession()
        }
        _hrState.update { it.copy(sessionStatus = SessionStatus.RUNNING) }
        startSessionTimerLoop()
        if (currentSettings.runMode == "outdoor" && !isSimulationEnabled) {
            startLocationUpdates()
        }
        Log.d(TAG, "Session RESUMED")
    }

    private fun startNewDatabaseSession() {
        serviceScope.launch(Dispatchers.IO) {
            // Mission: Reset Phase Engine for a fresh session
            currentPhase = SessionPhase.WARM_UP
            phaseSecondsRunning = 0
            
            // Reset session-level counters only when a new database session begins
            sessionSecondsRunning = 0
            sessionSecondsPaused = 0
            
            // Mission 4: Reset Location/Pace variables
            sessionDistanceMeters = 0.0
            lastSplitAnnouncedKm = 0
            synchronized(paceHistory) { paceHistory.clear() }
            lastLocation = null

            // Mission: Immediate UI State Reset 
            _hrState.update { it.copy(
                currentPhase = SessionPhase.WARM_UP,
                phaseSecondsRemaining = currentSettings.warmUpDurationSeconds,
                secondsRunning = 0,
                secondsPaused = 0,
                distanceKm = 0.0,
                paceMinPerKm = 0.0
            )}

            val session = RunnerSession(
                startTime = System.currentTimeMillis(),
                runMode = currentSettings.runMode
            )
            currentSessionId = database.sessionDao().insertSession(session)
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
            
            Log.d(TAG, "Started DB Session: $currentSessionId (Mode: ${currentSettings.runMode})")
        }
    }

    fun skipCurrentPhase() {
        when (currentPhase) {
            SessionPhase.WARM_UP -> {
                currentPhase = SessionPhase.MAIN
                phaseSecondsRunning = 0
                playCue("Warm up skipped. Starting workout.")
            }
            SessionPhase.MAIN -> {
                currentPhase = SessionPhase.COOL_DOWN
                phaseSecondsRunning = 0
                playCue("Starting cool down.")
            }
            SessionPhase.COOL_DOWN -> {
                stopSession()
            }
        }

        // Mission: Immediate UI Sync
        _hrState.update { currentState ->
            currentState.copy(
                currentPhase = currentPhase,
                phaseSecondsRemaining = if (currentPhase == SessionPhase.MAIN) 0 else {
                    val limit = when (currentPhase) {
                        SessionPhase.WARM_UP -> currentSettings.warmUpDurationSeconds
                        SessionPhase.COOL_DOWN -> currentSettings.coolDownDurationSeconds
                        else -> 0
                    }
                    (limit - phaseSecondsRunning).toInt()
                }
            )
        }
    }

    fun stopSession() {
        _hrState.update { it.copy(sessionStatus = SessionStatus.STOPPING) }
        stopScanning()
        
        // FIX: Capture final counters BEFORE disconnect() resets BLE state
        val finalSecondsRunning = sessionSecondsRunning
        val finalSecondsPaused = sessionSecondsPaused
        val finalDistanceKm = sessionDistanceMeters / 1000.0
        val finalAvgPace = calculatePace()
        
        disconnect()
        stopLocationUpdates()
        
        // Finalize DB session
        val sessionId = currentSessionId
        if (sessionId != null) {
            serviceScope.launch(Dispatchers.IO) {
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
                            timeInTargetZoneSeconds = sessionInTargetZoneSeconds,
                            distanceKm = finalDistanceKm,
                            avgPaceMinPerKm = finalAvgPace,
                            zone1Seconds = sessionZoneTimes[1] ?: 0L,
                            zone2Seconds = sessionZoneTimes[2] ?: 0L,
                            zone3Seconds = sessionZoneTimes[3] ?: 0L,
                            zone4Seconds = sessionZoneTimes[4] ?: 0L,
                            zone5Seconds = sessionZoneTimes[5] ?: 0L,
                            noDataSeconds = sessionNoDataSeconds
                        )
                        database.sessionDao().updateSession(updatedSession)
                        Log.d(TAG, "Finalized DB Session: $sessionId. Evidence: duration=${updatedSession.durationSeconds}")
                    }
                    currentSessionId = null
                }
            }
        }

        _hrState.update { it.copy(sessionStatus = SessionStatus.STOPPED) }
        stopForegroundService()
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    abandonAudioFocus()
                }

                override fun onError(utteranceId: String?) {
                    abandonAudioFocus()
                }
            })
        } else {
            Log.e(TAG, "TTS Initialization failed")
        }
    }
    
    fun playCue(text: String) {
        if (tts == null) return
        
        if (requestAudioFocus()) {
            val params = android.os.Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "CUE_ID")
            Log.d(TAG, "Playing Cue: $text")
        } else {
            Log.w(TAG, "Audio focus request failed")
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* No-op, managed by transient duration */ }
                .build()

            focusRequest = request
            val res = audioManager?.requestAudioFocus(request)
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val res = audioManager?.requestAudioFocus(
                null, 
                AudioManager.STREAM_MUSIC, 
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
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
        
        val defaultContent = "HR: ${currentState.bpm} | Active: ${formatTime(sessionSecondsRunning)}"
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HR Monitor")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(activityPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Workout", stopPendingIntent)
            .build()
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
    }

    private val scanCallback = object : android.bluetooth.le.ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
            result?.device?.let { device ->
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
            _hrState.update { it.copy(connectionStatus = "Scan Failed: $errorCode") }
        }
    }
    
    fun connectToDevice(address: String) {
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
            bpm = 0, 
            connectedDeviceName = null, 
            discoveredServices = emptyList()
        ) }
        synchronized(bpmHistory) {
            bpmHistory.clear()
        }
        currentZone = Zone.UNKNOWN
        
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
                    startLocationUpdates()
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
        
        val newZone = when {
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
        
        if (cooldownRemaining <= 0) {
             val persistenceHighMs = currentSettings.persistenceHighSeconds * 1000L
             val persistenceLowMs = currentSettings.persistenceLowSeconds * 1000L

             if (currentZone == Zone.HIGH && timeInCurrentZone >= persistenceHighMs) {
                 // MISSION: Warm-up Coaching Buffer - mute High HR cues for first 8 mins
                 val isBufferActive = sessionSecondsRunning < 480
                 val criticalThreshold = currentSettings.zone2High + 15
                 
                 // MISSION: Cardiac Drift Detection - >20 mins, < baseline + 12
                 val isDrifting = sessionSecondsRunning > 1200 && 
                                 baselineHr != null && 
                                 avgBpm < (baselineHr!! + 12)

                 if (isDrifting) {
                    val driftCooldownMs = 300_000L // 5 mins
                    if (now - lastDriftCueTime >= driftCooldownMs) {
                        playCue("Heart rate drifting up. Keep effort steady, or take a short walk break.")
                        lastDriftCueTime = now
                        lastCueTime = now
                        Log.d(TAG, "Drift Cue Played (Time: ${sessionSecondsRunning}s, Avg: $avgBpm, Base: $baselineHr)")
                    } else {
                        Log.d(TAG, "Drift detected but suppressed by anti-nag cooldown")
                    }
                 } else if (!isBufferActive || avgBpm > criticalThreshold) {
                     val text = if (currentSettings.voiceStyle == "short") "Ease off" else "Ease off slightly."
                     playCue(text)
                     lastCueTime = now
                 } else {
                     Log.d(TAG, "Warm-up Buffer Active: Muting High HR cue (Time: ${sessionSecondsRunning}s, Avg: $avgBpm, Limit: $criticalThreshold)")
                 }
             } else if (currentZone == Zone.LOW && timeInCurrentZone >= persistenceLowMs) {
                 // Low HR cues are NOT muted during buffer
                 val text = if (currentSettings.voiceStyle == "short") "Faster" else "Gently increase pace."
                 playCue(text)
                 lastCueTime = now
             }
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

    fun toggleSimulation() {
        isSimulationEnabled = !isSimulationEnabled
        _hrState.update { it.copy(isSimulating = isSimulationEnabled) }
        
        if (isSimulationEnabled) {
            Log.d(TAG, "Simulation Mode ENABLED - Starting Session")
            if (currentSessionId == null) {
                startNewDatabaseSession()
            }
            _hrState.update { it.copy(sessionStatus = SessionStatus.RUNNING) }
            startSessionTimerLoop()
            if (currentSettings.runMode == "outdoor" && !isSimulationEnabled) {
                startLocationUpdates()
            }
        } else {
            Log.d(TAG, "Simulation Mode DISABLED")
            // Note: We don't necessarily stop the session here if the user wanted to keep it running
            // but usually simulation is for the whole session in tests.
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission missing, cannot start updates")
            return
        }

        // MISSION: Move location updates to a background thread to prevent main thread stalls
        if (locationHandlerThread == null || !locationHandlerThread!!.isAlive) {
            locationHandlerThread = HandlerThread("LocationThread").apply { start() }
            locationHandler = Handler(locationHandlerThread!!.looper)
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // Mission: Stop zombie updates if session is no longer active
                if (_hrState.value.sessionStatus != SessionStatus.RUNNING) {
                    Log.d(TAG, "Ignoring location update - session not running")
                    return
                }
                for (location in locationResult.locations) {
                    handleNewLocation(location)
                }
            }
        }
        
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, locationHandler?.looper ?: mainLooper)
        Log.d(TAG, "Location updates started on custom looper")
    }

    private fun stopLocationUpdates() {
        Log.d(TAG, "stopLocationUpdates() - Killing location engine")
        val callback = locationCallback
        if (callback != null) {
            fusedLocationClient.removeLocationUpdates(callback)
            locationCallback = null
        }
        
        lastLocation = null
        Log.d(TAG, "Location updates stopped")
    }

    private fun handleNewLocation(location: Location) {
        val now = System.currentTimeMillis()
        Log.d(TAG, "New location: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}")
        
        // 1. Update Distance and Speed Fallback
        var speedMps = 0.0
        lastLocation?.let { last ->
            val distance = last.distanceTo(location).toDouble()
            val timeDeltaSec = (now - last.time) / 1000.0
            
            // MISSION: Smart Reject - Allow lower accuracy if we just recovered from a gap
            val timeSinceLastValid = (now - lastValidLocationTime) / 1000
            val accuracyThreshold = if (timeSinceLastValid > 30) 250.0 else 100.0
            
            if (location.accuracy <= accuracyThreshold) { 
                sessionDistanceMeters += distance
                lastValidLocationTime = now
                Log.d(TAG, "Distance updated: +${"%.2f".format(distance)}m, total=${"%.2f".format(sessionDistanceMeters)}m (Threshold: ${accuracyThreshold}m)")
            } else {
                Log.w(TAG, "Location rejected: accuracy=${location.accuracy}m > threshold=${accuracyThreshold}m")
            }

            // Speed fallback if hardware speed is missing
            speedMps = if (location.hasSpeed() && location.speed > 0.1f) {
                location.speed.toDouble()
            } else if (timeDeltaSec > 0.5) {
                distance / timeDeltaSec // Calculate speed from distance/time
            } else {
                0.0
            }
        }
        lastLocation = location
        
        // 2. Update Pace History for Smoothing
        // Use a lower threshold (0.2 m/s ~= 0.7 km/h) for people walking or testing
        if (speedMps > 0.2) { 
            synchronized(paceHistory) {
                paceHistory.add(Pair(now, speedMps))
                while (paceHistory.isNotEmpty() && (now - paceHistory.first.first > PACE_WINDOW_MS)) {
                    paceHistory.removeFirst()
                }
            }
        } else {
             synchronized(paceHistory) {
                 paceHistory.add(Pair(now, 0.0))
                  while (paceHistory.isNotEmpty() && (now - paceHistory.first.first > PACE_WINDOW_MS)) {
                    paceHistory.removeFirst()
                }
             }
        }

        // 3. Check for 1km Splits
        val currentKm = (sessionDistanceMeters / 1000).toInt()
        if (currentSettings.splitAnnouncementsEnabled && currentKm > lastSplitAnnouncedKm) {
            lastSplitAnnouncedKm = currentKm
            val pace = calculatePace()
            if (pace > 0) {
                val paceMins = pace.toInt()
                val paceSecs = ((pace - paceMins) * 60).roundToInt()
                playCue("Split $currentKm kilometer. Pace $paceMins minutes $paceSecs seconds per kilometer.")
            } else {
                playCue("Split $currentKm kilometer.")
            }
        }

        val currentDistanceKm = sessionDistanceMeters / 1000.0
        val currentPace = calculatePace()

        _hrState.update { it.copy(
            distanceKm = currentDistanceKm,
            paceMinPerKm = currentPace
        ) }
    }

    private fun calculatePace(): Double {
        synchronized(paceHistory) {
            if (paceHistory.isEmpty()) return 0.0
            val avgSpeedMps = paceHistory.map { it.second }.average()
            if (avgSpeedMps <= 0.1) return 0.0
            
            // Pace (min/km) = 1000 / (speed * 60)
            return 1000.0 / (avgSpeedMps * 60.0)
        }
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
        
        stopLocationUpdates()
        
        releaseWakeLock()
        tts?.shutdown()
        Log.d(TAG, "Service destroyed")
    }
}
