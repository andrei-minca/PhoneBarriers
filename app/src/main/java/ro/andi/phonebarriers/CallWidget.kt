package ro.andi.phonebarriers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.andi.phonebarriers.data.AppDatabase
import ro.andi.phonebarriers.data.AppPreferences
import androidx.core.content.ContextCompat

class CallWidget : AppWidgetProvider() {

    private val ACTION_CLICK = "com.yourapp.ACTION_WIDGET_CALL"

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val prefs = AppPreferences(context)
        val barrierName = prefs.getWidgetBarrierName()
        val barrierColor = prefs.getWidgetBarrierColor()

        for (appWidgetId in appWidgetIds) {

            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            if (barrierName != null) {
                // InsideABarrierRadius (Enabled state)

                setWidgetRemoteViews(context, views, barrierName, barrierColor)

                val intent = Intent(context, CallWidget::class.java).apply {
                    action = ACTION_CLICK
                    // Android 14+ requirement: Explicit package
                    `package` = context.packageName
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_button, pendingIntent)

                views.setBoolean(R.id.widget_button, "setEnabled", true)
            } else {
                // NoBarrierInSight (Disabled state)

                setWidgetRemoteViews(context, views, barrierName, barrierColor)

                views.setOnClickPendingIntent(R.id.widget_button, null)

                views.setBoolean(R.id.widget_button, "setEnabled", false)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_CLICK) {
            val pendingResult = goAsync() // Crucial for background work in a Receiver

            val prefs = AppPreferences(context)
            val barrierPhoneTo = prefs.getWidgetBarrierPhoneNumberTo()
            val barrierPhoneFrom = prefs.getWidgetBarrierPhoneNumberFrom()
            val barrierId = prefs.getWidgetBarrierId()

            if (barrierPhoneTo == null || barrierPhoneFrom == null) {
                pendingResult.finish()
                return
            }

            // 1. Set "Loading" State in Widget
            showAsReady(context,false)

            // 2. Perform background logic
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context)

                    // A.1 update lift-n-learn count & last lift timestamp
                    db.barrierDao().getById(barrierId)?.let { barrier ->
                        db.barrierDao().update(barrier.copy(countLiftNLearn = barrier.countLiftNLearn + 1))
                    }

                    prefs.setBarrierIdLastLiftTimestamp(barrierId, System.currentTimeMillis())

                    // A.2 Trigger the API/Call
                    CallRepository.triggerOneRing(
                        barrierPhoneTo,
                        barrierPhoneFrom
                    ) { /* handle success/fail if needed */ }


                    // B. Tag recent motion points (Same logic as Activity)
                    run {
                        val sessionId = System.currentTimeMillis()
                        val dao = db.motionDao()

                        // Tag points from the last 30.9 seconds that don't have a sessionId yet
                        val threshold = System.currentTimeMillis() - 30900
                        dao.tagRecentPoints(sessionId, barrierId, threshold)

                        // Optional: Clean up very old data (> 1 minute)
                        dao.cleanOldUnusedData(System.currentTimeMillis() - 60000)
                    }


                    // C. Wait for the loading state duration (5 seconds)
                    delay(5000)

                } finally {
                    // D. Reset UI on Main Thread
                    withContext(Dispatchers.Main) {

                        // 3. set Widget State to ready
                        showAsReady(context,true)

                        pendingResult.finish() // Tell the OS the broadcast is done
                    }
                }
            }

        }
    }

    private fun showAsReady(context: Context, bIsReady: Boolean) {

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val thisWidget = ComponentName(context, CallWidget::class.java)

        val finalViews = RemoteViews(context.packageName, R.layout.widget_layout)

        val prefs = AppPreferences(context)
        val barrierName = prefs.getWidgetBarrierName()
        val barrierColor = prefs.getWidgetBarrierColor()

        if (bIsReady) {
            // show as just Enabled & not Loading

            // should be InsideABarrierRadius (Enabled state)
            setWidgetRemoteViews(context, finalViews, barrierName, barrierColor)

            val intent = Intent(context, CallWidget::class.java).apply {
                action = ACTION_CLICK
                // Android 14+ requirement: Explicit package
                `package` = context.packageName
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            finalViews.setOnClickPendingIntent(R.id.widget_button, pendingIntent)

            finalViews.setBoolean(R.id.widget_button, "setEnabled", true)
            
            finalViews.setViewVisibility(R.id.widget_progress, View.GONE)

            appWidgetManager.updateAppWidget(thisWidget, finalViews)
        }
        else {
            // show as Enabled & Loading

            finalViews.setViewVisibility(R.id.widget_progress, View.VISIBLE)

            finalViews.setBoolean(R.id.widget_button, "setEnabled", false)

            finalViews.setOnClickPendingIntent(R.id.widget_button, null)

            // should be InsideABarrierRadius (Enabled state)
            setWidgetRemoteViews(context, finalViews, barrierName, barrierColor)

            appWidgetManager.updateAppWidget(thisWidget, finalViews)
        }

    }

    private fun setWidgetRemoteViews(context: Context, wRV: RemoteViews, barrierName: String?, barrierColor: Int = 0) {

        if (barrierName != null) {
            // InsideABarrierRadius (Enabled state)
            val blueColor = ContextCompat.getColor(context, R.color.blue_lift_n_learn)
            wRV.setTextViewText(R.id.widget_tvt, barrierName as CharSequence)
            wRV.setTextColor(R.id.widget_tvt, android.graphics.Color.WHITE)
            wRV.setInt(R.id.widget_border_bg, "setColorFilter", barrierColor)
            wRV.setInt(R.id.widget_inner_bg, "setColorFilter", blueColor)
            wRV.setInt(R.id.widget_icon, "setColorFilter", android.graphics.Color.WHITE)
            wRV.setInt(R.id.widget_icon2, "setColorFilter", android.graphics.Color.WHITE)
        }
        else {
            // NoBarrierInSight (Disabled state)
            val lightGray = ContextCompat.getColor(context, R.color.gray_no_barrier_in_sight)
            wRV.setTextViewText(R.id.widget_tvt, "No-Barrier")
            wRV.setTextColor(R.id.widget_tvt, android.graphics.Color.DKGRAY)
            wRV.setInt(R.id.widget_border_bg, "setColorFilter", lightGray)
            wRV.setInt(R.id.widget_inner_bg, "setColorFilter", lightGray)
            wRV.setInt(R.id.widget_icon, "setColorFilter", android.graphics.Color.DKGRAY)
            wRV.setInt(R.id.widget_icon2, "setColorFilter", android.graphics.Color.DKGRAY)
        }

    }
}