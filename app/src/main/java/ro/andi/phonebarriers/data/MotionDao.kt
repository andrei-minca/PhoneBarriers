package ro.andi.phonebarriers.data

import androidx.room.*

@Dao
interface MotionDao {
    @Insert
    suspend fun insert(point: MotionPoint)

    @Query("SELECT * FROM motion_data WHERE barrierId = :barrierId AND sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getSessionData(barrierId: Int, sessionId: Long): List<MotionPoint>

    @Query("SELECT * FROM motion_data ORDER BY timestamp ASC")
    suspend fun getData(): List<MotionPoint>

    @Query("UPDATE motion_data SET sessionId = :sessionId, barrierId = :barrierId WHERE sessionId IS NULL AND timestamp > :threshold")
    suspend fun tagRecentPoints(sessionId: Long, barrierId: Int, threshold: Long)

    @Query("DELETE FROM motion_data WHERE sessionId IS NULL AND timestamp < :threshold")
    suspend fun cleanOldUnusedData(threshold: Long)
}