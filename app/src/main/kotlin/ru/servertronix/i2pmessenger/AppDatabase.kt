package ru.servertronix.i2pmessenger.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ContactEntity::class, MessageEntity::class],
    version = 4,  // <- УВЕЛИЧЬ НА 1 (было 2)
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "i2p_messenger_db"
                )
                .fallbackToDestructiveMigration() // сброс при изменении схемы
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}