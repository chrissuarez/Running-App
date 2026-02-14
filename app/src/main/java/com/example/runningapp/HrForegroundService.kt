package com.example.runningapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.os.Build
import android.os.IBinder
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

    // Session Phases
    val currentPhase: SessionPhase = SessionPhase.WARM_UP,
    val phaseSecondsRemaining: Int = 0,

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
    private var reconnectDelay = 1000L
    private var lastNotificationTime = 0L
    private var isReconnecting = false
    
    // TTS & Audio Focus
    private var tts: TextToSpeech? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private lateinit var settingsRepository: SettingsRepository
    private var currentSettings = UserSettings()

    private lateinit var database: AppDatabase
    private var currentSessionId: Long? = null
    private var sessionMaxBpm = 0
    private var sessionBpmSum = 0L
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
    
    // --- Session Engine State ---
    private var sessionSecondsRunning = 0L
    private var sessionSecondsPaused = 0L
    private var reconnectAttemptCount = 0
    private var lastHrTimestamp = 0L
    private var firstDisconnectTime = 0L
    private val RECONNECT_TIMEOUT_MS = 120_000L // 2 minutes
    private var timerJob: Job? = null
    
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
        const val TAG = "HrService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): HrForegroundService = this@HrForegroundService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
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
        
        createNotificationChannel()
        startSessionTimerLoop()
    }

    private fun startSessionTimerLoop() {
        if (timerJob?.isActive == true) return
        timerJob = serviceScope.launch {
            while (this.isActive) {
                delay(1000)
                
                // Mission 3: Side-effect outside of .update to avoid CAS recursion/ANR
                if (isSimulationEnabled) {
                    updateSimulationData()
                }

                _hrState.update { currentState ->
                    val now = System.currentTimeMillis()
                    val hrAge = if (lastHrTimestamp > 0) (now - lastHrTimestamp) / 1000 else 0
                    
                    when (currentState.sessionStatus) {
                        SessionStatus.RUNNING -> {
                            sessionSecondsRunning++
                            phaseSecondsRunning++
                            
                            // Phase Transition & Notification Logic
                            val phaseLimit = when (currentPhase) {
                                SessionPhase.WARM_UP -> currentSettings.warmUpDurationSeconds
                                SessionPhase.MAIN -> Int.MAX_VALUE
                                SessionPhase.COOL_DOWN -> currentSettings.coolDownDurationSeconds
                            }
                            
                            val remaining = (phaseLimit - phaseSecondsRunning).toInt()
                            
                            // Voice alert at 10 seconds
                            if (currentPhase != SessionPhase.MAIN && remaining == 10) {
                                val phaseName = if (currentPhase == SessionPhase.WARM_UP) "warm up" else "cool down"
                                playCue("10 seconds of $phaseName remaining")
                            }
                            
                            // Auto-transition
                            if (currentPhase == SessionPhase.WARM_UP && phaseSecondsRunning >= phaseLimit) {
                                currentPhase = SessionPhase.MAIN
                                phaseSecondsRunning = 0
                                playCue("Starting main workout")
                            } else if (currentPhase == SessionPhase.COOL_DOWN && phaseSecondsRunning >= phaseLimit) {
                                serviceScope.launch { stopSession() }
                            }
                            
                            // Record sample once per second
                            if (sessionSecondsRunning > lastRecordedSecond) {
                                lastRecordedSecond = sessionSecondsRunning
                                val currentBpm = currentState.bpm
                                if (currentBpm > 0) {
                                    sessionMaxBpm = maxOf(sessionMaxBpm, currentBpm)
                                    sessionBpmSum += currentBpm
                                    sessionSampleCount++
                                    
                                    // MISSION: Coaching & Zones only in MAIN phase
                                    if (currentPhase == SessionPhase.MAIN) {
                                        val isTarget = currentState.currentZone == "TARGET"
                                        if (isTarget) sessionInTargetZoneSeconds++
                                        
                                        // Calculate and track zone for this second
                                        val zone = calculateZone(currentBpm, currentSettings.maxHr)
                                        if (zone in 1..5) {
                                            sessionZoneTimes[zone] = (sessionZoneTimes[zone] ?: 0L) + 1
                                        }
                                    }
                                    
                                    val sessionId = currentSessionId
                                    if (sessionId != null) {
                                        val sample = HrSample(
                                            sessionId = sessionId,
                                            elapsedSeconds = sessionSecondsRunning,
                                            rawBpm = currentBpm,
                                            smoothedBpm = currentState.avgBpm,
                                            connectionState = currentState.connectionStatus
                                        )
                                        serviceScope.launch(Dispatchers.IO) {
                                            database.sampleDao().insertSample(sample)
                                        }
                                    }
                                }
                            }
 
                            currentState.copy(
                                secondsRunning = sessionSecondsRunning,
                                lastHrAgeSeconds = hrAge,
                                zoneTimes = sessionZoneTimes.toMap(),
                                isSimulating = isSimulationEnabled,
                                currentPhase = currentPhase,
                                phaseSecondsRemaining = if (currentPhase == SessionPhase.MAIN) 0 else remaining
                            )
                        }
                        SessionStatus.PAUSED -> {
                            sessionSecondsPaused++
                            currentState.copy(
                                secondsPaused = sessionSecondsPaused,
                                lastHrAgeSeconds = hrAge
                            )
                        }
                        SessionStatus.CONNECTING -> {
                            // Check for 2-minute reconnect timeout
                            if (firstDisconnectTime > 0 && (now - firstDisconnectTime > RECONNECT_TIMEOUT_MS)) {
                                currentState.copy(
                                    sessionStatus = SessionStatus.ERROR,
                                    errorMessage = "Reconnect Timeout (2m)",
                                    lastHrAgeSeconds = hrAge
                                )
                            } else {
                                currentState.copy(lastHrAgeSeconds = hrAge)
                            }
                        }
                        else -> currentState.copy(lastHrAgeSeconds = hrAge)
                    }
                }
                updateNotification() // Refresh HR Age in notification
            }
        }
    }

    private fun updateNotification() {
        if (_hrState.value.sessionStatus != SessionStatus.IDLE && _hrState.value.sessionStatus != SessionStatus.STOPPED) {
             val state = _hrState.value
             val content = "HR: ${state.bpm} | Active: ${formatTime(state.secondsRunning)}"
             val notification = createNotification(content)
             val manager = getSystemService(NotificationManager::class.java)
             manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun formatTime(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return "%02d:%02d".format(mins, secs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                startForegroundService()
                serviceScope.launch {
                    // Try to connect to active device first, else scan
                    val settings = currentSettings
                    if (settings.activeDeviceAddress != null) {
                        connectToDevice(settings.activeDeviceAddress!!)
                    } else {
                        startScanning()
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
                startForegroundService()
                startScanning()
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
        Log.d(TAG, "Session PAUSED")
    }

    private fun resumeSession() {
        if (currentSessionId == null) {
            startNewDatabaseSession()
        }
        _hrState.update { it.copy(sessionStatus = SessionStatus.RUNNING) }
        Log.d(TAG, "Session RESUMED")
    }

    private fun startNewDatabaseSession() {
        serviceScope.launch(Dispatchers.IO) {
            // Reset session-level counters only when a new database session begins
            sessionSecondsRunning = 0
            sessionSecondsPaused = 0
            
            val session = RunnerSession(
                startTime = System.currentTimeMillis()
            )
            currentSessionId = database.sessionDao().insertSession(session)
            sessionMaxBpm = 0
            sessionBpmSum = 0
            sessionSampleCount = 0
            sessionInTargetZoneSeconds = 0
            lastRecordedSecond = -1
            
            // Mission 3: Reset Zone Timers
            sessionZoneTimes.keys.forEach { sessionZoneTimes[it] = 0L }
            
            // Mission: Session Phases
            currentPhase = SessionPhase.WARM_UP
            phaseSecondsRunning = 0
            
            Log.d(TAG, "Started DB Session: $currentSessionId")
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
    }

    fun stopSession() {
        _hrState.update { it.copy(sessionStatus = SessionStatus.STOPPING) }
        stopScanning()
        
        // FIX: Capture final counters BEFORE disconnect() resets BLE state
        val finalSecondsRunning = sessionSecondsRunning
        val finalSecondsPaused = sessionSecondsPaused
        
        disconnect()
        
        // Finalize DB session
        val sessionId = currentSessionId
        if (sessionId != null) {
            serviceScope.launch(Dispatchers.IO) {
                val session = database.sessionDao().getSessionById(sessionId)
                if (session != null) {
                    val avgBpm = if (sessionSampleCount > 0) (sessionBpmSum / sessionSampleCount).toInt() else 0
                    val updatedSession = session.copy(
                        endTime = System.currentTimeMillis(),
                        durationSeconds = finalSecondsRunning, // Use captured non-zero value
                        avgBpm = avgBpm,
                        maxBpm = sessionMaxBpm,
                        timeInTargetZoneSeconds = sessionInTargetZoneSeconds,
                        // Mission 3: Persist Zone Timers
                        zone1Seconds = sessionZoneTimes[1] ?: 0L,
                        zone2Seconds = sessionZoneTimes[2] ?: 0L,
                        zone3Seconds = sessionZoneTimes[3] ?: 0L,
                        zone4Seconds = sessionZoneTimes[4] ?: 0L,
                        zone5Seconds = sessionZoneTimes[5] ?: 0L
                    )
                    database.sessionDao().updateSession(updatedSession)
                    // Added debug evidence as requested
                    Log.d(TAG, "Finalized DB Session: $sessionId. Evidence: secondsRunning=$finalSecondsRunning, secondsPaused=$finalSecondsPaused, finalDuration=${updatedSession.durationSeconds}")
                }
                currentSessionId = null
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


    private fun startForegroundService() {
        val notification = createNotification("Monitoring Heart Rate...")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundService() {
        stopScanning() // Stop scanning if running
        disconnect() // Disconnect GATT if connected
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun createNotification(content: String): Notification {
         return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HR Monitor")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOnlyAlertOnce(true) // Don't buzz every time
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "HR Monitor Service Channel",
                NotificationManager.IMPORTANCE_LOW
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
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
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
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
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
                reconnectDelay = 1000L 
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

                // Save device as active
                serviceScope.launch {
                    settingsRepository.saveDevice(deviceAddress, deviceName)
                }

                // FIX: Ensure a database session exists immediately upon connection
                if (currentSessionId == null) {
                    startNewDatabaseSession()
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
                    
                    gatt?.close()
                    bluetoothGatt = null
                    attemptReconnect()
                } else {
                     _hrState.update { it.copy(
                         connectionStatus = "Disconnected",
                         sessionStatus = SessionStatus.STOPPED
                     ) }
                     gatt?.close()
                     bluetoothGatt = null
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
        
        if (System.currentTimeMillis() - lastNotificationTime > 5000) {
            lastNotificationTime = System.currentTimeMillis()
            val notification = createNotification("HR: $bpm BPM | Avg: ${debugInfo.avg}")
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
    
    private fun processCoachingRules(bpm: Int, now: Long) {
        // MISSION: Block coaching cues outside MAIN phase
        if (currentPhase != SessionPhase.MAIN) {
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
                 val text = if (currentSettings.voiceStyle == "short") "Ease off" else "Ease off slightly."
                 playCue(text)
                 lastCueTime = now
             } else if (currentZone == Zone.LOW && timeInCurrentZone >= persistenceLowMs) {
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

    private fun calculateZone(bpm: Int, maxHr: Int): Int {
        if (bpm <= 0 || maxHr <= 0) return 0
        val percent = (bpm.toFloat() / maxHr) * 100
        return when {
            percent < 50 -> 0
            percent < 60 -> 1
            percent < 70 -> 2
            percent < 80 -> 3
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
        Log.d(TAG, "Simulation Mode: $isSimulationEnabled")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        disconnect()
        tts?.shutdown()
    }
}
