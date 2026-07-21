package ro.andi.phonebarriers.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MotionPoint::class, Barrier::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun motionDao(): MotionDao
    abstract fun barrierDao(): BarrierDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            // If the INSTANCE is not null, then return it,
            // if it is, then create the database
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "motion_database"
                )
                    // Wipes and rebuilds instead of migrating if you change the schema
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}