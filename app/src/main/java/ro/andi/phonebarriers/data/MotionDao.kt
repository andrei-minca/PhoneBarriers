package ro.andi.phonebarriers.data

import androidx.room.*

@Dao
interface MotionDao {
    @Insert
    suspend fun insert(point: MotionPoint)

    @Query("SELECT * FROM motion_data WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getSessionData(sessionId: Long): List<MotionPoint>

    @Query("SELECT * FROM motion_data ORDER BY timestamp ASC")
    suspend fun getData(): List<MotionPoint>

    @Query("UPDATE motion_data SET sessionId = :sessionId WHERE sessionId IS NULL AND timestamp > :threshold")
    suspend fun tagRecentPoints(sessionId: Long, threshold: Long)

    @Query("DELETE FROM motion_data WHERE sessionId IS NULL AND timestamp < :threshold")
    suspend fun cleanOldUnusedData(threshold: Long)
}