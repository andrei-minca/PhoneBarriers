package ro.andi.phonebarriers

import android.app.Application
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ro.andi.phonebarriers.data.AppDatabase
import ro.andi.phonebarriers.data.Barrier
import ro.andi.phonebarriers.data.BarrierWithMedoidCount
import ro.andi.phonebarriers.data.LiftSource
import ro.andi.phonebarriers.service.PathToBarrierMonitoringService
import android.content.Intent

data class BarrierStats(
    val failedCount: Int,
    val successButton: Int,
    val successLiftNLearn: Int,
    val successWidget: Int,
    val successAutoTrigger: Int
)

class BarrierManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "BarrierVM"
    private val db = AppDatabase.getDatabase(application)
    private val barrierDao = db.barrierDao()
    private val liftEventDao = db.liftEventDao()
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)

    val barriers: StateFlow<List<BarrierWithMedoidCount>> = barrierDao.getAllWithMedoidCountFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val loc = locationResult.lastLocation
            Log.d(TAG, "onLocationResult: $loc")
            _currentLocation.value = loc
        }
    }

    init {
        startLocationUpdates()
        monitorAutoTriggerOpted()
    }

    private fun monitorAutoTriggerOpted() {
        barrierDao.getCountAutoTriggerOptedFlow()
            .distinctUntilChanged()
            .onEach { count ->
                Log.d(TAG, "monitorAutoTriggerOpted: $count")
                val isRunning = Utils.isServiceRunning(getApplication(), PathToBarrierMonitoringService::class.java)
                if (count > 0 && !isRunning) {
                    val intent = Intent(getApplication(), PathToBarrierMonitoringService::class.java)
                    getApplication<Application>().startForegroundService(intent)
                } else if (count == 0 && isRunning) {
                    val intent = Intent(getApplication(), PathToBarrierMonitoringService::class.java)
                    getApplication<Application>().stopService(intent)
                }
            }
            .launchIn(viewModelScope)
    }

    fun startLocationUpdates() {
        Log.d(TAG, "startLocationUpdates requested")
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .build()

        try {
            // Also try to get the very last known location immediately
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                Log.d(TAG, "Initial lastLocation: $loc")
                _currentLocation.value = loc
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "requestLocationUpdates started")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: No location permission", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    fun seedDatabaseIfNeeded(
        shortName: String,
        description: String,
        color: Int,
        phoneTo: String,
        phoneFrom: String,
        lat: Double,
        lng: Double,
        radius: Float
    ) {
        viewModelScope.launch {
            // Check DAO directly to avoid race conditions with StateFlow initialization
            if (barrierDao.getAll().isEmpty()) {
                val testBarrier = Barrier(
                    shortName = shortName,
                    description = description,
                    color = color,
                    phoneNumberTo = phoneTo,
                    phoneNumberFrom = phoneFrom,
                    latitude = lat,
                    longitude = lng,
                    radius = radius,
                    hasOptedAutoTrigger = true // Assuming seed data wants this enabled for demo
                )
                barrierDao.insert(testBarrier)
            }
        }
    }

    fun addBarrier(barrier: Barrier) {
        viewModelScope.launch {
            barrierDao.insert(barrier)
        }
    }

    fun updateBarrier(barrier: Barrier) {
        viewModelScope.launch {
            barrierDao.update(barrier)
        }
    }

    suspend fun getLiftStats(barrierId: Int): BarrierStats {
        return BarrierStats(
            failedCount = liftEventDao.getFailedCount(barrierId),
            successButton = liftEventDao.getSuccessCountBySource(barrierId, LiftSource.BUTTON),
            successLiftNLearn = liftEventDao.getSuccessCountBySource(barrierId, LiftSource.BUTTON_LIFT_AND_LEARN),
            successWidget = liftEventDao.getSuccessCountBySource(barrierId, LiftSource.WIDGET),
            successAutoTrigger = liftEventDao.getSuccessCountBySource(barrierId, LiftSource.AUTO_TRIGGER)
        )
    }

    fun toggleAutoTrigger(barrier: Barrier, enabled: Boolean) {
        updateBarrier(barrier.copy(isEnabledAutoTrigger = enabled))
    }

    fun deleteBarrier(barrier: Barrier) {
        viewModelScope.launch {
            barrierDao.delete(barrier)
        }
    }
}
