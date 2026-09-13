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
import java.util.Locale
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
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
import kotlin.time.Duration.Companion.milliseconds

class PathToBarrierMonitoringService : Service() {

    companion object {
        private const val TAG = "PathToBarrierService"
        private const val CHANNEL_ID = "monitoring_channel"
        private const val MATCH_CHANNEL_ID = "match_results_channel"
        private const val NOTIFICATION_ID = 100
        private const val MATCH_NOTIFICATION_BASE_ID = 2000
        
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SLEEP = "ACTION_SLEEP"
        const val ACTION_WAKE_LIGHT = "ACTION_WAKE_LIGHT"
        const val ACTION_WAKE_DEEP = "ACTION_WAKE_DEEP"

        const val LOW_SLOW_SPEED = 0.1f //  m/s
        const val SLOW_WALKING_SPEED = 0.8f //  m/s
        const val SLOW_BIKING_SPEED = 2.0f  //  m/s
        const val SLOW_CAR_SPEED = 4.0f  //  m/s

        const val GUARD_TIMEOUT = 30 * 60 * 1000L // 30 minutes guard timeout
        const val TARGET_BUFFER_TIME = 30 // seconds to wake up before barrier
        const val MIN_SLEEP_LIGHT = 10 * 1000L
        const val MAX_SLEEP_LIGHT = 1 * 60 * 1000L
        const val MIN_SLEEP_DEEP = 30 * 1000L
        const val MAX_SLEEP_DEEP = 2 * 60 * 1000L
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var barrierCollectionJob: Job? = null
    
    private var lastLocation: Location? = null
    private var currentMaxAccel = 0f
    
    private var cachedBarriersWithAutoTriggerOpted = listOf<Barrier>()
    
    private enum class State { STARTING, ACTIVE, LIGHT_SLEEP, DEEP_SLEEP }
    private val _currentState = MutableStateFlow(State.STARTING)
    private val currentState: State get() = _currentState.value
    
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
            transitionTo(State.LIGHT_SLEEP)
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
        
        // Start reactive barrier caching
        barrierCollectionJob = serviceScope.launch {
            AppDatabase.getDatabase(this@PathToBarrierMonitoringService)
                .barrierDao().getAllFlow().collect { barriers ->
                    cachedBarriersWithAutoTriggerOpted = barriers.filter { it.hasOptedAutoTrigger }
                }
        }

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
            ACTION_WAKE_LIGHT -> {
                transitionTo(State.ACTIVE)
            }
            ACTION_WAKE_DEEP -> {
                transitionTo(State.LIGHT_SLEEP)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        updateWidget(null)
        barrierCollectionJob?.cancel()
        serviceScope.cancel()
        stopLocationUpdates()
        stopAccelerometer()
        sensorManager.cancelTriggerSensor(significantMotionListener, sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION))

        super.onDestroy()
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            _currentState.collectLatest { state ->
                when (state) {
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
        startLocationUpdates(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
        startAccelerometer()
        
        val points = collectData(10, 1000L)
        val maxSpeedLast10s = points.maxOfOrNull { it.speed } ?: 0f
        
        val inRange = cachedBarriersWithAutoTriggerOpted.any { barrier ->
            isInRangeToReach(barrier, maxSpeedLast10s, 2f, 30f)
        }

        if (inRange) {
            transitionTo(State.ACTIVE)
        } else {
            transitionTo(State.LIGHT_SLEEP)
        }
    }

    private suspend fun handleActive() {
        Log.d(TAG, "[ACTIVE] Monitoring barriers...")
        startLocationUpdates(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
        startAccelerometer()

        val last30Points = mutableListOf<MotionPoint>()
        
        while (currentState == State.ACTIVE) {
            yield()

            // collect data: first 30 points or another point
            if (last30Points.size < 30) {
                val points = collectData(30, 1000L)
                last30Points.addAll(points)
            }
            else {
                val point = saveAndReturnPoint()
                last30Points.add(point)
            }
            // keep only 30 points
            if (last30Points.size > 30) last30Points.removeAt(0)

            // detect closest barrier and update widget
            var closestBarrier: Barrier? = null
            var minDistance = Float.MAX_VALUE
            cachedBarriersWithAutoTriggerOpted.forEach { barrier ->
                val distance = getDistanceTo(barrier)
                if (distance < barrier.radius) {
                    if (distance < minDistance) {
                        minDistance = distance
                        closestBarrier = barrier
                    }
                }
                Log.d(TAG, "[ACTIVE] distance to barrier: ${distance}m [name:${barrier.shortName}] [id:${barrier.id}] [radius:${barrier.radius}m]")
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

            // detect max speed of the last 25 seconds
            val maxSpeedLast25s = last30Points.takeLast(25).maxOfOrNull { it.speed } ?: 0f

            // detect if still in active range by max speed of the last 5 seconds
            val stillInRange = cachedBarriersWithAutoTriggerOpted.any {
                isInRangeToReach(it, maxSpeedLast25s, 2f, 30f)
            }
            if (!stillInRange) {
                Log.d(TAG, "[ACTIVE] In light range, transitioning to [LIGHT-SLEEP] (maxSpeedLast25Seconds: $maxSpeedLast25s m/s)")
                transitionTo(State.LIGHT_SLEEP)
                break
            }
            else if (maxSpeedLast25s < SLOW_WALKING_SPEED) {
                Log.d(TAG, "[ACTIVE] In active range but slow moving, transitioning to [LIGHT-SLEEP] (maxSpeedLast25Seconds: $maxSpeedLast25s m/s)")
                transitionTo(State.LIGHT_SLEEP)
                break
            }
            else {
                Log.d(TAG, "[ACTIVE] Still in active range continue looping ... (maxSpeedLast25Seconds: $maxSpeedLast25s m/s)")
            }

            delay(1000L.milliseconds)
        }
    }

    private suspend fun handleLightSleep() {
        Log.d(TAG, "[LIGHT-SLEEP] Sleeping...")
        stopLocationUpdates()
        stopAccelerometer()

        // remove unallocated points older than 1 minute
        AppDatabase.getDatabase(this).motionDao().cleanOldUnusedData(System.currentTimeMillis() - 60000)
        
        while (currentState == State.LIGHT_SLEEP) {
            yield()
            startLocationUpdates(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5000L)
            startAccelerometer()

            val points = collectData(7, 1000L)
            val maxSpeedLast7s = points.maxOfOrNull { it.speed } ?: 0f
            
            val inRangeActive = cachedBarriersWithAutoTriggerOpted.any { isInRangeToReach(it, maxSpeedLast7s, 2f, 30f) }

            if (inRangeActive) {
                if (maxSpeedLast7s < SLOW_WALKING_SPEED) {
                    Log.d(TAG, "[LIGHT-SLEEP] In active range but slow moving ... (maxSpeedLast7s: $maxSpeedLast7s m/s)")
                }
                else {
                    Log.d(TAG, "[LIGHT-SLEEP] In active range and moving, transitioning to [ACTIVE] (maxSpeedLast7s: $maxSpeedLast7s m/s)")
                    transitionTo(State.ACTIVE)
                    break
                }
            }

            val inRangeLight = cachedBarriersWithAutoTriggerOpted.any { isInRangeToReach(it, maxSpeedLast7s, 4f, 30f) }

            if (!inRangeLight) {
                Log.d(TAG, "[LIGHT-SLEEP] In deep range, transitioning to [DEEP-SLEEP] (maxSpeedLast7s: $maxSpeedLast7s m/s)")
                transitionTo(State.DEEP_SLEEP)
                break
            }
            else if (maxSpeedLast7s < SLOW_BIKING_SPEED) {
                Log.d(TAG, "[LIGHT-SLEEP] In light range but slow moving, transitioning to [DEEP-SLEEP] (maxSpeedLast7s: $maxSpeedLast7s m/s)")
                transitionTo(State.DEEP_SLEEP)
                break
            }
            else {
                val sleepMillis = calculateSleepTime(cachedBarriersWithAutoTriggerOpted, maxSpeedLast7s, false)
                
                Log.d(TAG, "[LIGHT-SLEEP] In light range and moving, sleeping for ${sleepMillis}ms (maxSpeedLast7s: $maxSpeedLast7s m/s, minDistance: ${minDistance}m)")
                stopLocationUpdates()
                stopAccelerometer()

                delay(sleepMillis.milliseconds)
            }
        }
    }

    private suspend fun handleDeepSleep() {
        Log.d(TAG, "[DEEP-SLEEP] Deep sleeping...")
        stopLocationUpdates()
        stopAccelerometer()

        // remove unallocated points older than 1 minute
        val motionDao = AppDatabase.getDatabase(this).motionDao()
        motionDao.cleanOldUnusedData(System.currentTimeMillis() - 60000)

        // remove sessions from database that have less than 30 points and are older than a week
        val oneWeekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        motionDao.cleanShortOldSessions(oneWeekAgo, 30)
        
        while (currentState == State.DEEP_SLEEP) {
            yield()
            startLocationUpdates(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10000L)
            startAccelerometer()

            val points = collectData(5, 1000L)
            val maxSpeedLast5s = points.maxOfOrNull { it.speed } ?: 0f
            
            val inRangeLight = cachedBarriersWithAutoTriggerOpted.any {
                isInRangeToReach(it, maxSpeedLast5s, 4f, 30f)
            }

            if (inRangeLight) {
                if (maxSpeedLast5s < SLOW_BIKING_SPEED) {
                    Log.d(TAG, "[DEEP-SLEEP] In light range but slow moving ... (maxSpeedLast5s: $maxSpeedLast5s m/s)")
                }
                else {
                    Log.d(TAG, "[DEEP-SLEEP] In light range and moving, transitioning to [LIGHT_SLEEP] (maxSpeedLast5s: $maxSpeedLast5s m/s)")
                    transitionTo(State.LIGHT_SLEEP)
                    break
                }
            }

            val inRangeDeep = cachedBarriersWithAutoTriggerOpted.any {
                isInRangeToReach(it, maxSpeedLast5s, 8f, 30f)
            }

            if (!inRangeDeep || (maxSpeedLast5s < SLOW_CAR_SPEED)) {
                Log.d(TAG, "[DEEP-SLEEP] Outside of deep range or slow moving, waiting for significant motion or guard timeout")
                stopLocationUpdates()
                stopAccelerometer()
                registerSignificantMotion()

                withTimeoutOrNull(GUARD_TIMEOUT.milliseconds) {
                    suspendCancellableCoroutine<Unit> { /* Stay suspended until cancelled, triggered, or timeout */ }
                }
                sensorManager.cancelTriggerSensor(significantMotionListener, sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION))
                Log.d(TAG, "[DEEP-SLEEP] Resuming from suspension (motion or timeout)")
            }
            else {
                val sleepMillis = calculateSleepTime(cachedBarriersWithAutoTriggerOpted, maxSpeedLast5s, true)

                Log.d(TAG, "[DEEP-SLEEP] In deep range, sleeping for ${sleepMillis}ms (maxSpeedLast5s: $maxSpeedLast5s m/s, minDistance: ${minDistance}m)")
                stopLocationUpdates()
                stopAccelerometer()

                delay(sleepMillis.milliseconds)
            }
        }
    }

    private fun transitionTo(newState: State) {
        if (_currentState.value != newState) {
            Log.d(TAG, "Transitioning from ${_currentState.value} to $newState")
            _currentState.value = newState
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
        Log.d(TAG, "Saving point: $point")
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

    private fun calculateSleepTime(barriers:List<Barrier>, currentSpeed: Float, bDeepSleep: Boolean): Long {

        var minDistance = Float.MAX_VALUE
        barriers.forEach {
            val d = getDistanceTo(it)
            if (d < minDistance) minDistance = d
        }

        var minSleep = MIN_SLEEP_LIGHT
        var maxSleep = MAX_SLEEP_LIGHT
        var currentSpeedMultiplied = currentSpeed * 4f
        if (bDeepSleep) {
            minSleep = MIN_SLEEP_DEEP
            maxSleep = MAX_SLEEP_DEEP
            currentSpeedMultiplied = currentSpeed * 8f
        }

        val safeSpeed = kotlin.math.max(currentSpeedMultiplied, LOW_SLOW_SPEED) // e.g. at least 0.1m/s to avoid division by zero or infinite sleep
        val timeToArrival = minDistance / safeSpeed
        val sleepSeconds = timeToArrival - TARGET_BUFFER_TIME
        val sleepMillis = (sleepSeconds * 1000L).toLong()
        return sleepMillis.coerceIn(minSleep, maxSleep)
    }

    private fun updateWidget(barrier: Barrier?) {
        val prefs = AppPreferences(this)
        if (barrier != null) {
            prefs.setWidgetBarrierInfo(
                barrier.shortName, barrier.color,
                barrier.phoneNumberTo, barrier.phoneNumberFrom,
                barrier.id)
        } else {
            prefs.setWidgetBarrierInfoToEmpty()
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

        // try match the path with the medoids
        val matchResultJson = withContext(Dispatchers.Default) {
            NativeLib.matchPathWithBarrierMedoids(last30Points.toTypedArray(), medoids.toTypedArray())
        }

        val gson = Gson()
        val matchResult = try {
            gson.fromJson(matchResultJson, MatchResultJson::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing match result JSON", e)
            MatchResultJson.empty
        }
        
        if (matchResult.hasMatch) {
            Log.d(TAG, "AUTO-TRIGGER matched for barrier: ${barrier.shortName} [${barrier.id}]. Result: $matchResultJson")

            db.barrierDao().update(barrier.copy(countAutoTriggered = barrier.countAutoTriggered + 1))

            appPreferences.setBarrierIdLastLiftTimestamp(barrier.id, System.currentTimeMillis())

            CallRepository.triggerOneRing(
                barrier.phoneNumberTo,
                barrier.phoneNumberFrom
            ) { /* handle success/fail if needed */ }

            showMatchNotification(barrier, matchResult, matchResultJson)
        }
        else {
            Log.d(TAG, "AUTO-TRIGGER not matched for barrier: ${barrier.shortName} [${barrier.id}]. Result: $matchResultJson")

            showMatchNotification(barrier, matchResult, matchResultJson)
        }
    }

    private fun showMatchNotification(barrier: Barrier, result: MatchResultJson, rawJson: String) {
        val title = if (result.hasMatch) "Auto-Triggered: ${barrier.shortName}" else "No match to auto-trigger: ${barrier.shortName}"

        val builder = NotificationCompat.Builder(this, MATCH_CHANNEL_ID)
            .setSmallIcon(R.drawable.sv_fontawesome_road_barrier_s_f)
            .setContentTitle(title)
            .setContentText("Best distance: ${String.format(Locale.US, "%.2f", result.bestDistance)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Barrier ID: ${barrier.id}\n" +
                "Best Medoid Cluster ID: ${result.bestMedoidClusterId}\n" +
                "Best Medoid Session ID: ${result.bestMedoidSessionId}\n" +
                "Best Distance: ${result.bestDistance}\n" +
                "Distances: ${result.distanceToEachMedoid}\n" +
                "Full Result: $rawJson"
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(this).notify(MATCH_NOTIFICATION_BASE_ID + barrier.id, builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission missing")
        }
    }

    private data class MatchResultJson(
        val hasMatch: Boolean,
        val bestDistance: Double,
        val bestMedoidClusterId: Int,
        val bestMedoidSessionId: Long,
        val distanceToEachMedoid: List<List<Double>>
    ) {
        companion object {
            val empty = MatchResultJson(false, -1.0, -1, -1L, emptyList())
        }
    }

    private fun registerSignificantMotion() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (sensor != null) {
            sensorManager.requestTriggerSensor(significantMotionListener, sensor)
        }
    }

    private fun startLocationUpdates(priority: Int = Priority.PRIORITY_HIGH_ACCURACY, interval: Long = 1000L) {
        if (isLocationActive) {
            // Check if existing update settings match or if we need to restart
            // For simplicity, we restart if active to apply new priority/interval
            stopLocationUpdates()
        }
        val request = LocationRequest.Builder(priority, interval).build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooper)
            isLocationActive = true
            Log.d(TAG, "Location updates started: Priority $priority, Interval ${interval}ms")
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
        val channel = NotificationChannel(CHANNEL_ID, "Monitoring paths-to-barriers", NotificationManager.IMPORTANCE_LOW)
        val matchChannel = NotificationChannel(MATCH_CHANNEL_ID, "Auto-Trigger Matches", NotificationManager.IMPORTANCE_HIGH)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(matchChannel)
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
            State.LIGHT_SLEEP -> {
                val wakeIntent = Intent(this, ControlReceiver::class.java).apply { action = ACTION_WAKE_LIGHT }
                val pWake = PendingIntent.getBroadcast(this, 3, wakeIntent, PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(android.R.drawable.ic_media_play, "Wake", pWake)
            }
            State.DEEP_SLEEP -> {
                val wakeIntent = Intent(this, ControlReceiver::class.java).apply { action = ACTION_WAKE_DEEP }
                val pWake = PendingIntent.getBroadcast(this, 4, wakeIntent, PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(android.R.drawable.ic_media_play, "Wake", pWake)
            }
            else -> {}
        }

        return builder.build()
    }
}
