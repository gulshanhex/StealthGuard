package com.system.cacheclean.db.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.system.cacheclean.db.entity.KeywordAudioEntity
import com.system.cacheclean.db.entity.PersonaEntity
import com.system.cacheclean.db.entity.SosContactEntity

// ═════════════════════════════════════════════════════════════════════════════
// KeywordAudioDao
// ═════════════════════════════════════════════════════════════════════════════

@Dao
interface KeywordAudioDao {

    /**
     * Observe all mappings as LiveData — RecyclerView updates automatically
     * whenever the user adds or deletes a keyword mapping.
     */
    @Query("SELECT * FROM keyword_audio_map ORDER BY keyword ASC")
    fun getAllMappings(): LiveData<List<KeywordAudioEntity>>

    /**
     * Used by the STT state machine during a live call.
     * Synchronous (no suspend) because it runs on a background thread
     * inside CallStateManager, not on the main thread.
     *
     * @param keyword The word/phrase recognised by SpeechRecognizer.
     * @return The matching entity, or null if no mapping exists.
     */
    @Query("SELECT * FROM keyword_audio_map WHERE keyword = :keyword LIMIT 1")
    fun findByKeyword(keyword: String): KeywordAudioEntity?

    /**
     * Upsert — inserts if keyword is new, replaces if keyword already exists.
     * OnConflictStrategy.REPLACE triggers on the unique index for `keyword`.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: KeywordAudioEntity)

    @Update
    suspend fun update(entity: KeywordAudioEntity)

    @Delete
    suspend fun delete(entity: KeywordAudioEntity)

    @Query("DELETE FROM keyword_audio_map WHERE id = :id")
    suspend fun deleteById(id: Int)

    /**
     * BUG-10 FIX: Null out the GENTS audio path for any row that points
     * to the given file path. Called when a GENTS response audio file is
     * deleted from disk so the DB doesn't hold a stale reference.
     */
    @Query("UPDATE keyword_audio_map SET gentsAudioPath = NULL WHERE gentsAudioPath = :path")
    suspend fun clearGentsPath(path: String)

    /**
     * BUG-10 FIX: Null out the LADY audio path for any row that points
     * to the given file path.
     */
    @Query("UPDATE keyword_audio_map SET ladyAudioPath = NULL WHERE ladyAudioPath = :path")
    suspend fun clearLadyPath(path: String)
}


// ═════════════════════════════════════════════════════════════════════════════
// PersonaDao
// ═════════════════════════════════════════════════════════════════════════════

@Dao
interface PersonaDao {

    /** All personas for the Persona Manager RecyclerView. */
    @Query("SELECT * FROM caller_personas ORDER BY displayName ASC")
    fun getAllPersonas(): LiveData<List<PersonaEntity>>

    /**
     * Random persona selection for a new call session.
     * Only enabled personas enter the pool.
     * RANDOM() in SQLite is efficient enough for a small table.
     * Returns null if ALL personas are disabled (edge case — UI should warn).
     */
    @Query("SELECT * FROM caller_personas WHERE isEnabled = 1 ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomEnabledPersona(): PersonaEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PersonaEntity)

    @Update
    suspend fun update(entity: PersonaEntity)

    @Delete
    suspend fun delete(entity: PersonaEntity)

    @Query("UPDATE caller_personas SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Int, enabled: Boolean)

    /** Count of enabled personas — used to prevent disabling the last one. */
    @Query("SELECT COUNT(*) FROM caller_personas WHERE isEnabled = 1")
    suspend fun countEnabled(): Int
}


// ═════════════════════════════════════════════════════════════════════════════
// SosContactDao
// ═════════════════════════════════════════════════════════════════════════════

@Dao
interface SosContactDao {

    /** All contacts for the SOS Settings RecyclerView. */
    @Query("SELECT * FROM sos_contacts ORDER BY nickname ASC")
    fun getAllContacts(): LiveData<List<SosContactEntity>>

    /**
     * Enabled contacts — used by SOSManager at send time.
     * Synchronous because SOSManager runs this on its own background thread.
     */
    @Query("SELECT * FROM sos_contacts WHERE isEnabled = 1")
    fun getEnabledContactsSync(): List<SosContactEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SosContactEntity)

    @Update
    suspend fun update(entity: SosContactEntity)

    @Delete
    suspend fun delete(entity: SosContactEntity)

    @Query("UPDATE sos_contacts SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Int, enabled: Boolean)

    @Query("SELECT COUNT(*) FROM sos_contacts")
    suspend fun count(): Int
}
