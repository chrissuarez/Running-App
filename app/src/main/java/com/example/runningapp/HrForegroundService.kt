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
import android.os.Binder
import android.os.Build
import android.os.IBinder
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
import java.util.Locale
import java.util.UUID

// simple data class to hold the state
data class HrState(
    val connectionStatus: String = "Disconnected",
    val bpm: Int = 0,
    val lastUpdateTimestamp: Long = 0,
    val connectedDeviceName: String? = null,
    val scannedDevices: List<BluetoothDevice> = emptyList(),
    val discoveredServices: List<String> = emptyList(),
    val lastPacketTimeFormatted: String = "--:--:--.---",
    val dataBits: String = "Unknown"
)

class HrForegroundService : Service() {

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

        scanner.startScan(object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
                result?.device?.let { device ->
                    // Add device if not already in list
                    _hrState.update { currentState ->
                        val currentList = currentState.scannedDevices
                        if (currentList.none { it.address == device.address }) {
                            // Check for permission to read name
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
        })
    }
    
    fun connectToDevice(address: String) {
        stopScanning()
        targetDeviceAddress = address
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        connectToDevice(device)
    }
    
    private fun stopScanning() {
         if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
             bluetoothAdapter?.bluetoothLeScanner?.stopScan(object: android.bluetooth.le.ScanCallback() {})
         }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

        isReconnecting = false
        _hrState.update { it.copy(connectionStatus = "Connecting to ${device.name}...") }
        
        // AutoConnect = false for initial connection is usually better/faster
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }
    
    private fun attemptReconnect() {
         if (targetDeviceAddress == null) return
         
         isReconnecting = true
         val delayMs = reconnectDelay
         _hrState.update { it.copy(connectionStatus = "Reconnecting in ${delayMs/1000}s...") }
         
         serviceScope.launch {
             delay(delayMs)
             // Increase backoff: 1s, 2s, 4s, 8s, 16s, 30s (cap)
             reconnectDelay = (reconnectDelay * 2).coerceAtMost(30000L)
             
             if (targetDeviceAddress != null) {
                 connectToDevice(targetDeviceAddress!!)
             }
         }
    }

    fun disconnect() {
        targetDeviceAddress = null // Prevent auto-reconnect
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
        _hrState.update { it.copy(connectionStatus = "Disconnected", bpm = 0, connectedDeviceName = null, discoveredServices = emptyList()) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectDelay = 1000L // Reset backoff
                isReconnecting = false
                _hrState.update { it.copy(connectionStatus = "Connected", connectedDeviceName = gatt?.device?.name) }
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (targetDeviceAddress != null) {
                    // Unexpected disconnect (or failed connect), try to reconnect
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

        _hrState.update { 
            it.copy(
                bpm = bpm, 
                lastUpdateTimestamp = timestamp,
                lastPacketTimeFormatted = formattedTime,
                dataBits = formatString
            ) 
        }
        
        // Rate limit notification updates (every 5 seconds)
        if (System.currentTimeMillis() - lastNotificationTime > 5000) {
            lastNotificationTime = System.currentTimeMillis()
            val notification = createNotification("HR: $bpm BPM")
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        disconnect()
    }
}
