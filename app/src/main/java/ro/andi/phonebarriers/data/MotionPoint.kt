package ro.andi.phonebarriers.data
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "motion_data")
data class MotionPoint(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: Long?, // Unique ID for each 5-second trigger
    val timestamp: Long,
    val accuracy: Float,
    val lat: Double,
    val lng: Double,
    val alt: Double,
    val speed: Float,
    val acceleration: Float
)