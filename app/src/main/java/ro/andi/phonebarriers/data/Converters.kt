package ro.andi.phonebarriers.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromLiftSource(value: LiftSource): String = value.name

    @TypeConverter
    fun toLiftSource(value: String): LiftSource = LiftSource.valueOf(value)

    @TypeConverter
    fun fromLiftOutcome(value: LiftOutcome): String = value.name

    @TypeConverter
    fun toLiftOutcome(value: String): LiftOutcome = LiftOutcome.valueOf(value)
}
