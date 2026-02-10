package com.example.runningapp

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {

    private var hrService: HrForegroundService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as HrForegroundService.LocalBinder
            hrService = binder.getService()
            isBound = true
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

        // Bind to the service
        Intent(this, HrForegroundService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val serviceState = hrService?.hrState?.collectAsState(initial = HrState())
                    
                    var boundService by remember { mutableStateOf<HrForegroundService?>(null) }
                    
                    LaunchedEffect(Unit) {
                        while(true) {
                            if (isBound && hrService != null) {
                                boundService = hrService
                            }
                            delay(500)
                        }
                    }

                    MainScreen(
                        hrService = boundService,
                        onRequestPermissions = { checkAndRequestPermissions() },
                        onStartService = {
                            val intent = Intent(this, HrForegroundService::class.java).apply {
                                action = HrForegroundService.ACTION_START_FOREGROUND
                            }
                            startService(intent)
                        },
                        onStopService = {
                             val intent = Intent(this, HrForegroundService::class.java).apply {
                                action = HrForegroundService.ACTION_STOP_FOREGROUND
                            }
                            startService(intent)
                        },
                        onConnectToDevice = { address ->
                            boundService?.connectToDevice(address)
                        }
                    )
                }
            }
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
             permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missing = permissions.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }
        
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}

@Composable
fun MainScreen(
    hrService: HrForegroundService?, 
    onRequestPermissions: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onConnectToDevice: (String) -> Unit
) {
    val state = hrService?.hrState?.collectAsState()?.value ?: HrState()
    
    // Calculate age of last update
    var timeSinceUpdate by remember { mutableStateOf(0L) }
    
    LaunchedEffect(state.lastUpdateTimestamp) {
        while(true) {
            if (state.lastUpdateTimestamp > 0) {
                timeSinceUpdate = (System.currentTimeMillis() - state.lastUpdateTimestamp) / 1000
            } else {
                timeSinceUpdate = 0
            }
            delay(1000)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Heart Rate Monitor", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(text = "Status: ${state.connectionStatus}")
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onStartService) {
                Text("Scan / Start")
            }
            Button(onClick = onStopService, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Stop / Disconnect")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermissions) {
            Text("Request Permissions")
        }
        
        Spacer(modifier = Modifier.height(24.dp))

        if (state.connectionStatus == "Scanning...") {
            Text("Scanned Devices:", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color.LightGray.copy(alpha = 0.2f))
            ) {
                items(state.scannedDevices) { device ->
                    DeviceListItem(device = device, onClick = { onConnectToDevice(device.address) })
                }
            }
        } else if (state.connectionStatus == "Connected" || state.connectionStatus.startsWith("Connecting")) {
             if (state.connectedDeviceName != null) {
                Text(text = "Device: ${state.connectedDeviceName}", style = MaterialTheme.typography.titleMedium)
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(text = "${state.bpm} BPM", style = MaterialTheme.typography.displayLarge)
            Text(text = "Last update: ${timeSinceUpdate}s ago")
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("Debug Info (Services):", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Column(modifier = Modifier.fillMaxWidth().height(200.dp).verticalScroll(rememberScrollState()).background(Color.Black.copy(alpha = 0.05f)).padding(8.dp)) {
                 state.discoveredServices.forEach { uuid ->
                     Text(text = uuid, fontSize = 12.sp)
                 }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
            Text("Service not running or disconnected.")
            Spacer(modifier = Modifier.weight(1f))
        }
    }
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
