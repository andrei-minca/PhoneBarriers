package ro.andi.phonebarriers

import android.app.Application
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ro.andi.phonebarriers.data.AppDatabase
import ro.andi.phonebarriers.data.Barrier

class BarrierManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "BarrierVM"
    private val barrierDao = AppDatabase.getDatabase(application).barrierDao()
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)

    val barriers: StateFlow<List<Barrier>> = barrierDao.getAllFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val loc = locationResult.lastLocation
            Log.d(TAG, "onLocationResult: $loc")
            loc?.let {
                _currentLocation.value = LatLng(it.latitude, it.longitude)
            }
        }
    }

    init {
        startLocationUpdates()
    }

    fun startLocationUpdates() {
        Log.d(TAG, "startLocationUpdates requested")
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .build()

        try {
            // Also try to get the very last known location immediately
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                Log.d(TAG, "Initial lastLocation: $loc")
                loc?.let {
                    _currentLocation.value = LatLng(it.latitude, it.longitude)
                }
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

    fun incrementLiftCount(barrier: Barrier) {
        updateBarrier(barrier.copy(countLift = barrier.countLift + 1))
    }

    fun incrementLiftNLearnCount(barrier: Barrier) {
        updateBarrier(barrier.copy(countLiftNLearn = barrier.countLiftNLearn + 1))
    }

    fun incrementAutoTriggeredCount(barrier: Barrier) {
        updateBarrier(barrier.copy(countAutoTriggered = barrier.countAutoTriggered + 1))
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
