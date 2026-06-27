package com.system.cacheclean.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ═════════════════════════════════════════════════════════════════════════════
// KeywordAudioEntity
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Maps a spoken keyword to a pair of audio file paths (one per gender).
 *
 * WHY BOTH PATHS IN ONE ROW:
 *   A keyword like "market" needs a GENTS response AND a LADY response.
 *   Keeping both in one row makes keyword lookup a single DB query.
 *   AudioResolver picks which path to use based on the session's persona gender.
 *
 * UNIQUE INDEX on keyword:
 *   Prevents duplicate keyword entries. The keyword is the lookup key during
 *   the STT state machine — duplicates would cause ambiguous results.
 *
 * PATH STORAGE:
 *   We store absolute paths (filesDir-rooted). If the app is reinstalled,
 *   filesDir changes, so paths become stale. StorageManager.kt handles
 *   path validation and stale-entry cleanup.
 */
@Entity(
    tableName = "keyword_audio_map",
    indices = [Index(value = ["keyword"], unique = true)]
)
data class KeywordAudioEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,

    /** The spoken word/phrase that triggers this response (e.g., "market"). */
    val keyword: String,

    /**
     * Absolute path to the GENTS response audio file.
     * Null if no male-voice response has been recorded yet.
     */
    val gentsAudioPath: String? = null,

    /**
     * Absolute path to the LADY response audio file.
     * Null if no female-voice response has been recorded yet.
     */
    val ladyAudioPath: String? = null
)


// ═════════════════════════════════════════════════════════════════════════════
// PersonaEntity
// ═════════════════════════════════════════════════════════════════════════════

/**
 * A caller persona in the random selection pool for the fake call screen.
 *
 * On FakeCallActivity launch, PersonaRepository queries all enabled personas,
 * picks one at random, and locks it as the sessionPersona for that call.
 *
 * gender stored as String ("GENTS" or "LADY") — maps to Gender enum via
 * Gender.valueOf(gender) at runtime.
 *
 * DEFAULT PERSONAS (inserted via DatabaseCallback.onCreate):
 *   Papa, Bhai, Chachu  → GENTS
 *   Mom, Didi, Bua      → LADY
 */
@Entity(tableName = "caller_personas")
data class PersonaEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,

    /** Display name shown on the fake incoming call screen. */
    val displayName: String,

    /**
     * "GENTS" or "LADY" — determines which audio directory is used
     * for the entire call session when this persona is selected.
     */
    val gender: String,

    /**
     * If false, this persona is excluded from random selection.
     * User can disable personas they don't want to appear.
     */
    val isEnabled: Boolean = true
)


// ═════════════════════════════════════════════════════════════════════════════
// SosContactEntity
// ═════════════════════════════════════════════════════════════════════════════

/**
 * A trusted contact who receives the silent SOS SMS.
 *
 * Up to 3 contacts are recommended (carrier limits on bulk SMS).
 * SOSManager sends to ALL contacts in this table that are enabled.
 * Messages are staggered 500ms apart to avoid carrier throttling.
 */
@Entity(tableName = "sos_contacts")
data class SosContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,

    /** Phone number in any format — SmsManager handles formatting. */
    val phoneNumber: String,

    /** Friendly label for display in Admin Panel only (e.g., "Brother"). */
    val nickname: String,

    /** Whether this contact is included in SOS sends. Defaults to active. */
    val isEnabled: Boolean = true
)
