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
import java.util.UUID

// simple data class to hold the state
data class HrState(
    val connectionStatus: String = "Disconnected",
    val bpm: Int = 0,
    val lastUpdateTimestamp: Long = 0, // System.currentTimeMillis()
    val connectedDeviceName: String? = null
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

    companion object {
        const val CHANNEL_ID = "HrServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_FOREGROUND = "ACTION_START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "ACTION_STOP_FOREGROUND"
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
            .setSmallIcon(android.R.drawable.ic_menu_compass) // meaningful icon in real app
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

    private fun startScanning() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            _hrState.update { it.copy(connectionStatus = "Permission Missing") }
            return
        }
        
        _hrState.update { it.copy(connectionStatus = "Scanning...") }

        // Simple scan for first device with HR service - in real app, filter for specific UUIDs
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            _hrState.update { it.copy(connectionStatus = "Bluetooth Off/Unavailable") }
            return
        }

        scanner.startScan(object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult?) {
                result?.device?.let { device ->
                    // For simplicity, connect to the first device found. 
                    // ideally check for advertising data containing HR UUID
                     if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                         // Check meaningful name or filter here
                         if (device.name != null) { // connect to non-null name devices
                             scanner.stopScan(this)
                             connectToDevice(device)
                         }
                     }
                }
            }
            
            override fun onScanFailed(errorCode: Int) {
                _hrState.update { it.copy(connectionStatus = "Scan Failed: $errorCode") }
            }
        })
    }
    
    private fun stopScanning() {
         if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
             bluetoothAdapter?.bluetoothLeScanner?.stopScan(object: android.bluetooth.le.ScanCallback() {})
         }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

        _hrState.update { it.copy(connectionStatus = "Connecting to ${device.name}...") }
        
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private fun disconnect() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        }
        _hrState.update { it.copy(connectionStatus = "Disconnected", bpm = 0) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (ActivityCompat.checkSelfPermission(this@HrForegroundService, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _hrState.update { it.copy(connectionStatus = "Connected", connectedDeviceName = gatt?.device?.name) }
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _hrState.update { it.copy(connectionStatus = "Disconnected") }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
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
             // Use value directly for Android 13+ (part of the callback signature change but for compatibility we use the passed value or characteristic.value)
             // On older API levels, the `value` parameter might not be present in `onCharacteristicChanged` (it was added in API 33).
             // However, since we are targeting Android 16 (API 35+), we use the signature with `value`.
             
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                handleHeartRate(value) // use the ByteArray passed
            }
        }
        
        // For compatibility if compiling against older SDK source, though project says Android 16.
        // The signature above with `value` is for API 33+. 
    }

    private fun handleHeartRate(data: ByteArray) {
        if (data.isEmpty()) return
        
        val flag = data[0].toInt()
        val format = if ((flag and 0x01) != 0) {
            BluetoothGattCharacteristic.FORMAT_UINT16
        } else {
            BluetoothGattCharacteristic.FORMAT_UINT8
        }
        
        // Parsing manually since getIntValue might not be available on ByteArray directly without wrapping
        var bpm = 0
        if (format == BluetoothGattCharacteristic.FORMAT_UINT16) {
             if (data.size >= 3) {
                 bpm = ((data[2].toInt() and 0xFF) shl 8) + (data[1].toInt() and 0xFF)
             }
        } else {
             if (data.size >= 2) {
                 bpm = data[1].toInt() and 0xFF
             }
        }

        _hrState.update { 
            it.copy(
                bpm = bpm, 
                lastUpdateTimestamp = System.currentTimeMillis()
            ) 
        }
        
        // Update notification
        val notification = createNotification("HR: $bpm BPM")
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        disconnect()
    }
}
