package ro.andi.phonebarriers.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class LiftSource {
    BUTTON,
    BUTTON_LIFT_AND_LEARN,
    WIDGET,
    AUTO_TRIGGER
}

enum class LiftOutcome {
    SUCCESS,
    FAILED
}

@Entity(tableName = "lift_events")
data class LiftEvent(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val barrierId: Int,
    val barrierName: String,
    val timestamp: Long,
    val source: LiftSource,
    val outcome: LiftOutcome,
    val reason: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speed: Float = 0f,
    val acceleration: Float = 0f
)
