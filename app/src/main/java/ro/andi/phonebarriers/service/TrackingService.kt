package ro.andi.phonebarriers.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import com.google.android.gms.location.*
import com.google.android.gms.location.FusedLocationProviderClient
import kotlinx.coroutines.*
import ro.andi.phonebarriers.BuildConfig
import ro.andi.phonebarriers.MainActivity
import ro.andi.phonebarriers.data.AppDatabase
import ro.andi.phonebarriers.data.MotionPoint
import ro.andi.phonebarriers.R

class TrackingService : Service(), SensorEventListener {

    private val VALUE_BARRIER_NAME = BuildConfig.TEST_BARRIER_NAME // or dynamic update if multiple barriers

    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var countSavedPointsSinceRefresh = 0

    private var isSampling = true // Control flag
    private var currentMaxAccel = 0f
    private var lastLocation: android.location.Location? = null
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            // Update the global lastLocation variable
            lastLocation = locationResult.lastLocation
        }
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    class ControlReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            val serviceIntent = Intent(context, TrackingService::class.java).apply {
                action = intent.action
            }
            context.startService(serviceIntent)
        }
    }

    override fun onCreate() {
        super.onCreate()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates()
        startSamplingLoop()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // Handle the button actions
        when (intent?.action) {
            "ACTION_STOP" -> {
                isSampling = false
            }
            "ACTION_START" -> {
                isSampling = true
            }
        }

        // This must be called in onStartCommand as well to ensure
        // the system knows the FGS type is 'location'
        startMyForegroundService()

        // ... your logic ...

        return START_STICKY
    }
    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(this)
        serviceScope.cancel() // Stop the sampling loop
    }

    private fun startMyForegroundService() {

        // 1.a create Notification Channel (Required for API 26+)
        createNotificationChannel()
        // 2.b create Notification
        val notification = createNotification()

        // 2. start as Foreground Service
        startForeground(1, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }
    private fun createNotificationChannel() {
        val channel = android.app.NotificationChannel(
            "tracking_channel",
            "Tracking Service Channel",
            android.app.NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
    private fun createNotification(): Notification {

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Stop Button Intent
        val stopIntent = Intent(this, ControlReceiver::class.java).apply { action = "ACTION_STOP" }
        val pPause = PendingIntent.getBroadcast(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        // Start Button Intent
        val startIntent = Intent(this, ControlReceiver::class.java).apply { action = "ACTION_START" }
        val pStart = PendingIntent.getBroadcast(this, 2, startIntent, PendingIntent.FLAG_IMMUTABLE)

        // Intent to open MainActivity and trigger the lift
        val liftIntent = Intent(this, MainActivity::class.java).apply {
            action = "ACTION_TRIGGER_LIFT" // Custom action
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pLift = PendingIntent.getActivity(
            this,
            3,
            liftIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = androidx.core.app.NotificationCompat.Builder(this, "tracking_channel")
            .setContentTitle("Phone Barriers")
            .setContentText(if (isSampling) "Monitoring patterns at 1Hz" else "Monitoring of patterns is PAUSED")
            .setSmallIcon(R.drawable.sv_fontawesome_road_barrier_s_f)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true) // Makes the notification non-dismissible
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_call, "Lift "+VALUE_BARRIER_NAME.take(9), pLift)

        // Dynamically add the correct button
        if (isSampling) {
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pPause)
        } else {
            builder.addAction(android.R.drawable.ic_media_play, "Start", pStart)
        }

        return builder.build()
    }
    private fun startSamplingLoop() {

        serviceScope.launch {
            while (isActive) {

                if (isSampling) {

                    savePointToDb(sessionId = null) // Null means "temporary buffer"

                    // do some cleaning every 2 minutes
                    if (countSavedPointsSinceRefresh>120) {

                        // remove points older than 1 minute
                        AppDatabase.getDatabase(this@TrackingService)
                            .motionDao()
                            .cleanOldUnusedData(System.currentTimeMillis() - 60000)

                        countSavedPointsSinceRefresh = 0
                    }
                }

                delay(1000) // 1Hz frequency
            }
        }
    }
    private suspend fun savePointToDb(sessionId: Long?) {

        val point = MotionPoint(
            sessionId = sessionId,
            timestamp = System.currentTimeMillis(),
            accuracy = lastLocation?.accuracy ?: 0f,
            lat = lastLocation?.latitude ?: 0.0,
            lng = lastLocation?.longitude ?: 0.0,
            alt = lastLocation?.altitude ?: 0.0,
            speed = lastLocation?.speed ?: 0f,
            acceleration = currentMaxAccel
        )
        Log.d("${this.javaClass.name}","adding point to db: $point ...")

        // Insert into Room
        AppDatabase.getDatabase(this).motionDao().insert(point)

        Log.d("${this.javaClass.name}","added point to db: $point !")

        countSavedPointsSinceRefresh++
        currentMaxAccel = 0f // Reset for next 500ms window
    }

    override fun onSensorChanged(event: SensorEvent) {
        val acc = event.values
        val totalAcc = Math.sqrt((acc[0]*acc[0] + acc[1]*acc[1] + acc[2]*acc[2]).toDouble()).toFloat()
        if (totalAcc > currentMaxAccel) currentMaxAccel = totalAcc
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L) // 1 second intervals
            .setMinUpdateIntervalMillis(500L) // Allow it to go faster if available
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                mainLooper // Ensures the callback runs on the main thread to update the variable
            )
        } catch (unlikely: SecurityException) {
            // This happens if the user revoked permissions while the service was starting
            stopSelf()
        }
    }
}