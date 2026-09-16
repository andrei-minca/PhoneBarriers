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
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.andi.phonebarriers.data.AppDatabase
import ro.andi.phonebarriers.service.PathToBarrierMonitoringService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ro.andi.phonebarriers.ui.theme.PhoneBarriersTheme
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import ro.andi.phonebarriers.service.RecurrentNativeWorker

class AdminActivity : ComponentActivity() {

    private val VALUE_BARRIER_NAME = BuildConfig.TEST_BARRIER_SHORTNAME // or dynamic update if multiple barriers

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { permissions ->

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
    // Inside AdminActivity class
    private var isLoading by mutableStateOf(false)

    private var isServiceActive by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

//        // Show message from C++ without blocking UI thread
//        lifecycleScope.launch(Dispatchers.Default) {
//            val message = NativeLib.stringFromJNI()
//            withContext(Dispatchers.Main) {
//                Toast.makeText(this@AdminActivity, message, Toast.LENGTH_SHORT).show()
//            }
//        }

        // trigger permission request
        checkAndStartPermissions()

        // Check if app was opened via the notification button
        handleIntent(intent)

        setContent {
            PhoneBarriersTheme {
                //var isLoading by remember { mutableStateOf(false) }

                val csvPickerLauncherLnLMotion = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    uri?.let { loadMotionDataFromCsv(it) }
                }
                val csvPickerLauncherBarrierList = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    uri?.let { loadBarrierListFromCsv(it) }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
//                        Button(
//                            enabled = !isLoading,
//                            onClick = { performLiftAction() },
//                            modifier = Modifier.size(200.dp, 60.dp),
//                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
//                        ) {
//                            if (isLoading) {
//                                CircularProgressIndicator(
//                                    color = Color.White,
//                                    modifier = Modifier.size(24.dp)
//                                )
//                            } else {
//                                Text("Lift "+VALUE_BARRIER_NAME)
//                            }
//                        }

                        // --- SPACE ---
                        Spacer(modifier = Modifier.height(48.dp))

                        Button(
                            onClick = { toggleTrackingService() },
                            modifier = Modifier.size(300.dp, 60.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isServiceActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(if (isServiceActive) "Close Monitoring Service" else "Start Monitoring Service")
                        }

                        Spacer(modifier = Modifier.height(72.dp))

                        // --- SHARE CSV BUTTONS ---
                        Button(
                            onClick = { shareBarrierListCsv(this@AdminActivity) },
                            modifier = Modifier.size(300.dp, 60.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Share Barrier List (CSV)")
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Button(
                            onClick = { shareLnLMotionCsv(this@AdminActivity) },
                            modifier = Modifier.size(300.dp, 60.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Share LnL Motion Data (CSV)")
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Button(
                            onClick = { shareLiftEventsCsv(this@AdminActivity) },
                            modifier = Modifier.size(300.dp, 60.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Share Lift Events (CSV)")
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Button(
                            onClick = { shareMedoidCsv(this@AdminActivity) },
                            modifier = Modifier.size(300.dp, 60.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Share Medoid Data (CSV)")
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { triggerRecurrentWorker() },
                            modifier = Modifier.size(300.dp, 60.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        ) {
                            Text("[Classify & Find Medoids]")
                        }

                        Spacer(modifier = Modifier.height(24.dp))


                        Button(
                            onClick = { csvPickerLauncherBarrierList.launch("text/comma-separated-values") },
                            modifier = Modifier.size(333.dp, 60.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Replace Barrier List from CSV")
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Button(
                            onClick = { csvPickerLauncherLnLMotion.launch("text/comma-separated-values") },
                            modifier = Modifier.size(333.dp, 60.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Replace LnL Motion Data from CSV")
                        }

                        Spacer(modifier = Modifier.height(24.dp))
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
    // Update this state whenever the activity is visible
    override fun onResume() {
        super.onResume()
        isServiceActive = Utils.isServiceRunning(this,PathToBarrierMonitoringService::class.java)
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
                data = Uri.parse("package:$packageName")
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

//    private fun performLiftAction() {
//        Log.d("AdminActivity", "Button clicked!")
//        if (isLoading) {
//            Log.w("AdminActivity", "Still performing an action, clicked ignored!")
//            return
//        }
//        isLoading = true // Start loading
//
//        CallRepository.triggerOneRing(
//            CallRepository.KEY_TO,
//            CallRepository.KEY_FROM
//        )
//        { /* handle success/fail if needed */ }
//
//        onTriggerButtonPressed(this@AdminActivity)
//
//        lifecycleScope.launch {
//            delay(5000) // Non-blocking delay
//            isLoading = false
//        }
//    }
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "ACTION_TRIGGER_LIFT") {

            // Clear the action so it doesn't trigger again on rotation
            intent.action = null

            Toast.makeText(this, "Triggering Lift from Notification...", Toast.LENGTH_SHORT).show()

            // todo : still keep it?
        }
    }

    private fun startTrackingService() {
        // Start the Monitoring Service
        val serviceIntent = Intent(this, PathToBarrierMonitoringService::class.java)
        startForegroundService(serviceIntent)
        isServiceActive = true
    }

    fun shareMedoidCsv(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val data = AppDatabase.getDatabase(context).medoidDao().getAllMedoids()
            val csvHeader = "BarrierId,ClusterId,SessionId,Time,Distance,DeltaHeading,Speed,Accel\n"
            val csvRows = data.joinToString("\n") {
                "${it.barrierId},${it.clusterId},${it.sessionId}," +
                        "${it.timestamp}," +
                        "${it.distance},${it.deltaHeading},${it.speed},${it.acceleration}"
            }

            val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            val dateStr = sdf.format(Date())
            val file = File(context.cacheDir, "medoid_data_${dateStr}_${System.currentTimeMillis()}.csv")
            file.writeText(csvHeader + csvRows)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Medoid Data"))
        }
    }

    fun shareBarrierListCsv(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val data = AppDatabase.getDatabase(context).barrierDao().getAll()
            val csvHeader = "ShortName,Description,Color,PhoneNumberTo,PhoneNumberFrom,Latitude,Longitude,Radius,IsEnabledAutoTrigger,HasOptedAutoTrigger\n"
            val csvRows = data.joinToString("\n") {
                CsvUtils.escapeCsvField(it.shortName) + ","+ CsvUtils.escapeCsvField(it.description) + "," +
                        "${it.color},${it.phoneNumberTo},${it.phoneNumberFrom}," +
                        "${it.latitude},${it.longitude},${it.radius},${it.isEnabledAutoTrigger},${it.hasOptedAutoTrigger}"
            }

            val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            val dateStr = sdf.format(Date())
            val file = File(context.cacheDir, "barrier_list_${dateStr}_${System.currentTimeMillis()}.csv")
            file.writeText(csvHeader + csvRows)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Barrier List"))
        }
    }

    fun shareLnLMotionCsv(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val data = AppDatabase.getDatabase(context).motionDao().getData()
            val csvHeader = "BarrierId,SessionId,Time,Accuracy,Lat,Lng,Alt,Speed,Accel\n"
            val csvRows = data.joinToString("\n") {
                "${it.barrierId},${it.sessionId}," +
                        "${it.timestamp}," +
                        "${it.accuracy},${it.lat},${it.lng},${it.alt}," +
                        "${it.speed},${it.acceleration}"
            }

            val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            val dateStr = sdf.format(Date())
            val file = File(context.cacheDir, "motion_data_${dateStr}_${System.currentTimeMillis()}.csv")
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

    fun shareLiftEventsCsv(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val data = AppDatabase.getDatabase(context).liftEventDao().getAllEvents()
            val csvHeader = "Id,BarrierId,BarrierName,Time,Source,Outcome,Reason,Lat,Lng,Alt,Speed,Accel\n"
            val csvRows = data.joinToString("\n") {
                "${it.id},${it.barrierId},${CsvUtils.escapeCsvField(it.barrierName)}," +
                        "${it.timestamp}," +
                        "${it.source},${it.outcome},${CsvUtils.escapeCsvField(it.reason ?: "")}," +
                        "${it.latitude},${it.longitude},${it.altitude}," +
                        "${it.speed},${it.acceleration}"
            }

            val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            val dateStr = sdf.format(Date())
            val file = File(context.cacheDir, "lift_events_${dateStr}_${System.currentTimeMillis()}.csv")
            file.writeText(csvHeader + csvRows)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Lift Events"))
        }
    }

//    fun onTriggerButtonPressed(context: Context) {
//        val sessionId = System.currentTimeMillis()
//        val db = AppDatabase.getDatabase(context)
//        val dao = db.motionDao()
//
//        CoroutineScope(Dispatchers.IO).launch {
//            // 1. Tag the 30 points (last 30 seconds) currently in the 'buffer'
//            // We look for points with null sessionIds from the last 30900ms
//            val threshold = System.currentTimeMillis() - 30900
//
//            dao.tagRecentPoints(sessionId, 0, threshold)
//
//            // 2. Optional: Cleanup very old null data to keep the DB small
//            dao.cleanOldUnusedData(System.currentTimeMillis() - 60000)
//        }
//    }

    private fun toggleTrackingService() {
        val serviceIntent = Intent(this, PathToBarrierMonitoringService::class.java)
        if (isServiceActive) {
            stopService(serviceIntent)
            isServiceActive = false
            Toast.makeText(this, "Monitoring Stopped", Toast.LENGTH_SHORT).show()
        } else {
            // Ensure we have permissions before starting
            checkAndStartPermissions()
            // Note: checkAndStartPermissions calls startTrackingService()
            // which sets isServiceActive = true
        }
    }

    private fun triggerRecurrentWorker() {
        val workRequest = OneTimeWorkRequestBuilder<RecurrentNativeWorker>().build()
        WorkManager.getInstance(this).enqueue(workRequest)
        Toast.makeText(this, "Analysis Task Enqueued", Toast.LENGTH_SHORT).show()
    }

    private fun loadBarrierListFromCsv(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val reader = inputStream?.bufferedReader()
                val lines = reader?.readLines() ?: emptyList()
                
                if (lines.isEmpty()) return@launch

                val barriers = mutableListOf<ro.andi.phonebarriers.data.Barrier>()
                
                // Skip header: ShortName,Description,Color,PhoneNumberTo,PhoneNumberFrom,Latitude,Longitude,Radius,IsEnabledAutoTrigger,HasOptedAutoTrigger
                lines.drop(1).forEach { line ->
                    val columns = //line.split(",")
                        CsvUtils.parseCsvLine(line)
                    if (columns.size >= 10) {
                        barriers.add(
                            ro.andi.phonebarriers.data.Barrier(
                                shortName = columns[0],
                                description = columns[1],
                                color = columns[2].toInt(),
                                phoneNumberTo = columns[3],
                                phoneNumberFrom = columns[4],
                                latitude = columns[5].toDouble(),
                                longitude = columns[6].toDouble(),
                                radius = columns[7].toFloat(),
                                isEnabledAutoTrigger = columns[8].toBoolean(),
                                hasOptedAutoTrigger = columns[9].toBoolean()
                            )
                        )
                    }
                }

                val db = AppDatabase.getDatabase(this@AdminActivity)
                db.barrierDao().clearAll()
                db.barrierDao().insertAll(barriers)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdminActivity, "Successfully loaded ${barriers.size} barriers", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("AdminActivity", "Error loading CSV", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdminActivity, "Failed to load CSV: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadMotionDataFromCsv(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val reader = inputStream?.bufferedReader()
                val lines = reader?.readLines() ?: emptyList()

                if (lines.isEmpty()) return@launch

                val points = mutableListOf<ro.andi.phonebarriers.data.MotionPoint>()

                // Skip header: BarrierId,SessionId,Time,Accuracy,Lat,Lng,Alt,Speed,Accel
                lines.drop(1).forEach { line ->
                    val columns = line.split(",")
                    if (columns.size >= 9) {
                        points.add(
                            ro.andi.phonebarriers.data.MotionPoint(
                                barrierId = columns[0].toIntOrNull(),
                                sessionId = columns[1].toLongOrNull(),
                                timestamp = columns[2].toLong(),
                                accuracy = columns[3].toFloat(),
                                lat = columns[4].toDouble(),
                                lng = columns[5].toDouble(),
                                alt = columns[6].toDouble(),
                                speed = columns[7].toFloat(),
                                acceleration = columns[8].toFloat()
                            )
                        )
                    }
                }

                val db = AppDatabase.getDatabase(this@AdminActivity)
                db.motionDao().clearAll()
                db.motionDao().insertAll(points)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdminActivity, "Successfully loaded ${points.size} points", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("AdminActivity", "Error loading CSV", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdminActivity, "Failed to load CSV: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
