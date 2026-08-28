package ro.andi.phonebarriers

import android.app.Activity
import android.content.Context

/**
 * [Utils] is a utility object providing helper functions for checking Android permissions
 * and service states.
 */
object Utils {
    //...
    fun isServiceRunning(context: android.content.Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}