package com.system.cacheclean.ui.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.system.cacheclean.audio.AudioRecorder
import com.system.cacheclean.db.StealthGuardDatabase
import com.system.cacheclean.db.entity.KeywordAudioEntity
import com.system.cacheclean.model.AudioType
import com.system.cacheclean.model.Gender
import com.system.cacheclean.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AudioStudioViewModel
 *
 * Shared ViewModel for both GENTS and LADY AudioStudioFragment instances.
 * Each fragment instance passes its gender at construction.
 *
 * Responsibilities:
 *   - File system reads (listing hook/response/filler files)
 *   - Recording lifecycle delegation to AudioRecorder
 *   - Active hook management via StorageManager
 *   - Keyword-audio DB link creation via KeywordAudioDao
 *   - File deletion (filesystem + DB cleanup)
 */
class AudioStudioViewModel(app: Application) : AndroidViewModel(app) {

    private val db             = StealthGuardDatabase.getInstance(app)
    private val storageManager = StorageManager(app)
    private val recorder       = AudioRecorder(app)
    private val keywordDao     = db.keywordAudioDao()

    // ─── Observed File Lists ──────────────────────────────────────────────────

    private val _hookFiles     = MutableLiveData<List<File>>()
    private val _responseFiles = MutableLiveData<List<File>>()
    private val _fillerFiles   = MutableLiveData<List<File>>()

    val hookFiles:     LiveData<List<File>> = _hookFiles
    val responseFiles: LiveData<List<File>> = _responseFiles
    val fillerFiles:   LiveData<List<File>> = _fillerFiles

    // ─── Recording State ──────────────────────────────────────────────────────

    private val _isRecording = MutableLiveData(false)
    val isRecording: LiveData<Boolean> = _isRecording

    val isRecorderActive: Boolean get() = recorder.isRecording
    fun getAmplitude(): Int = recorder.getAmplitude()

    // ─── File Loading ─────────────────────────────────────────────────────────

    /** Refresh all three file lists from disk for the given gender. */
    fun loadFiles(gender: Gender) {
        _hookFiles.value     = storageManager.listFiles(gender, AudioType.HOOK)
        _responseFiles.value = storageManager.listFiles(gender, AudioType.RESPONSE)
        _fillerFiles.value   = storageManager.listFiles(gender, AudioType.FILLER)
    }

    fun getActiveHook(gender: Gender): File? = storageManager.getActiveHook(gender)

    fun setActiveHook(gender: Gender, file: File) {
        storageManager.setActiveHook(gender, file)
        // Re-emit hook list so adapter can redraw the "active" badge
        _hookFiles.value = storageManager.listFiles(gender, AudioType.HOOK)
    }

    // ─── Recording Control ────────────────────────────────────────────────────

    /**
     * Starts recording to a new file in the correct directory.
     * @return true if recording started. false means RECORD_AUDIO permission
     *         is missing or the recorder is already active.
     */
    fun startRecording(gender: Gender, type: AudioType): Boolean {
        val outputFile = storageManager.newRecordingFile(gender, type)
        val started = recorder.start(outputFile)
        if (started) _isRecording.value = true
        return started
    }

    /**
     * Stops recording and saves the file.
     * @return The saved File, or null on error.
     */
    fun stopRecording(gender: Gender, type: AudioType): File? {
        val path = recorder.stop()
        _isRecording.value = false
        if (path == null) return null
        loadFiles(gender)   // Refresh list to show new file
        return File(path)
    }

    /** Discards the in-progress recording without saving. */
    fun cancelRecording(gender: Gender) {
        recorder.cancel()
        _isRecording.value = false
        loadFiles(gender)
    }

    // ─── Keyword Linking ──────────────────────────────────────────────────────

    /**
     * Links a saved response audio file to a keyword in Room DB.
     * Creates the keyword row if it doesn't exist; updates if it does.
     *
     * @param gender  Determines which path column (gentsAudioPath/ladyAudioPath) to set.
     * @param keyword The spoken trigger word.
     * @param file    The response audio file.
     */
    fun linkResponseToKeyword(gender: Gender, keyword: String, file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = keywordDao.findByKeyword(keyword.trim().lowercase())
            if (existing == null) {
                keywordDao.upsert(
                    KeywordAudioEntity(
                        keyword       = keyword.trim().lowercase(),
                        gentsAudioPath = if (gender == Gender.GENTS) file.absolutePath else null,
                        ladyAudioPath  = if (gender == Gender.LADY)  file.absolutePath else null
                    )
                )
            } else {
                keywordDao.upsert(
                    existing.copy(
                        gentsAudioPath = if (gender == Gender.GENTS) file.absolutePath else existing.gentsAudioPath,
                        ladyAudioPath  = if (gender == Gender.LADY)  file.absolutePath else existing.ladyAudioPath
                    )
                )
            }
        }
    }

    // ─── File Deletion ────────────────────────────────────────────────────────

    fun deleteFile(gender: Gender, type: AudioType, file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            // If this was the active hook, clear the preference
            if (type == AudioType.HOOK &&
                storageManager.getActiveHook(gender)?.name == file.name) {
                storageManager.clearActiveHook(gender)
            }
            // If this was a response file, null out the stale path in Room DB
            if (type == AudioType.RESPONSE) {
                clearPathFromDb(gender, file.absolutePath)
            }
            storageManager.deleteFile(file)
            // Switch to Main to call loadFiles (which uses .value not .postValue).
            // withContext (not a nested launch) ties this back to the parent
            // coroutine so it's cancelled cleanly if the ViewModel is cleared.
            withContext(Dispatchers.Main) { loadFiles(gender) }
        }
    }

    /**
     * BUG-10 FIX: Properly nulls out the audio path in Room DB when a
     * response file is deleted from disk. Previously this was a no-op,
     * causing AudioResolver to attempt playback of non-existent files.
     *
     * Uses gender-specific UPDATE queries added to KeywordAudioDao:
     *   clearGentsPath(path) / clearLadyPath(path)
     */
    private suspend fun clearPathFromDb(gender: Gender, path: String) {
        when (gender) {
            Gender.GENTS -> keywordDao.clearGentsPath(path)
            Gender.LADY  -> keywordDao.clearLadyPath(path)
        }
    }

    fun fileSizeLabel(file: File): String = storageManager.fileSizeLabel(file)

    override fun onCleared() {
        super.onCleared()
        recorder.release()
    }
}
