package ro.andi.phonebarriers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ro.andi.phonebarriers.data.AppDatabase
import ro.andi.phonebarriers.service.PathToBarrierMonitoringService

class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LauncherScreen(
                        onPermissionsGranted = {
                            startServiceAndNavigate()
                        }
                    )
                }
            }
        }
    }

    private fun startServiceAndNavigate() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@LauncherActivity)
            val optedCount = db.barrierDao().getCountAutoTriggerOpted()
            
            if (optedCount > 0) {
                if (!Utils.isServiceRunning(this@LauncherActivity, PathToBarrierMonitoringService::class.java)) {
                    val serviceIntent = Intent(this@LauncherActivity, PathToBarrierMonitoringService::class.java)
                    startForegroundService(serviceIntent)
                }
            }
            delay(500)
            startActivity(Intent(this@LauncherActivity, BarrierManagementActivity::class.java))
            finish()
        }
    }
}

@Composable
fun LauncherScreen(onPermissionsGranted: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var permissionStep by remember { mutableStateOf(0) }

    val mainPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocation = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        
        if (fineLocation || coarseLocation) {
            permissionStep = 1 // Move to background location or next
        }
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        permissionStep = 2 // Move to battery optimization or finish
    }

    LaunchedEffect(permissionStep) {
        when (permissionStep) {
            0 -> {
                val permissions = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                
                val allGranted = permissions.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                
                if (allGranted) {
                    permissionStep = 1
                } else {
                    mainPermissionsLauncher.launch(permissions.toTypedArray())
                }
            }
            1 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        permissionStep = 2
                    }
                } else {
                    permissionStep = 2
                }
            }
            2 -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
                onPermissionsGranted()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.sv_fontawesome_road_barrier_s_f),
                contentDescription = "Barrier Icon",
                modifier = Modifier.size(64.dp)
            )
            Image(
                painter = painterResource(id = R.drawable.sv_fontawesome_brain_solid_full),
                contentDescription = "Brain Icon",
                modifier = Modifier.size(64.dp)
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text(text = "Initializing Phone Barriers...", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        CircularProgressIndicator()
    }
}


/*
    a new path-to-barrier-monitoring service:
        - started in the background if at least one barrier has auto-trigger opted
        - [STARTING] it collects speed for 5 seconds at 1Hz
            and checks if in range to reach any barrier's radius at 2x the maximum speed collected.
            if in range then transitions to [ACTIVE] state. if not then transitions to [LIGHT-SLEEP] state.
        - [ACTIVE] it collects data (GPS & Accelerometer) at 1Hz.
            if in range of any barrier's radius:
                - activates the widget and fills it with the closest barrier's info.
                - it checks medoids paths for a match to auto-trigger.
            if speed and acceleration are 0 for 10 seconds then it transitions to [LIGHT-SLEEP] state.
            has stop and sleep buttons.
        - [LIGHT-SLEEP] it sleeps for 10 seconds and then collects speed for 5 seconds at 1Hz
            and checks if in range to reach any barrier's radius at 2x the maximum speed collected.
            if in range then transitions to [ACTIVE] state. if not then transitions to [DEEP-SLEEP] state.
            has stop & wake buttons.
        - [DEEP-SLEEP] it sleeps for 100 seconds and then collects speed for 5 seconds at 1Hz
            and checks if in range to reach any barrier's radius at 2x the maximum speed collected.
            if in range then transitions to [ACTIVE] state. if not then after 30 seconds it registers a significant-motion-sensor.
            has stop & wake buttons.

    how can the device's motion wake the service? (SensorEventListener.TYPE_SIGNIFICANT_MOTION)

 */