package ro.andi.phonebarriers

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ro.andi.phonebarriers.data.AppDatabase
import ro.andi.phonebarriers.data.Barrier
import ro.andi.phonebarriers.ui.BarrierForm
import ro.andi.phonebarriers.ui.BarrierListItem

class BarrierManagementActivity : ComponentActivity() {
    private val viewModel: BarrierManagementViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Seed DB if empty
        seedDatabaseIfNeeded()

        setContent {
            MaterialTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    if (permissions.values.any { it }) {
                        viewModel.startLocationUpdates()
                    }
                }

                LaunchedEffect(Unit) {
                    val permissions = arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    val allGranted = permissions.all { ContextCompat.checkSelfPermission(this@BarrierManagementActivity, it) == PackageManager.PERMISSION_GRANTED }
                    if (!allGranted) {
                        permissionLauncher.launch(permissions)
                    } else {
                        viewModel.startLocationUpdates()
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BarrierManagementScreen(
                        viewModel = viewModel,
                        onOpenAdmin = {
                            startActivity(Intent(this, AdminActivity::class.java))
                        },
                        onLift = { barrier ->
                            viewModel.incrementLiftCount(barrier)
                            // A. Trigger the API/Call (location: anywhere)
                            CallRepository.triggerOneRing(
                                barrier.phoneNumberTo,
                                barrier.phoneNumberFrom) { success ->
                                runOnUiThread {
                                    Toast.makeText(this, if (success) "Lift triggered for ${barrier.shortName}" else "Failed to trigger lift", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onLiftNLearn = { barrier ->
                            viewModel.incrementLiftNLearnCount(barrier)
                            // A. Trigger the API/Call (location: should be inside radius
                            CallRepository.triggerOneRing(
                                barrier.phoneNumberTo,
                                barrier.phoneNumberFrom) { success ->
                                runOnUiThread {
                                    Toast.makeText(this, if (success) "Lift & Lear triggered for ${barrier.shortName}" else "Failed to trigger lift & learn", Toast.LENGTH_SHORT).show()
                                }
                            }
                            // B. Tag recent motion points (Same logic as Activity)
                            CoroutineScope(Dispatchers.IO).launch {
                                run {
                                    val sessionId = System.currentTimeMillis()
                                    val db = AppDatabase.getDatabase(this@BarrierManagementActivity)
                                    val dao = db.motionDao()

                                    // Tag points from the last 30.9 seconds that don't have a sessionId yet
                                    val threshold = System.currentTimeMillis() - 30900
                                    dao.tagRecentPoints(sessionId, 0, threshold)

                                    // Optional: Clean up very old data (> 1 minute)
                                    dao.cleanOldUnusedData(System.currentTimeMillis() - 60000)
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    private fun seedDatabaseIfNeeded() {
        viewModel.seedDatabaseIfNeeded(
            shortName = BuildConfig.TEST_BARRIER_SHORTNAME,
            description = BuildConfig.TEST_BARRIER_DESCRIPTION,
            color = java.lang.Long.decode(BuildConfig.TEST_BARRIER_COLOR).toInt(),
            phoneTo = BuildConfig.TEST_BARRIER_PHONE_NUMBER_TO,
            phoneFrom = BuildConfig.TEST_BARRIER_PHONE_NUMBER_FROM,
            lat = BuildConfig.TEST_BARRIER_LATITUDE.toDouble(),
            lng = BuildConfig.TEST_BARRIER_LONGITUDE.toDouble(),
            radius = BuildConfig.TEST_BARRIER_RADIUS.toFloat()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarrierManagementScreen(
    viewModel: BarrierManagementViewModel,
    onOpenAdmin: () -> Unit,
    onLift: (Barrier) -> Unit,
    onLiftNLearn: (Barrier) -> Unit
) {
    val barriers by viewModel.barriers.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    var showForm by remember { mutableStateOf(value = false) }
    var editingBarrier by remember { mutableStateOf<Barrier?>(null) }

    val isShowingForm = showForm || editingBarrier != null

    Scaffold(
        topBar = {
            if (!isShowingForm) {
                TopAppBar(
                    title = { Text("Phone Barriers") },
                    actions = {
                        IconButton(onClick = onOpenAdmin) {
                            Icon(Icons.Default.Settings, contentDescription = "Admin Settings")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!isShowingForm) {
                FloatingActionButton(
                    onClick = {
                        editingBarrier = null
                        showForm = true
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Barrier")
                }
            }
        }
    ) { padding ->
        if (isShowingForm) {
            Box(modifier = Modifier.padding(padding)) {
                BarrierForm(
                    barrier = editingBarrier,
                    onSave = {
                        if (editingBarrier != null) {
                            viewModel.updateBarrier(it)
                        } else {
                            viewModel.addBarrier(it)
                        }
                        showForm = false
                        editingBarrier = null
                    },
                    onCancel = {
                        showForm = false
                        editingBarrier = null
                    }
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                items(barriers) { barrier ->
                    BarrierListItem(
                        barrier = barrier,
                        currentLocation = currentLocation,
                        onEdit = { editingBarrier = barrier },
                        onDelete = { viewModel.deleteBarrier(barrier) },
                        onLift = { onLift(barrier) },
                        onLiftNLearn = { onLiftNLearn(barrier) },
                        onToggleAutoTrigger = { enabled ->
                            viewModel.toggleAutoTrigger(barrier, enabled)
                        }
                    )
                }
            }
        }
    }
}
