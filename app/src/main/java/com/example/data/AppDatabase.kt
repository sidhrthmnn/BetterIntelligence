package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ModelEntity::class,
        IpcLogEntity::class,
        ClientAppPolicyEntity::class,
        ChatMessageEntity::class,
        MemorySnippetEntity::class,
        HardwarePerformanceLogEntity::class,
        ConversationEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun ipcLogDao(): IpcLogDao
    abstract fun clientPolicyDao(): ClientPolicyDao
    abstract fun chatDao(): ChatDao
    abstract fun memoryDao(): MemorySnippetDao
    abstract fun performanceLogDao(): HardwarePerformanceLogDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN contextSummary TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE conversations ADD COLUMN summaryThroughId INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS conversations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, draft TEXT NOT NULL, groundingMode TEXT NOT NULL)")
                db.execSQL("INSERT INTO conversations (id,title,createdAt,updatedAt,draft,groundingMode) SELECT 1,'Previous chat',created,updated,'','OFF' FROM (SELECT MIN(timestamp) AS created,MAX(timestamp) AS updated,COUNT(*) AS count FROM chat_messages) WHERE count > 0")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN conversationId INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN sourcesJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN groundingStatus TEXT NOT NULL DEFAULT 'LOCAL'")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN messageState TEXT NOT NULL DEFAULT 'COMPLETE'")
            }
        }
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "local_ai_core.db"
                ).addMigrations(MIGRATION_7_8, MIGRATION_8_9).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
