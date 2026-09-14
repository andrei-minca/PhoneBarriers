package ro.andi.phonebarriers.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LiftEventDao {
    @Insert
    suspend fun insert(event: LiftEvent)

    @Query("SELECT * FROM lift_events WHERE barrierId = :barrierId ORDER BY timestamp DESC")
    fun getEventsForBarrier(barrierId: Int): Flow<List<LiftEvent>>

    @Query("SELECT COUNT(*) FROM lift_events WHERE barrierId = :barrierId AND outcome = 'FAILED'")
    suspend fun getFailedCount(barrierId: Int): Int

    @Query("SELECT COUNT(*) FROM lift_events WHERE barrierId = :barrierId AND outcome = 'SUCCESS' AND source = :source")
    suspend fun getSuccessCountBySource(barrierId: Int, source: LiftSource): Int
}
