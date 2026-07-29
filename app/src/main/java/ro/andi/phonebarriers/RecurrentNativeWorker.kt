package ro.andi.phonebarriers

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.andi.phonebarriers.data.AppDatabase
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class RecurrentNativeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("RecurrentNativeWorker", "Starting recurrent background task...")

        val db = AppDatabase.getDatabase(applicationContext)
        val allPoints = db.motionDao().getData()
        
        // Group points by barrierId. 
        // Note: barrierId is nullable in the entity, but for classification 
        // we only care about tagged points.
        val groups = allPoints.filter { it.barrierId != null }.groupBy { it.barrierId }

        Log.d("RecurrentNativeWorker", "Found data for ${groups.size} barriers")

        groups.forEach { (barrierId, points) ->
            if (barrierId == null) return@forEach

            Log.d("RecurrentNativeWorker", "Processing barrier $barrierId with ${points.size} points")
            
            val resultArray = points.toTypedArray()
            val classifyResult = withContext(Dispatchers.Default) {
                NativeLib.dtwClassifyAndFindMedoidsForPathsAndAnchors(resultArray)
            }

            Log.d("RecurrentNativeWorker",
                "C++ processed ${points.size} points for barrier $barrierId " +
                        "and resulted with: $classifyResult")

            // Send notification for this barrier
            showResultNotification(barrierId, classifyResult)
        }

        return Result.success()
    }

    private suspend fun showResultNotification(barrierId: Int, result: String) {
        val db = AppDatabase.getDatabase(applicationContext)
        val barrier = db.barrierDao().getById(barrierId)
        val barrierName = barrier?.name ?: "Unknown Barrier"

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
}
