package com.system.cacheclean.storage

import android.content.Context
import android.util.Log
import com.system.cacheclean.model.AudioType
import com.system.cacheclean.model.Gender
import java.io.File

/**
 * StorageManager
 *
 * Single source of truth for the internal audio file system.
 * All path construction goes through this class — nothing else builds
 * paths manually.
 *
 * DIRECTORY TREE (all under context.filesDir):
 *
 *   audio/
 *   ├── gents/
 *   │   ├── hooks/
 *   │   ├── responses/
 *   │   └── fillers/
 *   └── lady/
 *       ├── hooks/
 *       ├── responses/
 *       └── fillers/
 *
 * All directories are created on first call to ensureDirectoriesExist().
 * Call this from Application.onCreate() to guarantee they exist before
 * any recording or playback attempt.
 *
 * FILE NAMING CONVENTION:
 *   {typePrefix}_{genderPrefix}_{epochMs}.m4a
 *   e.g.  hook_g_1718123456789.m4a
 *         resp_g_1718123456789.m4a
 *         fill_l_1718123456789.m4a
 */
class StorageManager(context: Context) {

    companion object {
        private const val TAG        = "StealthGuard_Storage"
        private const val AUDIO_ROOT = "audio"
    }

    private val filesDir: File = context.filesDir

    // ─── Directory Resolution ─────────────────────────────────────────────────

    /** Root: filesDir/audio/ */
    val audioRoot: File get() = File(filesDir, AUDIO_ROOT)

    /**
     * Returns the specific sub-directory for a given gender + audio type.
     * e.g. getDir(GENTS, HOOK) → filesDir/audio/gents/hooks/
     */
    fun getDir(gender: Gender, type: AudioType): File =
        File(audioRoot, "${gender.dirName}/${type.dirName}")

    // ─── Directory Initialisation ─────────────────────────────────────────────

    /**
     * Creates the full directory tree if it doesn't exist.
     * Safe to call multiple times (mkdirs() is idempotent).
     * Call once from StealthGuardApp.onCreate().
     */
    fun ensureDirectoriesExist() {
        Gender.entries.forEach { gender ->
            AudioType.entries.forEach { type ->
                val dir = getDir(gender, type)
                if (!dir.exists()) {
                    val created = dir.mkdirs()
                    Log.d(TAG, "Created ${dir.path}: $created")
                } else {
                    Log.d(TAG, "Dir exists: ${dir.path}")
                }
            }
        }
    }

    // ─── File Generation ──────────────────────────────────────────────────────

    /**
     * Generates a new unique File object (not yet created on disk) for a
     * new recording. MediaRecorder will create the actual file.
     *
     * @param gender GENTS or LADY
     * @param type   HOOK, RESPONSE, or FILLER
     * @return A File with a unique timestamped name in the correct directory.
     */
    fun newRecordingFile(gender: Gender, type: AudioType): File {
        val prefix = when (type) {
            AudioType.HOOK     -> "hook"
            AudioType.RESPONSE -> "resp"
            AudioType.FILLER   -> "fill"
        }
        val genderCode = if (gender == Gender.GENTS) "g" else "l"
        val timestamp  = System.currentTimeMillis()
        val fileName   = "${prefix}_${genderCode}_${timestamp}.m4a"
        return File(getDir(gender, type), fileName)
    }

    // ─── File Listing ─────────────────────────────────────────────────────────

    /**
     * Lists all .m4a files in a directory, sorted by last-modified descending
     * (most recently recorded first).
     */
    fun listFiles(gender: Gender, type: AudioType): List<File> {
        val dir = getDir(gender, type)
        return dir.listFiles { f -> f.extension == "m4a" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    // ─── Active Hook Tracking ─────────────────────────────────────────────────
    //
    // Only ONE hook audio is "active" per gender — the one played at call start.
    // We store the active filename (not path) in a simple properties file.
    // Using filename only (not full path) makes this resilient to filesDir
    // changes on app reinstall.

    private val hookPrefsFile: File get() = File(filesDir, "hook_prefs.properties")

    @Synchronized
    private fun loadHookPrefs(): java.util.Properties {
        val props = java.util.Properties()
        if (hookPrefsFile.exists()) {
            hookPrefsFile.inputStream().use { props.load(it) }
        }
        return props
    }

    @Synchronized
    private fun saveHookPrefs(props: java.util.Properties) {
        hookPrefsFile.outputStream().use { props.store(it, null) }
    }

    /** Returns the currently active hook File for a gender, or null if none set. */
    fun getActiveHook(gender: Gender): File? {
        val fileName = loadHookPrefs().getProperty("active_hook_${gender.dirName}")
            ?: return null
        val file = File(getDir(gender, AudioType.HOOK), fileName)
        return if (file.exists()) file else null
    }

    /** Sets a hook file as the active one for its gender. */
    fun setActiveHook(gender: Gender, file: File) {
        val props = loadHookPrefs()
        props.setProperty("active_hook_${gender.dirName}", file.name)
        saveHookPrefs(props)
    }

    /** Clears the active hook (e.g., when the active file is deleted). */
    fun clearActiveHook(gender: Gender) {
        val props = loadHookPrefs()
        props.remove("active_hook_${gender.dirName}")
        saveHookPrefs(props)
    }

    // ─── Custom Caller ID (A-11) ──────────────────────────────────────────────
    //
    // Replaces the old random-number generator in FakeCallActivity. The user
    // sets their own number (any country code) once in Setup; every fake call
    // then shows that exact number instead of a randomly generated Indian one.

    /** Returns the user-set caller ID, or null if never configured. */
    fun getCustomCallerId(): String? =
        loadHookPrefs().getProperty("custom_caller_id")?.takeIf { it.isNotBlank() }

    /** Sets the caller ID shown on the fake call screen. */
    fun setCustomCallerId(number: String) {
        val props = loadHookPrefs()
        props.setProperty("custom_caller_id", number.trim())
        saveHookPrefs(props)
    }

    // ─── Stale Path Validation ────────────────────────────────────────────────

    /**
     * Checks if an absolute path stored in Room DB still points to a real file.
     * Call this before using any path retrieved from KeywordAudioEntity.
     */
    fun isPathValid(path: String?): Boolean =
        path != null && File(path).exists()

    /**
     * Deletes a file and logs the result.
     * @return true if deleted successfully.
     */
    fun deleteFile(file: File): Boolean {
        val deleted = file.delete()
        Log.d(TAG, "Delete ${file.name}: $deleted")
        return deleted
    }

    /**
     * Returns human-readable file size string.
     */
    fun fileSizeLabel(file: File): String {
        val bytes = file.length()
        return when {
            bytes < 1_024       -> "${bytes}B"
            bytes < 1_048_576   -> "${"%.1f".format(bytes / 1_024f)}KB"
            else                -> "${"%.1f".format(bytes / 1_048_576f)}MB"
        }
    }
}
