package ro.andi.phonebarriers.logging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ro.andi.phonebarriers.R
import ro.andi.phonebarriers.data.*
import java.text.SimpleDateFormat
import java.util.*

object LiftEventLogger {
    private const val CHANNEL_ID = "lift_events_channel"
    private const val CHANNEL_NAME = "Lift Events"

    fun logEvent(
        context: Context,
        barrier: Barrier,
        source: LiftSource,
        outcome: LiftOutcome,
        reason: String? = null,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        altitude: Double = 0.0,
        speed: Float = 0f,
        acceleration: Float = 0f
    ) {
        val timestamp = System.currentTimeMillis()
        val event = LiftEvent(
            barrierId = barrier.id,
            barrierName = barrier.shortName,
            timestamp = timestamp,
            source = source,
            outcome = outcome,
            reason = reason,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            speed = speed,
            acceleration = acceleration
        )

        // 1. Save to DB
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getDatabase(context).liftEventDao().insert(event)
        }

        // 2. Show unique notification
        showNotification(context, event)
    }

    private fun showNotification(context: Context, event: LiftEvent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)

        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.timestamp))
        val title = "${event.barrierName}: Lift ${event.outcome.name.lowercase()}"
        
        val contentText = "Source: ${event.source.name.replace("_", " ")}\n" +
                "Time: $timeStr" +
                (if (event.reason != null) "\nReason: ${event.reason}" else "")

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.sv_fontawesome_road_barrier_s_f)
            .setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(context).notify(
                event.timestamp.toInt(), // Unique ID
                builder.build()
            )
        } catch (e: SecurityException) {
            // Handle permission error if necessary
        }
    }
}
