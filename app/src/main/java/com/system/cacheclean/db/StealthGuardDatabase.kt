package com.system.cacheclean.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.system.cacheclean.db.dao.KeywordAudioDao
import com.system.cacheclean.db.dao.PersonaDao
import com.system.cacheclean.db.dao.SosContactDao
import com.system.cacheclean.db.entity.KeywordAudioEntity
import com.system.cacheclean.db.entity.PersonaEntity
import com.system.cacheclean.db.entity.SosContactEntity

@Database(
    entities = [
        KeywordAudioEntity::class,
        PersonaEntity::class,
        SosContactEntity::class
    ],
    version = 1,
    // A-14: exportSchema=false meant there was no schema history saved on
    // disk — fallbackToDestructiveMigration() was the ONLY option for any
    // future version bump, even a trivial one, wiping all user data
    // (personas, SOS contacts, keyword mappings) on update. Exporting the
    // schema lets a real Migration(1, 2) be written later.
    exportSchema = true
)
abstract class StealthGuardDatabase : RoomDatabase() {

    abstract fun keywordAudioDao(): KeywordAudioDao
    abstract fun personaDao(): PersonaDao
    abstract fun sosContactDao(): SosContactDao

    companion object {
        @Volatile private var INSTANCE: StealthGuardDatabase? = null

        fun getInstance(context: Context): StealthGuardDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StealthGuardDatabase::class.java,
                    "stealthguard.db"
                )
                    .addCallback(PrepopulateCallback())
                    // WARNING: Remove this before any schema version bump in v2+.
                    // Add a Migration(n, n+1) instead.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }

    /**
     * Runs ONCE on first database creation.
     * Inserts the 6 default caller personas via raw SQL so we don't need
     * a coroutine scope at this point in the lifecycle.
     */
    private class PrepopulateCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            listOf(
                Pair("Papa",   "GENTS"),
                Pair("Bhai",   "GENTS"),
                Pair("Chachu", "GENTS"),
                Pair("Mom",    "LADY"),
                Pair("Didi",   "LADY"),
                Pair("Bua",    "LADY")
            ).forEach { (name, gender) ->
                db.execSQL(
                    "INSERT INTO caller_personas (displayName, gender, isEnabled) " +
                    "VALUES (?, ?, 1)",
                    arrayOf(name, gender)
                )
            }
        }
    }
}
