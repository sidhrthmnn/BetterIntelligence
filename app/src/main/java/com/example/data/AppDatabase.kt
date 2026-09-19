package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ModelEntity::class,
        IpcLogEntity::class,
        ClientAppPolicyEntity::class,
        ChatMessageEntity::class,
        MemorySnippetEntity::class,
        HardwarePerformanceLogEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun ipcLogDao(): IpcLogDao
    abstract fun clientPolicyDao(): ClientPolicyDao
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemorySnippetDao
    abstract fun performanceLogDao(): HardwarePerformanceLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "local_ai_core.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
