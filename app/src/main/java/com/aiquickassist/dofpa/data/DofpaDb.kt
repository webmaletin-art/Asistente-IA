package com.aiquickassist.dofpa.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DofpaDocument::class, DofpaPassage::class, PassageFts::class, DofpaNote::class],
    version = 1,
    exportSchema = false
)
abstract class DofpaDb : RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun passages(): PassageDao
    abstract fun notes(): NoteDao

    companion object {
        @Volatile private var instance: DofpaDb? = null
        fun get(ctx: Context): DofpaDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, DofpaDb::class.java, "dofpa.db")
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
