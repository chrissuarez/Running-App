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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

// simple data class to hold the state
data class HrState(
    val connectionStatus: String = "Disconnected",
    val bpm: Int = 0,
    val lastUpdateTimestamp: Long = 0,
    val connectedDeviceName: String? = null,
    val scannedDevices: List<BluetoothDevice> = emptyList(),
    val discoveredServices: List<String> = emptyList(),
    val lastPacketTimeFormatted: String = "--:--:--.---",
    val dataBits: String = "Unknown",
    // Coaching Debug Info
    val avgBpm: Int = 0,
    val currentZone: String = "No Data", // "Low", "Target", "High"
    val timeInZoneString: String = "0s", // e.g. "Trigger in 25s"
    val cooldownWithHysteresisString: String = "Ready" // "Cool: 45s" or "Hysteresis: 10s"
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

    // --- Coaching Rules Engine State ---
    private val TARGET_LOW = 120
    private val TARGET_HIGH = 140
    private val HISTORY_WINDOW_MS = 5000L
    
    // Pair<Timestamp, Bpm>
    private val bpmHistory = LinkedList<Pair<Long, Int>>()
    
    private enum class Zone { LOW, TARGET, HIGH, UNKNOWN }
    private var currentZone = Zone.UNKNOWN
    private var zoneEnterTime = 0L
    
    private var lastCueTime = 0L
    private val COOLDOWN_MS = 75_000L
    
    // Hysteresis: Must return to TARGET for 20s to re-arm cues
    private var hysteresisEnterTime = 0L
    private val HYSTERESIS_MS = 20_000L
    private var isSystemArmed = true // Starts armed


    companion object {
        const val CHANNEL_ID = "HrServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_FOREGROUND = "ACTION_START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "ACTION_STOP_FOREGROUND"
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
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
        
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                startForegroundService()
                startScanning()
            }
            ACTION_STOP_FOREGROUND -> {
                stopForegroundService()
            }
        }
        return START_STICKY
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
        
        if (_hrState.value.connectionStatus == "Connected" || isReconnecting) return

        _hrState.update { it.copy(connectionStatus = "Scanning...", scannedDevices = emptyList()) }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            _hrState.update { it.copy(connectionStatus = "Bluetooth Off/Unavailable") }
            return
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
        _hrState.update { it.copy(connectionStatus = "Connecting to ${device.name}...") }
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }
    
    private fun attemptReconnect() {
         if (targetDeviceAddress == null) return
         
         isReconnecting = true
         val delayMs = reconnectDelay
         _hrState.update { it.copy(connectionStatus = "Reconnecting in ${delayMs/1000}s...") }
         
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
        _hrState.update { it.copy(connectionStatus = "Disconnected", bpm = 0, connectedDeviceName = null, discoveredServices = emptyList()) }
        bpmHistory.clear()
        isSystemArmed = true 
        currentZone = Zone.UNKNOWN
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectDelay = 1000L 
                isReconnecting = false
                _hrState.update { it.copy(connectionStatus = "Connected", connectedDeviceName = gatt?.device?.name) }
                gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (targetDeviceAddress != null) {
                     _hrState.update { it.copy(connectionStatus = "Disconnected (Retrying)") }
                    gatt?.close()
                    bluetoothGatt = null
                    attemptReconnect()
                } else {
                     _hrState.update { it.copy(connectionStatus = "Disconnected") }
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
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val formattedTime = sdf.format(Date(timestamp))
        val formatString = if (is16Bit) "16-bit (UINT16)" else "8-bit (UINT8)"
        
        processCoachingRules(bpm, timestamp)
        val debugInfo = getCoachingDebugInfo(timestamp)

        _hrState.update { 
            it.copy(
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
        bpmHistory.add(Pair(now, bpm))
        while (bpmHistory.isNotEmpty() && (now - bpmHistory.first.first > HISTORY_WINDOW_MS)) {
            bpmHistory.removeFirst()
        }
        if (bpmHistory.isEmpty()) return
        val avgBpm = bpmHistory.map { it.second }.average().roundToInt()
        
        val newZone = when {
            avgBpm < TARGET_LOW -> Zone.LOW
            avgBpm > TARGET_HIGH -> Zone.HIGH
            else -> Zone.TARGET
        }
        
        if (newZone != currentZone) {
            currentZone = newZone
            zoneEnterTime = now
        }
        
        val timeInCurrentZone = now - zoneEnterTime
        val cooldownRemaining = (lastCueTime + COOLDOWN_MS) - now
        
        if (cooldownRemaining <= 0) {
             if (currentZone == Zone.HIGH && timeInCurrentZone >= 30_000L) {
                 playCue("Ease off slightly.")
                 lastCueTime = now
             } else if (currentZone == Zone.LOW && timeInCurrentZone >= 45_000L) {
                 playCue("Gently increase pace.")
                 lastCueTime = now
             }
        }
    }
    
    private data class DebugInfo(val avg: Int, val zone: String, val timeInZone: String, val cooldown: String)
    
    private fun getCoachingDebugInfo(now: Long): DebugInfo {
        if (bpmHistory.isEmpty()) return DebugInfo(0, "Init", "0s", "Ready")
        val avg = bpmHistory.map { it.second }.average().roundToInt()
        val zoneStr = currentZone.name
        val timeInZone = (now - zoneEnterTime) / 1000
        val cooldownRem = ((lastCueTime + COOLDOWN_MS) - now).coerceAtLeast(0) / 1000
        val statusStr = if (cooldownRem > 0) "Cool: ${cooldownRem}s" else "Ready"
        return DebugInfo(avg, zoneStr, "${timeInZone}s", statusStr)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        disconnect()
        tts?.shutdown()
    }
}
