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

class CallWidget : AppWidgetProvider() {

    private val ACTION_CLICK = "com.yourapp.ACTION_WIDGET_CALL"

    private val VALUE_BARRIER_NAME = BuildConfig.TEST_BARRIER_NAME // or dynamic update if multiple barriers

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {

            val intent = Intent(context, CallWidget::class.java).apply {
                action = ACTION_CLICK
                // Android 14+ requirement: Explicit package
                `package` = context.packageName
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            views.setOnClickPendingIntent(R.id.widget_button, pendingIntent)
            views.setTextViewText(R.id.widget_tvt, VALUE_BARRIER_NAME.take(9))

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_CLICK) {
            val pendingResult = goAsync() // Crucial for background work in a Receiver

            // 1. Set "Loading" State in Widget
            showAsReady(context,false)

            // 2. Perform background logic
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // A. Trigger the API/Call
                    CallRepository.triggerOneRing(
                        CallRepository.KEY_TO,
                        CallRepository.KEY_FROM
                    ) { /* handle success/fail if needed */ }

                    // B. Tag recent motion points (Same logic as Activity)
                    run {
                        val sessionId = System.currentTimeMillis()
                        val db = AppDatabase.getDatabase(context)
                        val dao = db.motionDao()

                        // Tag points from the last 30.9 seconds that don't have a sessionId yet
                        val threshold = System.currentTimeMillis() - 30900
                        dao.tagRecentPoints(sessionId, threshold)

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

        if (bIsReady) {

            val intent = Intent(context, CallWidget::class.java).apply {
                action = ACTION_CLICK
                // Android 14+ requirement: Explicit package
                `package` = context.packageName
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val finalViews = RemoteViews(context.packageName, R.layout.widget_layout)
            finalViews.setOnClickPendingIntent(R.id.widget_button, pendingIntent)
            finalViews.setTextViewText(R.id.widget_tvt, VALUE_BARRIER_NAME)
            finalViews.setBoolean(R.id.widget_button, "setEnabled", true)
            finalViews.setViewVisibility(R.id.widget_progress, View.GONE)

            appWidgetManager.updateAppWidget(thisWidget, finalViews)
        }
        else {
            // show as Loading

            val loadingViews = RemoteViews(context.packageName, R.layout.widget_layout)
            loadingViews.setViewVisibility(R.id.widget_progress, View.VISIBLE)
            loadingViews.setTextViewText(R.id.widget_tvt, VALUE_BARRIER_NAME)
            loadingViews.setBoolean(R.id.widget_button, "setEnabled", false)
            loadingViews.setOnClickPendingIntent(R.id.widget_button, null)
            appWidgetManager.updateAppWidget(thisWidget, loadingViews)
        }

    }
}