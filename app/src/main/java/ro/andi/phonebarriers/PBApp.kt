package ro.andi.phonebarriers

import android.app.Application
import android.util.Log
import ro.andi.phonebarriers.data.AppPreferences

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
    }
}