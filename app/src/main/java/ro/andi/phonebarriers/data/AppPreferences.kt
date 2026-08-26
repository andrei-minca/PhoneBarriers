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
        private const val KEY_WIDGET_BARRIER_NAME = "widget_barrier_name"
        private const val KEY_WIDGET_BARRIER_COLOR = "widget_barrier_color"
        private const val KEY_WIDGET_BARRIER_PHONE_NUMBER_TO = "widget_barrier_phone_number_to"
        private const val KEY_WIDGET_BARRIER_PHONE_NUMBER_FROM = "widget_barrier_phone_number_from"

    }

    fun getWidgetBarrierName(): String? = sharedPrefs.getString(KEY_WIDGET_BARRIER_NAME, null)
    fun getWidgetBarrierColor(): Int = sharedPrefs.getInt(
        KEY_WIDGET_BARRIER_COLOR,
        androidx.core.content.ContextCompat.getColor(context, ro.andi.phonebarriers.R.color.blue_lift_n_learn)
    )
    fun getWidgetBarrierPhoneNumberTo(): String? = sharedPrefs.getString(KEY_WIDGET_BARRIER_PHONE_NUMBER_TO, null)
    fun getWidgetBarrierPhoneNumberFrom(): String? = sharedPrefs.getString(KEY_WIDGET_BARRIER_PHONE_NUMBER_FROM, null)


    fun setWidgetBarrierInfo(name: String?, color: Int = 0,
                             phoneNumberTo: String = "", phoneNumberFrom: String = "") {
        sharedPrefs.edit {
            if (name == null) {
                remove(KEY_WIDGET_BARRIER_NAME)
                remove(KEY_WIDGET_BARRIER_COLOR)
                remove(KEY_WIDGET_BARRIER_PHONE_NUMBER_TO)
                remove(KEY_WIDGET_BARRIER_PHONE_NUMBER_FROM)
            }
            else {
                putString(KEY_WIDGET_BARRIER_NAME, name)
                putInt(KEY_WIDGET_BARRIER_COLOR, color)
                putString(KEY_WIDGET_BARRIER_PHONE_NUMBER_TO, phoneNumberTo)
                putString(KEY_WIDGET_BARRIER_PHONE_NUMBER_FROM, phoneNumberFrom)
            }
        }
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