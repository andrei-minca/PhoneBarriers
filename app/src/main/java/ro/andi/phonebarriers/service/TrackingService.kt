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

    enum class ETypeSampling {
        Active,
        Paused,
        AutoSleep,Sleep,
        DelayedSleep // awake and delay the sleep state for X minutes
    }
    private var samplingState = ETypeSampling.Active // Control flag
    private var timestampDelayedSleep = 0L // Timestamp for delayed sleep
    private var delaySleepAmountMillis = 600000L // 10 minutes // 20000L // 20 seconds //
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
            "ACTION_PAUSE" -> {
                samplingState = ETypeSampling.Paused
            }
            "ACTION_START" -> {
                samplingState = ETypeSampling.Active
            }
            "ACTION_SLEEP" -> {
                samplingState = ETypeSampling.Sleep
            }
            "ACTION_DELAY_SLEEP" -> {
                samplingState = ETypeSampling.DelayedSleep
                timestampDelayedSleep = System.currentTimeMillis()
            }
            "ACTION_CLOSE" -> {
                // remove notification and stop service
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY // Do not restart if user explicitly exited
            }
        }

        // This must be called in onStartCommand as well to ensure
        // the system knows the FGS type is 'location'
        startMyForegroundService()

        // ... your logic ...

        // ADJUST HARDWARE BASED ON TIME/STATE
        adjustHardwareForPower()

        return START_STICKY
    }
    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(this)
        serviceScope.cancel() // Stop the sampling loop
        Log.d("TrackingService", "Service Destroyed and Sampling Stopped")
    }

    private fun startMyForegroundService() {

        // 1.a create Notification Channel (Required for API 26+)
        createNotificationChannel()
        // 1.b create Notification
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

        //region {buttons with intents}

        // close button
        val closeIntent = Intent(this, ControlReceiver::class.java).apply { action = "ACTION_CLOSE" }
        val pClose = PendingIntent.getBroadcast(this, 5, closeIntent, PendingIntent.FLAG_IMMUTABLE)

        // pause Button Intent
        val pauseIntent = Intent(this, ControlReceiver::class.java).apply { action = "ACTION_PAUSE" }
        val pPause = PendingIntent.getBroadcast(this, 1, pauseIntent, PendingIntent.FLAG_IMMUTABLE)

        // Start Button Intent
        val startIntent = Intent(this, ControlReceiver::class.java).apply { action = "ACTION_START" }
        val pStart = PendingIntent.getBroadcast(this, 2, startIntent, PendingIntent.FLAG_IMMUTABLE)

        // delay sleep Button Intent
        val sleepIntent = Intent(this, ControlReceiver::class.java).apply { action = "ACTION_SLEEP" }
        val pSleep = PendingIntent.getBroadcast(this, 3, sleepIntent, PendingIntent.FLAG_IMMUTABLE)

        // delay sleep Button Intent
        val delaySleepIntent = Intent(this, ControlReceiver::class.java).apply { action = "ACTION_DELAY_SLEEP" }
        val pDelaySleep = PendingIntent.getBroadcast(this, 4, delaySleepIntent, PendingIntent.FLAG_IMMUTABLE)

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

        //endregion

        val builder = androidx.core.app.NotificationCompat.Builder(this, "tracking_channel")
            .setContentTitle("Phone Barriers")
            .setContentText(when (samplingState) {
                ETypeSampling.Active -> "Monitoring patterns at 1Hz (ACTIVE)"
                ETypeSampling.Paused -> "No monitoring of patterns (PAUSED)"
                ETypeSampling.AutoSleep -> "No monitoring of patterns (AUTO-SLEEP)"
                ETypeSampling.Sleep -> "No monitoring of patterns (SLEEP)"
                ETypeSampling.DelayedSleep -> "Temporary monitoring of patterns at 1Hz (DELAYED-SLEEP)"
            })
            .setSmallIcon(R.drawable.sv_fontawesome_road_barrier_s_f)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true) // Makes the notification non-dismissible
            .setSilent(true)
            .setContentIntent(pendingIntent)

        // add the close button
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", pClose)

        // Dynamically add the correct button
        when (samplingState) {
            ETypeSampling.Active -> builder.addAction(android.R.drawable.ic_media_pause, "Pause", pPause)
            ETypeSampling.Paused -> builder.addAction(android.R.drawable.ic_media_play, "Start", pStart)
            ETypeSampling.AutoSleep -> builder.addAction(android.R.drawable.ic_media_play, "Delay sleep", pDelaySleep)
            ETypeSampling.Sleep -> builder.addAction(android.R.drawable.ic_media_play, "Delay sleep", pDelaySleep)
            ETypeSampling.DelayedSleep -> builder.addAction(android.R.drawable.ic_media_pause, "Sleep", pSleep)
        }

        // lift button
        builder.addAction(android.R.drawable.ic_menu_call, "Lift "+VALUE_BARRIER_NAME.take(9), pLift)

        return builder.build()
    }

    private fun nextSamplingState(currentState: ETypeSampling): ETypeSampling {

        val retCode = when (currentState) {
            ETypeSampling.Active -> {

                if (isAutoSleepTime()) {
                    ETypeSampling.AutoSleep
                } else {
                    ETypeSampling.Active
                }
            }

            ETypeSampling.Paused -> ETypeSampling.Paused

            ETypeSampling.AutoSleep -> {

                if (isAutoSleepTime()) {
                    ETypeSampling.AutoSleep
                } else {
                    ETypeSampling.Active
                }
            }

            ETypeSampling.Sleep -> {

                if (isAutoSleepTime()) {
                    ETypeSampling.Sleep
                } else {
                    ETypeSampling.Active
                }
            }

            ETypeSampling.DelayedSleep -> {

                if (timestampDelayedSleep==0L) timestampDelayedSleep = System.currentTimeMillis()
                if (System.currentTimeMillis() - timestampDelayedSleep > delaySleepAmountMillis) {
                    timestampDelayedSleep = 0L
                    ETypeSampling.AutoSleep
                } else {
                    ETypeSampling.DelayedSleep
                }
            }
        }

        return retCode
    }
    private fun isAutoSleepTime(): Boolean {
        val hourNow = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        Log.d("TrackingService", "hourNow: $hourNow")
        val activeHours = 9..17 //listOf(8,9,12,13,15,16)//
        return hourNow !in activeHours
    }
    private var isCurrentlyThrottled: Boolean? = null // Tracks if we are in power-save mode
    private fun adjustHardwareForPower() {

        val checkShouldThrottle: ((ETypeSampling)-> Boolean) = { samplingState ->

            when (samplingState) {
                ETypeSampling.Active -> false
                ETypeSampling.Paused -> true
                ETypeSampling.AutoSleep -> true
                ETypeSampling.Sleep -> true
                ETypeSampling.DelayedSleep -> false
            }
        }

        val shouldThrottle = checkShouldThrottle(samplingState)

        // Optimization: Only run the logic if the state actually changed
        if (isCurrentlyThrottled == shouldThrottle) return
        isCurrentlyThrottled = shouldThrottle

        // REFRESH NOTIFICATION: Update the text to reflect Pause or Night Mode
        startMyForegroundService()

        if (shouldThrottle) {
            // PAUSE OR SLEEP MODE: Stop sensors and slow down GPS to "Passive"
            sensorManager.unregisterListener(this)

            val passiveRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 300000L) // 5 minutes
                .build()
            try {
                fusedLocationClient.requestLocationUpdates(passiveRequest, locationCallback, mainLooper)
            } catch (e: SecurityException) { }

            Log.d("TrackingService", "Battery Saver: Hardware throttled")
        } else {
            // ONLINE MODE: Resume high accuracy
            val accel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL) // NORMAL is better than GAME for battery

            startLocationUpdates() // Resumes 1s GPS
        }
    }
    private fun startSamplingLoop() {

        val checkIsSampling: (ETypeSampling) -> Boolean = { samplingState ->
            when (samplingState) {
                ETypeSampling.Active -> true
                ETypeSampling.Paused -> false
                ETypeSampling.AutoSleep -> false
                ETypeSampling.Sleep -> false
                ETypeSampling.DelayedSleep -> true
            }
        }

        serviceScope.launch {
            while (isActive) {

                Log.d("TrackingService", "samplingState: $samplingState")
                samplingState = nextSamplingState(samplingState)
                Log.d("TrackingService", "next-samplingState: $samplingState")

                // Check if we need to change hardware state (Day/Night or Pause/Start)
                adjustHardwareForPower()

                if (checkIsSampling(samplingState)) {

                    savePointToDb(sessionId = null) // Null means "temporary buffer"

                    // do some cleaning every 2 minutes
                    if (countSavedPointsSinceRefresh>120) {

                        // remove points older than 1 minute
                        AppDatabase.getDatabase(this@TrackingService)
                            .motionDao()
                            .cleanOldUnusedData(System.currentTimeMillis() - 60000)

                        countSavedPointsSinceRefresh = 0
                    }

                    delay(1000) // 1Hz frequency
                }
                else {
                    // if it's  paused, sleep for a longer interval (e.g., 10 seconds)
                    // before checking the time/status again to save CPU cycles.
                    delay(10000)
                }
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