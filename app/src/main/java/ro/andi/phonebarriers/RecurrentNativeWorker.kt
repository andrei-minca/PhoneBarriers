package ro.andi.phonebarriers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.andi.phonebarriers.data.AppDatabase

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
            Log.d("RecurrentNativeWorker", "Processing barrier $barrierId with ${points.size} points")
            
            val resultArray = points.toTypedArray()
            val classifyResult = withContext(Dispatchers.Default) {
                NativeLib.dtwClassifyAndFindMedoidsForPathsAndAnchors(resultArray)
            }

            Log.d("RecurrentNativeWorker",
                "C++ processed ${points.size} points for barrier $barrierId " +
                        "and resulted with: $classifyResult")
        }

        return Result.success()
    }
}
