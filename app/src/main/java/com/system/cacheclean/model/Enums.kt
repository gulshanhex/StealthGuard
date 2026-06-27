package com.system.cacheclean.model

/**
 * Gender
 *
 * Maps a CallerPersona to the correct audio sub-directory.
 * GENTS → filesDir/audio/gents/
 * LADY  → filesDir/audio/lady/
 *
 * Stored as String in Room DB ("GENTS" / "LADY") so the DB is
 * readable without the enum class if ever inspected externally.
 */
enum class Gender {
    GENTS,
    LADY;

    val dirName: String get() = name.lowercase()  // "gents" or "lady"
}

/**
 * AudioType
 *
 * Determines which sub-directory within a gender folder an audio
 * file lives in, and how the engine uses it.
 *
 *   HOOK      → played once at call start (only one is "active" per gender)
 *   RESPONSE  → played when a specific keyword is matched via STT
 *   FILLER    → played randomly when STT times out or no keyword matches
 */
enum class AudioType {
    HOOK,
    RESPONSE,
    FILLER;

    val dirName: String get() = name.lowercase() + "s"
    // HOOK → "hooks", RESPONSE → "responses", FILLER → "fillers"
}
