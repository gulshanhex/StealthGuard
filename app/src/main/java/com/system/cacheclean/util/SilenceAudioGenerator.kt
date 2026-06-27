package com.system.cacheclean.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SilenceAudioGenerator
 *
 * Creates a minimal 500ms WAV silence file at runtime and stores it in
 * `filesDir/silence.wav`.
 *
 * PURPOSE:
 *   On a fresh install, the user hasn't recorded any hook/filler audio yet.
 *   Without this file, the call engine would loop:
 *     LISTEN → FILLER(null → 1.5s wait) → LISTEN → FILLER…
 *   This is functional but feels abrupt — the "caller" says nothing.
 *
 *   With the silence file, AudioResolver uses it as an ultimate fallback:
 *   the call still "connects" with a natural 500ms pause before the
 *   engine starts listening, giving the user time to put the phone to ear.
 *
 * FILE SPEC:
 *   Format : PCM WAV (uncompressed — universally supported by MediaPlayer)
 *   Rate   : 44100 Hz
 *   Channels: Mono (1)
 *   Depth  : 16-bit
 *   Duration: 500ms → 44100 × 0.5 × 2 = 44100 bytes audio + 44 header = 44144 bytes total
 *
 * This file is regenerated if missing (e.g., after app data clear).
 * Call [ensureExists] from StealthGuardApp.onCreate() alongside ensureDirectoriesExist().
 */
object SilenceAudioGenerator {

    private const val TAG             = "StealthGuard_Silence"
    private const val SAMPLE_RATE     = 44_100
    private const val CHANNELS        = 1
    private const val BITS_PER_SAMPLE = 16
    private const val DURATION_MS     = 500
    private const val FILE_NAME       = "silence.wav"

    /**
     * Returns the silence File, creating it if it doesn't exist.
     * Safe to call repeatedly — skips creation if file already exists.
     */
    fun ensureExists(context: Context): File {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists() && file.length() > 44) {
            return file   // Already exists and is not empty
        }
        return create(file)
    }

    /**
     * Returns the File object without creating anything.
     * Use in AudioResolver — null-check before using.
     */
    fun getFile(context: Context): File =
        File(context.filesDir, FILE_NAME)

    // ─── WAV File Creation ────────────────────────────────────────────────────

    private fun create(output: File): File {
        try {
            val numSamples = SAMPLE_RATE * DURATION_MS / 1000        // 22050 samples
            val bytesPerSample = BITS_PER_SAMPLE / 8                  // 2 bytes
            val dataSize = numSamples * CHANNELS * bytesPerSample      // 44100 bytes
            val byteRate = SAMPLE_RATE * CHANNELS * bytesPerSample     // 88200

            FileOutputStream(output).use { fos ->
                // ── WAV Header (44 bytes, little-endian) ──────────────────────
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

                // RIFF chunk descriptor
                header.put("RIFF".toByteArray(Charsets.US_ASCII))
                header.putInt(36 + dataSize)           // ChunkSize = 36 + DataSize
                header.put("WAVE".toByteArray(Charsets.US_ASCII))

                // fmt sub-chunk
                header.put("fmt ".toByteArray(Charsets.US_ASCII))
                header.putInt(16)                      // Subchunk1Size (PCM = 16)
                header.putShort(1)                     // AudioFormat (PCM = 1)
                header.putShort(CHANNELS.toShort())    // NumChannels
                header.putInt(SAMPLE_RATE)             // SampleRate
                header.putInt(byteRate)                // ByteRate
                header.putShort((CHANNELS * bytesPerSample).toShort())  // BlockAlign
                header.putShort(BITS_PER_SAMPLE.toShort())              // BitsPerSample

                // data sub-chunk
                header.put("data".toByteArray(Charsets.US_ASCII))
                header.putInt(dataSize)                // Subchunk2Size

                fos.write(header.array())

                // ── Audio Data (all zeros = digital silence) ──────────────────
                // Write in 8KB chunks to avoid large heap allocation
                val chunk = ByteArray(8_192)
                var remaining = dataSize
                while (remaining > 0) {
                    val toWrite = minOf(chunk.size, remaining)
                    fos.write(chunk, 0, toWrite)
                    remaining -= toWrite
                }
            }

            Log.i(TAG, "Silence WAV created: ${output.absolutePath} " +
                       "(${output.length()} bytes, ${DURATION_MS}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create silence WAV: ${e.message}")
        }

        return output
    }
}
