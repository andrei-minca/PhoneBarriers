package ro.andi.phonebarriers

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ro.andi.phonebarriers.data.AppPreferences
import ro.andi.phonebarriers.service.RecurrentNativeWorker
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
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(android.app.NotificationManager::class.java)

        // Existing tracking channel (if not already handled in service, but good to ensure here)
        val trackingChannel = android.app.NotificationChannel(
            "tracking_channel",
            "Tracking Service Channel",
            android.app.NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(trackingChannel)

        // New Classification channel
        val classificationChannel = android.app.NotificationChannel(
            "classification_results_channel",
            "Classification Results",
            android.app.NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications for barrier classification results"
        }
        manager.createNotificationChannel(classificationChannel)
    }

    private fun scheduleRecurrentTask() {
        val recurrentWorkRequest = PeriodicWorkRequestBuilder<RecurrentNativeWorker>(
            3, TimeUnit.DAYS
//            3, TimeUnit.HOURS // the minimum is 15 minutes
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "RecurrentNativeTask",
            ExistingPeriodicWorkPolicy.UPDATE, // ExistingPeriodicWorkPolicy.KEEP,
            recurrentWorkRequest
        )
    }
}
