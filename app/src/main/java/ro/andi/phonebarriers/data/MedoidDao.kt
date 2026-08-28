package ro.andi.phonebarriers.data

import androidx.room.*

@Dao
interface MedoidDao {
    @Query("SELECT * FROM medoid_points WHERE barrierId = :barrierId")
    suspend fun getMedoidsForBarrier(barrierId: Int): List<MedoidPoint>

    @Query("SELECT * FROM medoid_points")
    suspend fun getAllMedoids(): List<MedoidPoint>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<MedoidPoint>)

    @Query("DELETE FROM medoid_points WHERE barrierId = :barrierId")
    suspend fun deleteForBarrier(barrierId: Int)

    @Query("DELETE FROM medoid_points")
    suspend fun clearAll()
}
