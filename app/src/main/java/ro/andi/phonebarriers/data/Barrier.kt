package ro.andi.phonebarriers.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "barriers")
data class Barrier(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val shortName: String,
    val description: String,
    val color: Int,
    val phoneNumberTo: String,
    val phoneNumberFrom: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Float, // Trigger radius in meters
    val isEnabled: Boolean = true
)
