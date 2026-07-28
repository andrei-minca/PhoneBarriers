package ro.andi.phonebarriers

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ro.andi.phonebarriers.data.AppPreferences
import java.util.concurrent.TimeUnit

class PBApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val lActiveHours = AppPreferences(this).getActiveHours()
        if (lActiveHours.isEmpty()) {

            val defaultList: List<Int> = BuildConfig.DEFAULT_ACTIVE_HOURS_LIST
                .split(",")         // Split by comma
                .filter { it.isNotBlank() }     // Remove empty strings if any
                .map { it.trim().toInt() }      // Convert each to Int

            Log.d("PBApp", "defaultActiveHours: $defaultList")

            AppPreferences(this).setActiveHours(defaultList)
        }

        scheduleRecurrentTask()
    }

    private fun scheduleRecurrentTask() {
        val recurrentWorkRequest = PeriodicWorkRequestBuilder<RecurrentNativeWorker>(
            7, TimeUnit.DAYS
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "RecurrentNativeTask",
            ExistingPeriodicWorkPolicy.KEEP,
            recurrentWorkRequest
        )
    }
}
