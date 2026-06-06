package com.mapmate.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mapmate.app.telemetry.TelemetryService

class MainActivity : ComponentActivity() {

    // State để hiện trên UI
    private var isServiceRunning by mutableStateOf(false)

    // Launcher xin permission. Đây là cách hiện đại của Android (Activity Result API).
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineLocationGranted) {
            startTelemetryService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    MainScreen(
                        isServiceRunning = isServiceRunning,
                        onStartClick = { requestPermissionsAndStart() },
                        onStopClick = { stopTelemetryService() }
                    )
                }
            }
        }
    }

    /**
     * Xin location permission. Nếu đã có thì start service luôn.
     */
    private fun requestPermissionsAndStart() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Android 13+ cần thêm POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Kiểm tra đã có chưa
        val allGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startTelemetryService()
        } else {
            // Chưa có → bật dialog xin
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    /**
     * Start service. Dùng startForegroundService() trên Android 8+.
     */
    private fun startTelemetryService() {
        val intent = Intent(this, TelemetryService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServiceRunning = true
    }

    private fun stopTelemetryService() {
        val intent = Intent(this, TelemetryService::class.java)
        stopService(intent)
        isServiceRunning = false
    }
}

/**
 * UI đơn giản với 1 nút Start/Stop.
 */
@Composable
fun MainScreen(
    isServiceRunning: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "MapMate Telemetry",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isServiceRunning) "✅ Tracking ON" else "⏸️ Tracking OFF",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(32.dp))

        if (!isServiceRunning) {
            Button(onClick = onStartClick) {
                Text("Start Tracking")
            }
        } else {
            Button(onClick = onStopClick) {
                Text("Stop Tracking")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Open Logcat in Android Studio\nand filter by 'TelemetryService'\nto see location updates",
            style = MaterialTheme.typography.bodySmall
        )
    }
}