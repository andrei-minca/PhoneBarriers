package ro.andi.phonebarriers.data

import androidx.room.*

@Dao
interface BarrierDao {
    @Query("SELECT * FROM barriers")
    suspend fun getAll(): List<Barrier>

    @Query("SELECT * FROM barriers WHERE id = :id")
    suspend fun getById(id: Int): Barrier?

    @Insert
    suspend fun insert(barrier: Barrier): Long

    @Update
    suspend fun update(barrier: Barrier)

    @Delete
    suspend fun delete(barrier: Barrier)
}
