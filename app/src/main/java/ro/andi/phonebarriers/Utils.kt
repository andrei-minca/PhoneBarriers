package ro.andi.phonebarriers

import android.app.Activity
import android.content.Context

/**
 * [Utils] is a utility object providing helper functions for checking Android permissions
 * and service states.
 */
object Utils {
    //...
    fun isServiceRunning(activity: Activity, serviceClass: Class<*>): Boolean {
        val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}