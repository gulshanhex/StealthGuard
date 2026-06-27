package com.system.cacheclean.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * AudioRecorder
 *
 * Thin, lifecycle-safe wrapper around Android's MediaRecorder.
 *
 * LIFECYCLE:
 *   start() → [recording] → stop() → returns saved file path
 *                         → cancel() → deletes temp file, returns nothing
 *
 * SETTINGS:
 *   Source : MIC
 *   Format : MPEG_4 (.m4a container)
 *   Codec  : AAC @ 128kbps, 44100Hz mono
 *   These settings give high-quality, small-file-size voice recordings
 *   that MediaPlayer can play back reliably on all API 24+ devices.
 *
 * THREAD SAFETY:
 *   All methods must be called from the SAME thread (main thread recommended).
 *   MediaRecorder is not thread-safe.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG             = "StealthGuard_Recorder"
        private const val SAMPLE_RATE     = 44_100
        private const val ENCODING_BITRATE = 128_000
    }

    // Current MediaRecorder instance — null when not recording
    private var recorder: MediaRecorder? = null

    // Path of the file currently being written — needed for cancel() cleanup
    private var currentOutputPath: String? = null

    /** true if a recording is actively in progress */
    val isRecording: Boolean
        get() = recorder != null

    // ─── Start Recording ──────────────────────────────────────────────────────

    /**
     * Begins recording audio to the specified output file.
     * The file does NOT need to exist — MediaRecorder creates it.
     *
     * @param outputFile  Destination file (from StorageManager.newRecordingFile)
     * @return true if recording started successfully, false on any error.
     */
    fun start(outputFile: File): Boolean {
        if (isRecording) {
            Log.w(TAG, "start() called while already recording — ignored.")
            return false
        }

        return try {
            recorder = createMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(ENCODING_BITRATE)
                setAudioChannels(1)          // Mono — sufficient for voice
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            currentOutputPath = outputFile.absolutePath
            Log.i(TAG, "Recording started → ${outputFile.name}")
            true
        } catch (e: SecurityException) {
            // A-15: happens if RECORD_AUDIO is revoked between the permission
            // check in the caller and this start() call (rare, but seen on
            // some custom ROMs that revoke mic access while an app is
            // backgrounded). Logged distinctly so it's not confused with a
            // generic MediaRecorder setup failure in crash reports.
            Log.e(TAG, "RECORD_AUDIO permission denied at MediaRecorder setup: ${e.message}")
            releaseRecorder()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
            releaseRecorder()
            false
        }
    }

    // ─── Stop Recording ───────────────────────────────────────────────────────

    /**
     * Stops the current recording and finalises the .m4a file.
     *
     * @return Absolute path of the saved file, or null if an error occurred.
     *         The file is always written before null is returned (best-effort).
     */
    fun stop(): String? {
        if (!isRecording) {
            Log.w(TAG, "stop() called while not recording.")
            return null
        }
        return try {
            recorder?.stop()
            val path = currentOutputPath
            Log.i(TAG, "Recording stopped → $path")
            releaseRecorder()
            path
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder: ${e.message}")
            releaseRecorder()
            null
        }
    }

    // ─── Cancel Recording ─────────────────────────────────────────────────────

    /**
     * Aborts the current recording and DELETES the partial file.
     * Use when the user taps "Discard" in the recording dialog.
     */
    fun cancel() {
        if (!isRecording) return
        val pathToDelete = currentOutputPath
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // MediaRecorder may throw if stopped too quickly — safe to ignore here
        }
        releaseRecorder()

        // Delete the partially written file
        pathToDelete?.let {
            val deleted = File(it).delete()
            Log.i(TAG, "Recording cancelled. File deleted ($deleted): $it")
        }
    }

    // ─── Amplitude (for WaveformView) ─────────────────────────────────────────

    /**
     * Returns the current max amplitude from the microphone (0–32767).
     * Poll this on a Handler at ~100ms intervals to animate WaveformView.
     * Returns 0 if not recording.
     */
    fun getAmplitude(): Int = try {
        recorder?.maxAmplitude ?: 0
    } catch (_: Exception) {
        0
    }

    // ─── Cleanup ──────────────────────────────────────────────────────────────

    private fun releaseRecorder() {
        try {
            recorder?.release()
        } catch (_: Exception) { /* safe to ignore */ }
        recorder = null
        currentOutputPath = null
    }

    /**
     * Call this from the hosting Fragment/Activity onDestroy to prevent leaks.
     * Cancels any in-progress recording first.
     */
    fun release() {
        if (isRecording) cancel() else releaseRecorder()
    }

    // ─── Factory ──────────────────────────────────────────────────────────────

    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
}
