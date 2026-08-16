package ro.andi.phonebarriers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ro.andi.phonebarriers.data.AppDatabase
import ro.andi.phonebarriers.data.Barrier

class BarrierManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val barrierDao = AppDatabase.getDatabase(application).barrierDao()

    val barriers: StateFlow<List<Barrier>> = barrierDao.getAllFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

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
