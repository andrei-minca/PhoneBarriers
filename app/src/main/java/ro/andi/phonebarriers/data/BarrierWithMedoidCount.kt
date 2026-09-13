package ro.andi.phonebarriers.data

import androidx.room.Embedded

data class BarrierWithMedoidCount(
    @Embedded val barrier: Barrier,
    val medoidCount: Int
)
