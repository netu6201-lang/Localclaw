package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FileIndexEntity::class,
        FileIndexFtsEntity::class,
        AgentMemoryEntity::class,
        ActionLogEntity::class,
        ModelRecordEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class EdgeClawDatabase : RoomDatabase() {
    abstract fun fileIndexDao(): FileIndexDao
    abstract fun agentMemoryDao(): AgentMemoryDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun modelRecordDao(): ModelRecordDao

    companion object {
        @Volatile
        private var INSTANCE: EdgeClawDatabase? = null

        fun getInstance(context: Context): EdgeClawDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EdgeClawDatabase::class.java,
                    "edgeclaw_local.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
