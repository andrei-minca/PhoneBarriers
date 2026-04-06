package ro.andi.phonebarriers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ro.andi.phonebarriers.data.AppDatabase
import ro.andi.phonebarriers.service.TrackingService
import java.io.File
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),secondary = Color(0xFFCCC2DC),
    tertiary = Color(0xFFEFB8C8),
    background = Color(0xFF1C1B1F),
    surface = Color(0xFF1C1B1F),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6650a4),
    secondary = Color(0xFF625b71),
    tertiary = Color(0xFF7D5260),
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
)

class MainActivity : ComponentActivity() {

    private val VALUE_BARRIER_NAME = BuildConfig.TEST_BARRIER_NAME // or dynamic update if multiple barriers

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        val notificationsGranted =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true


        if (fineLocationGranted || coarseLocationGranted
            || notificationsGranted) {
            // 2. Only start the service AFTER permissions are granted
            startTrackingService()
        } else {
            Toast.makeText(this,
                "Notifications & Location permissions required!!!",
                Toast.LENGTH_LONG).show()
        }
    }
    // Inside MainActivity class
    private var isLoading by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // trigger permission request
        checkAndStartPermissions()

        // Check if app was opened via the notification button
        handleIntent(intent)

        setContent {
            // Detect if the system is in Dark Mode
            val darkTheme = isSystemInDarkTheme()
            // Select the appropriate color scheme
            val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

            MaterialTheme(colorScheme = colorScheme) {
                //var isLoading by remember { mutableStateOf(false) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Button(
                            enabled = !isLoading,
                            onClick = { performLiftAction() },
                            modifier = Modifier.size(200.dp, 60.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Text("Lift "+VALUE_BARRIER_NAME)
                            }
                        }

                        // --- ADDED SPACE ---
                        Spacer(modifier = Modifier.height(48.dp))

                        // --- NEW SHARE CSV BUTTON ---
                        Button(
                            onClick = { shareSessionCsv(this@MainActivity) },
                            modifier = Modifier.size(300.dp, 60.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Share Trigger Motion Data (CSV)")
                        }
                    }
                }

            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Important to update the intent
        handleIntent(intent)
    }

    private fun checkAndStartPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Check Location
        val hasLocation =
            ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Check Notifications (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val hasNotifications =
                ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!hasNotifications) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }


        if (permissionsToRequest.isEmpty()) {
            checkBatteryOptimization()
            startTrackingService()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
    private fun checkBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            // We cannot use the launcher here. We must open the system settings.
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Some devices might not support the direct intent
                Log.w("BatteryOptimization", "Failed to open battery optimization settings", e)
                Toast.makeText(this, "Failed to open battery optimization settings", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performLiftAction() {
        Log.d("MainActivity", "Button clicked!")
        if (isLoading) {
            Log.w("MainActivity", "Still performing an action, clicked ignored!")
            return
        }
        isLoading = true // Start loading

        CallRepository.triggerOneRing(
            CallRepository.KEY_TO,
            CallRepository.KEY_FROM
        )
        { /* handle success/fail if needed */ }

        onTriggerButtonPressed(this@MainActivity)

        lifecycleScope.launch {
            delay(5000) // Non-blocking delay
            isLoading = false
        }
    }
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "ACTION_TRIGGER_LIFT") {
            performLiftAction()
            // Clear the action so it doesn't trigger again on rotation
            intent.action = null
            Toast.makeText(this, "Triggering Lift from Notification...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTrackingService() {
        // Start the Tracking Service
        val serviceIntent = Intent(this, TrackingService::class.java)
        startForegroundService(serviceIntent)
    }

    fun shareSessionCsv(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val data = AppDatabase.getDatabase(context).motionDao().getData()
            val csvHeader = "Time,SessionId,Accuracy,Lat,Lng,Alt,Speed(m/s),Accel(m/s2)\n"
            val csvRows = data.joinToString("\n") {
                "${it.timestamp},${it.sessionId},${it.accuracy},${it.lat},${it.lng},${it.alt},${it.speed},${it.acceleration}"
            }

            val file = File(context.cacheDir, "motion_data_${System.currentTimeMillis()}.csv")
            file.writeText(csvHeader + csvRows)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Motion Data"))
        }
    }

    fun onTriggerButtonPressed(context: Context) {
        val sessionId = System.currentTimeMillis()
        val db = AppDatabase.getDatabase(context)
        val dao = db.motionDao()

        CoroutineScope(Dispatchers.IO).launch {
            // 1. Tag the 30 points (last 30 seconds) currently in the 'buffer'
            // We look for points with null sessionIds from the last 30900ms
            val threshold = System.currentTimeMillis() - 30900

            dao.tagRecentPoints(sessionId, threshold)

            // 2. Optional: Cleanup very old null data to keep the DB small
            dao.cleanOldUnusedData(System.currentTimeMillis() - 60000)
        }
    }
}