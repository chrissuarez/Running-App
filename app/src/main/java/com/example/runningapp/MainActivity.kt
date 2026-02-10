package com.example.runningapp

import android.Manifest
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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
                    // Pass the expected stateflow if bound, else null or empty
                    val serviceState = hrService?.hrState?.collectAsState(initial = HrState())
                    
                    // We need a way to trigger recomposition when service binds
                    // A simple way is to checking periodically or using a MutableState for the service itself
                    // For this scaffold, we'll use a producesState or just rely on the fact that once bound,
                    // we can pass the flow.
                    
                    var boundService by remember { mutableStateOf<HrForegroundService?>(null) }
                    
                    // Poll for service binding (simple solution for scaffold)
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
    onStopService: () -> Unit
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
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Heart Rate Monitor", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(text = "Status: ${state.connectionStatus}")
        if (state.connectedDeviceName != null) {
            Text(text = "Device: ${state.connectedDeviceName}")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(text = "${state.bpm} BPM", style = MaterialTheme.typography.displayLarge)
        Text(text = "Last update: ${timeSinceUpdate}s ago")
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onStartService) {
                Text("Start Service")
            }
            Button(onClick = onStopService, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Stop Service")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(onClick = onRequestPermissions) {
            Text("Request Permissions")
        }
    }
}
