package ro.andi.phonebarriers.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "medoid_points")
data class MedoidPoint(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val barrierId: Int,

    val clusterId: Int,
    val sessionId: Long,

    val timestamp: Long,

    val distance: Double,
    val deltaHeading: Double,
    val speed: Float,
    val acceleration: Float
)
