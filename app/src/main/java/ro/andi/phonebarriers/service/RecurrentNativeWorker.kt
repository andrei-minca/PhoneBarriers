package ro.andi.phonebarriers.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.andi.phonebarriers.NativeLib
import ro.andi.phonebarriers.R
import ro.andi.phonebarriers.data.AppDatabase
import ro.andi.phonebarriers.data.MedoidPoint

class RecurrentNativeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("RecurrentNativeWorker", "Starting recurrent background task...")

        val db = AppDatabase.getDatabase(applicationContext)
        val allPoints = db.motionDao().getPointsWithBarrier()

        if (allPoints.isEmpty()) {
            Log.d("RecurrentNativeWorker", "No points with barrierId found, finishing.")
            return Result.success()
        }

        val groups = allPoints.groupBy { it.barrierId }

        Log.d("RecurrentNativeWorker", "Found data for ${groups.size} barriers")

        groups.forEach { (barrierId, points) ->
            if (isStopped) {
                Log.d("RecurrentNativeWorker", "Worker stopped, cancelling processing.")
                return Result.retry()
            }
            if (barrierId == null) return@forEach

            Log.d("RecurrentNativeWorker", "Processing barrier $barrierId with ${points.size} points")

            try {
                val resultArray = points.toTypedArray()
                val classifyResult = withContext(Dispatchers.Default) {
                    NativeLib.dtwClassifyAndFindMedoidsForPathsAndAnchors(resultArray)
                }

                Log.d("RecurrentNativeWorker",
                    "C++ processed ${points.size} points for barrier $barrierId " +
                            "and resulted with: $classifyResult")

                // Save results to MedoidPoint table
                saveMedoidResults(barrierId, classifyResult)

                // Send notification for this barrier
                showResultNotification(barrierId, classifyResult)
            } catch (e: Exception) {
                Log.e("RecurrentNativeWorker", "Error processing barrier $barrierId", e)
            }
        }

        return Result.success()
    }

    private suspend fun showResultNotification(barrierId: Int, result: String) {
        val db = AppDatabase.getDatabase(applicationContext)
        val barrier = db.barrierDao().getById(barrierId)
        val barrierName = barrier?.shortName ?: "Unknown Barrier"

        val builder = NotificationCompat.Builder(applicationContext, "classification_results_channel")
            .setSmallIcon(R.drawable.sv_fontawesome_road_barrier_s_f)
            .setContentTitle("Barrier Processed: $barrierName [$barrierId]")
            .setContentText(result)
            .setStyle(NotificationCompat.BigTextStyle().bigText(result))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(applicationContext)) {
            if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
                // Use barrierId as notification ID so each barrier gets its own entry
                notify(barrierId + 1000, builder.build())
            }
        }
    }

    private suspend fun saveMedoidResults(barrierId: Int, jsonResult: String) {
        withContext(Dispatchers.IO) {
            try {
                val gson = Gson()
                val result = gson.fromJson(jsonResult, DtwResultJson::class.java) ?: return@withContext

                val db = AppDatabase.getDatabase(applicationContext)
                val medoidDao = db.medoidDao()

                val pointsToInsert = mutableListOf<MedoidPoint>()

                // 2. Map medoid points
                // Invert medoidsSid to have SessionId -> ClusterId
                val sidToCluster = result.medoidsSid.entries.associate { (clusterStr, sid) ->
                    sid to (clusterStr.toIntOrNull() ?: 0)
                }

                result.medoidsRelativePoints.forEach { rp ->
                    val clusterId = sidToCluster[rp.sessionId] ?: 0
                    pointsToInsert.add(
                        MedoidPoint(
                            barrierId = barrierId,
                            clusterId = clusterId,
                            sessionId = rp.sessionId,
                            timestamp = rp.timestamp,
                            distance = rp.distance,
                            deltaHeading = rp.deltaHeading,
                            speed = rp.speed.toFloat(),
                            acceleration = rp.acceleration.toFloat()
                        )
                    )
                }

                // 3. Add normalization points (Mins and Maxs)
                // min: sessionId=0, clusterId=0, timestamp=-1
                pointsToInsert.add(
                    MedoidPoint(
                        barrierId = barrierId,
                        clusterId = 0,
                        sessionId = 0,
                        timestamp = -1,
                        distance = result.relativePointOfMins.distance,
                        deltaHeading = result.relativePointOfMins.deltaHeading,
                        speed = result.relativePointOfMins.speed.toFloat(),
                        acceleration = result.relativePointOfMins.acceleration.toFloat()
                    )
                )

                // max: sessionId=0, clusterId=0, timestamp=1
                pointsToInsert.add(
                    MedoidPoint(
                        barrierId = barrierId,
                        clusterId = 0,
                        sessionId = 0,
                        timestamp = 1,
                        distance = result.relativePointOfMaxs.distance,
                        deltaHeading = result.relativePointOfMaxs.deltaHeading,
                        speed = result.relativePointOfMaxs.speed.toFloat(),
                        acceleration = result.relativePointOfMaxs.acceleration.toFloat()
                    )
                )

                medoidDao.refreshMedoidsForBarrier(barrierId, pointsToInsert)
                Log.d("RecurrentNativeWorker", "Successfully saved ${pointsToInsert.size} medoid points for barrier $barrierId")

            } catch (e: Exception) {
                Log.e("RecurrentNativeWorker", "Error parsing or saving medoid results", e)
            }
        }
    }

    // JSON parsing helper classes
    private data class RelativePointJson(
        val sessionId: Long,
        val timestamp: Long,
        val distance: Double,
        val deltaHeading: Double,
        val speed: Double,
        val acceleration: Double
    )

    private data class DtwResultJson(
        val medoidsSid: Map<String, Long>,
        val medoidsRelativePoints: List<RelativePointJson>,
        val relativePointOfMins: RelativePointJson,
        val relativePointOfMaxs: RelativePointJson
    )
}
