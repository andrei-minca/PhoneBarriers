package ro.andi.phonebarriers.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import ro.andi.phonebarriers.AdminActivity
import ro.andi.phonebarriers.CallRepository
import ro.andi.phonebarriers.CallWidget
import ro.andi.phonebarriers.NativeLib
import ro.andi.phonebarriers.R
import ro.andi.phonebarriers.data.AppDatabase
import ro.andi.phonebarriers.data.AppPreferences
import ro.andi.phonebarriers.data.Barrier
import ro.andi.phonebarriers.data.MotionPoint
import kotlin.math.sqrt

class PathToBarrierMonitoringService : Service() {

    companion object {
        private const val TAG = "PathToBarrierService"
        private const val CHANNEL_ID = "monitoring_channel"
        private const val NOTIFICATION_ID = 100
        
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SLEEP = "ACTION_SLEEP"
        const val ACTION_WAKE = "ACTION_WAKE"
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var lastLocation: Location? = null
    private var currentMaxAccel = 0f
    
    private enum class State { STARTING, ACTIVE, LIGHT_SLEEP, DEEP_SLEEP }
    private var currentState = State.STARTING
    
    private var isLocationActive = false
    private var isAccelActive = false
    
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            lastLocation = locationResult.lastLocation
        }
    }

    private val significantMotionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            Log.d(TAG, "Significant motion detected!")
            if (currentState == State.DEEP_SLEEP) {
                startMonitoringLoop() // Restart the loop from deep sleep
            }
        }
    }

    private val sensorAccelEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
//            Log.d(TAG, "Sensor changed: ${event?.values?.get(0)}")
            if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val accel = sqrt(x*x + y*y + z*z)
                if (accel > currentMaxAccel) currentMaxAccel = accel
                Log.d(TAG, "Sensor changed, accel: $accel")
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    class ControlReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val serviceIntent = Intent(context, PathToBarrierMonitoringService::class.java).apply {
                action = intent.action
            }

            context.startForegroundService(serviceIntent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startMonitoringLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SLEEP -> {
                transitionTo(State.LIGHT_SLEEP)
            }
            ACTION_WAKE -> {
                transitionTo(State.ACTIVE)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopLocationUpdates()
        stopAccelerometer()
        sensorManager.cancelTriggerSensor(significantMotionListener, sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION))
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            while (isActive) {
                when (currentState) {
                    State.STARTING -> handleStarting()
                    State.ACTIVE -> handleActive()
                    State.LIGHT_SLEEP -> handleLightSleep()
                    State.DEEP_SLEEP -> handleDeepSleep()
                }
            }
        }
    }

    private suspend fun handleStarting() {
        Log.d(TAG, "[STARTING] Collecting initial data...")
        startLocationUpdates()
        startAccelerometer()
        
        val points = collectData(5, 1000L)
        val maxSpeed = points.maxOfOrNull { it.speed } ?: 0f
        
        val db = AppDatabase.getDatabase(this)
        val barriersWithAutoTriggerOpted = db.barrierDao().getAll().filter { it.hasOptedAutoTrigger }

        val inRange = barriersWithAutoTriggerOpted.any { barrier ->
            isInRangeToReach(barrier, maxSpeed, 2f, 30f)
        }

        if (inRange) {
            transitionTo(State.ACTIVE)
        } else {
            transitionTo(State.LIGHT_SLEEP)
        }
    }

    private suspend fun handleActive() {
        Log.d(TAG, "[ACTIVE] Monitoring barriers...")
        startLocationUpdates()
        startAccelerometer()

        val last30Points = mutableListOf<MotionPoint>()
        
        while (currentState == State.ACTIVE) {
            yield()
            val point = saveAndReturnPoint()
            last30Points.add(point)
            if (last30Points.size > 30) last30Points.removeAt(0)
            
            val db = AppDatabase.getDatabase(this)
            val barriersWithAutoTriggerOpted = db.barrierDao().getAll().filter { it.hasOptedAutoTrigger }
            
            var closestBarrier: Barrier? = null
            var minDistance = Float.MAX_VALUE
            
            barriersWithAutoTriggerOpted.forEach { barrier ->
                val distance = getDistanceTo(barrier)
                if (distance < barrier.radius) {
                    if (distance < minDistance) {
                        minDistance = distance
                        closestBarrier = barrier
                    }
                }
                Log.d(TAG, "[ACTIVE] distance to barrier: ${distance}m [${barrier.shortName}] [${barrier.id}]")
            }
            
            if (closestBarrier != null) {
                Log.d(TAG, "[ACTIVE] detected as closest barrier: ${closestBarrier.shortName} [${closestBarrier.id}]")
                updateWidget(closestBarrier)
                // if auto trigger is enabled then try to match the path collected with the barrier's medoids
                if (closestBarrier.isEnabledAutoTrigger && last30Points.size >= 30) {
                    Log.d(TAG, "[ACTIVE] checking if the case to AUTO-TRIGGER for barrier: ${closestBarrier.shortName} [${closestBarrier.id}]")
                    checkAutoTrigger(closestBarrier, last30Points)
                }
            } else {
                updateWidget(null)
            }
            
            val maxSpeedLast5 = last30Points.takeLast(5).maxOfOrNull { it.speed } ?: 0f
            val stillInRange = barriersWithAutoTriggerOpted.any {
                isInRangeToReach(it, maxSpeedLast5, 2f, 30f)
            }
            
            if (!stillInRange) {
                Log.d(TAG, "[ACTIVE] In light range, transitioning to [LIGHT-SLEEP] (maxSpeedLast5s: $maxSpeedLast5 m/s)")
                transitionTo(State.LIGHT_SLEEP)
                break
            }
            else {
                Log.d(TAG, "[ACTIVE] Still in active range continue looping ... (maxSpeedLast5s: $maxSpeedLast5 m/s)")
            }
            
            delay(1000L)
        }
    }

    private suspend fun handleLightSleep() {
        Log.d(TAG, "[LIGHT-SLEEP] Sleeping...")
        stopLocationUpdates()
        stopAccelerometer()
        
        while (currentState == State.LIGHT_SLEEP) {
            yield()
            startLocationUpdates()
            startAccelerometer()
            val points = collectData(5, 1000L)
            val maxSpeed = points.maxOfOrNull { it.speed } ?: 0f
            
            val db = AppDatabase.getDatabase(this)
            val barriersWithAutoTriggerOpted = db.barrierDao().getAll().filter { it.hasOptedAutoTrigger }
            
            val inRangeActive = barriersWithAutoTriggerOpted.any { isInRangeToReach(it, maxSpeed, 2f, 30f) }
            if (inRangeActive) {
                Log.d(TAG, "[LIGHT-SLEEP] In active range, transitioning to [ACTIVE] (maxSpeedLast5s: $maxSpeed m/s)")
                transitionTo(State.ACTIVE)
                break
            }
            
            val inRangeLight = barriersWithAutoTriggerOpted.any { isInRangeToReach(it, maxSpeed, 4f, 30f) }
            if (inRangeLight) {
                Log.d(TAG, "[LIGHT-SLEEP] Still in light range, sleeping for 10s (maxSpeedLast5s: $maxSpeed m/s)")
                stopLocationUpdates()
                stopAccelerometer()
                delay(10000L)
            } else {
                Log.d(TAG, "[LIGHT-SLEEP] In deep range, transitioning to [DEEP-SLEEP] (maxSpeedLast5s: $maxSpeed m/s)")
                transitionTo(State.DEEP_SLEEP)
                break
            }
        }
    }

    private suspend fun handleDeepSleep() {
        Log.d(TAG, "[DEEP-SLEEP] Deep sleeping...")
        stopLocationUpdates()
        stopAccelerometer()
        
        while (currentState == State.DEEP_SLEEP) {
            yield()
            startLocationUpdates()
            startAccelerometer()
            val points = collectData(5, 1000L)
            val maxSpeed = points.maxOfOrNull { it.speed } ?: 0f
            
            val db = AppDatabase.getDatabase(this)
            val barriersWithAutoTriggerOpted = db.barrierDao().getAll().filter { it.hasOptedAutoTrigger }
            
            val inRangeLight = barriersWithAutoTriggerOpted.any { isInRangeToReach(it, maxSpeed, 4f, 30f) }
            if (inRangeLight) {
                transitionTo(State.LIGHT_SLEEP)
                break
            }

            val inRangeDeep = barriersWithAutoTriggerOpted.any { isInRangeToReach(it, maxSpeed, 8f, 30f) }
            val secondsToSleep = if (inRangeDeep) 30 else 90
            
            if (maxSpeed > 0.1f) {
                Log.d(TAG, "[DEEP-SLEEP] Moving but not in range, sleeping for ${secondsToSleep}s (maxSpeedLast5s: $maxSpeed m/s)")
                stopLocationUpdates()
                stopAccelerometer()
                delay(secondsToSleep*1000L)
            } else {
                Log.d(TAG, "[DEEP-SLEEP] Not moving, waiting for significant motion")
                stopLocationUpdates()
                stopAccelerometer()
                registerSignificantMotion()
                suspendCancellableCoroutine<Unit> { /* Stay suspended until cancelled or triggered */ }
                break 
            }
        }
    }

    private fun transitionTo(newState: State) {
        if (currentState != newState) {
            Log.d(TAG, "Transitioning from $currentState to $newState")
            currentState = newState
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, createNotification())
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission for notification missing")
            }
        }
    }

    private suspend fun collectData(count: Int, interval: Long): List<MotionPoint> {
        val points = mutableListOf<MotionPoint>()
        repeat(count) {
            points.add(saveAndReturnPoint())
            delay(interval)
        }
        return points
    }

    private suspend fun saveAndReturnPoint(): MotionPoint {
        val point = MotionPoint(
            sessionId = null,
            timestamp = System.currentTimeMillis(),
            accuracy = lastLocation?.accuracy ?: 0f,
            lat = lastLocation?.latitude ?: 0.0,
            lng = lastLocation?.longitude ?: 0.0,
            alt = lastLocation?.altitude ?: 0.0,
            speed = lastLocation?.speed ?: 0f,
            acceleration = currentMaxAccel
        )
        AppDatabase.getDatabase(this).motionDao().insert(point)
        currentMaxAccel = 0f
        return point
    }

    private fun isInRangeToReach(barrier: Barrier, speed: Float, speedMultiplier: Float, timeSeconds: Float): Boolean {
        val distance = getDistanceTo(barrier)
        val reachableDistance = speed * speedMultiplier * timeSeconds
        return distance <= (barrier.radius + reachableDistance)
    }

    private fun getDistanceTo(barrier: Barrier): Float {
        val loc = lastLocation ?: return Float.MAX_VALUE
        val results = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, barrier.latitude, barrier.longitude, results)
        return results[0]
    }

    private fun updateWidget(barrier: Barrier?) {
        val prefs = AppPreferences(this)
        if (barrier != null) {
            prefs.setWidgetBarrierInfo(barrier.shortName, barrier.color, barrier.phoneNumberTo, barrier.phoneNumberFrom)
        } else {
            prefs.setWidgetBarrierInfo(null)
        }
        // Notify widget to update
        val intent = Intent(this, CallWidget::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            val ids = AppWidgetManager.getInstance(application).getAppWidgetIds(ComponentName(application, CallWidget::class.java))
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        sendBroadcast(intent)
    }

    private suspend fun checkAutoTrigger(barrier: Barrier, last30Points: List<MotionPoint>) {
        val db = AppDatabase.getDatabase(this)
        val medoids = db.medoidDao().getMedoidsForBarrier(barrier.id)
        if (medoids.isEmpty()) {
            Log.d(TAG, "No medoids found for barrier: ${barrier.shortName} [${barrier.id}]")
            return
        }

        val appPreferences = AppPreferences(this)
        val lastLiftTimestamp = appPreferences.getBarrierIdLastLiftTimestamp(barrier.id)
        val nowTimestamp = System.currentTimeMillis()
        if (lastLiftTimestamp > nowTimestamp - 10000) {
            Log.d(TAG, "Checking for match & auto-trigger canceled! Barrier already triggered recently, " +
                    "${barrier.shortName} [${barrier.id}], about ${nowTimestamp - lastLiftTimestamp} ms ago}")
            return
        }
        
        val isMatch = withContext(Dispatchers.Default) {
            // todo : review
            NativeLib.matchPathWithBarrierMedoids(last30Points.toTypedArray(), medoids.toTypedArray())
        }
        
        if (isMatch) {
            Log.d(TAG, "AUTO-TRIGGER matched for barrier: ${barrier.shortName} [${barrier.id}]")

            db.barrierDao().update(barrier.copy(countAutoTriggered = barrier.countAutoTriggered + 1))

            appPreferences.setBarrierIdLastLiftTimestamp(barrier.id, System.currentTimeMillis())

            CallRepository.triggerOneRing(
                barrier.phoneNumberTo,
                barrier.phoneNumberFrom
            ) { /* handle success/fail if needed */ }
        }
        else {
            Log.d(TAG, "AUTO-TRIGGER not matched for barrier: ${barrier.shortName} [${barrier.id}]")
        }
    }

    private fun registerSignificantMotion() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (sensor != null) {
            sensorManager.requestTriggerSensor(significantMotionListener, sensor)
        }
    }

    private fun startLocationUpdates() {
        if (isLocationActive) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooper)
            isLocationActive = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing")
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isLocationActive = false
    }

    private fun startAccelerometer() {
        if (isAccelActive) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        sensorManager.registerListener(sensorAccelEventListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        isAccelActive = true
    }

    private fun stopAccelerometer() {
        sensorManager.unregisterListener(sensorAccelEventListener)
        isAccelActive = false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Monitoring Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val adminIntent = Intent(this, AdminActivity::class.java)
        val pAdmin = PendingIntent.getActivity(this, 0, adminIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, ControlReceiver::class.java).apply { action = ACTION_STOP }
        val pStop = PendingIntent.getBroadcast(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.sv_fontawesome_road_barrier_s_f)
            .setContentTitle("Path-To-Barriers Monitoring")
            .setContentText("State: $currentState")
            .setOngoing(true)
            .setContentIntent(pAdmin)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pStop)

        when (currentState) {
            State.ACTIVE -> {
                val sleepIntent = Intent(this, ControlReceiver::class.java).apply { action = ACTION_SLEEP }
                val pSleep = PendingIntent.getBroadcast(this, 2, sleepIntent, PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(android.R.drawable.ic_media_pause, "Sleep", pSleep)
            }
            State.LIGHT_SLEEP, State.DEEP_SLEEP -> {
                val wakeIntent = Intent(this, ControlReceiver::class.java).apply { action = ACTION_WAKE }
                val pWake = PendingIntent.getBroadcast(this, 3, wakeIntent, PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(android.R.drawable.ic_media_play, "Wake", pWake)
            }
            else -> {}
        }

        return builder.build()
    }
}
