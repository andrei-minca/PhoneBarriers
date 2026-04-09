package ro.andi.phonebarriers.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * [AppPreferences] provides a simple way to store and retrieve control settings
 * using SharedPreferences.
 *
 * For more complex data or multi-user scenarios, consider Room, DataStore, or Firestore.
 */
class AppPreferences(val context: Context) {

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {

        private const val SHARED_PREFS_NAME = "shared_prefs"

        private const val KEY_ACTIVE_HOURS = "active_hours"

    }

    fun getActiveHours(): List<Int> {
        val json = sharedPrefs.getString(KEY_ACTIVE_HOURS, null)
        return if (json != null) {
            gson.fromJson(json, object : TypeToken<List<Int>>() {}.type)
        } else {
            listOf()
        }
    }
    fun setActiveHours(activeHours:List<Int>) {
        sharedPrefs.edit { putString(KEY_ACTIVE_HOURS, gson.toJson(activeHours)) }
    }

}