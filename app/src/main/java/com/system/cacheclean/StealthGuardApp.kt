package com.system.cacheclean

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import com.system.cacheclean.service.GuardForegroundService
import com.system.cacheclean.storage.StorageManager
import com.system.cacheclean.util.SilenceAudioGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * StealthGuardApp — Application Class
 *
 * Runs before any Activity. Initialises all on-disk resources and starts
 * the background guard service.
 *
 * STARTUP ORDER (matters):
 *   1. Create audio directory tree (StorageManager)
 *   2. Generate silence.wav fallback (SilenceAudioGenerator) — Phase 6 addition
 *   3. Start GuardForegroundService (trigger listener)
 *
 * Both disk operations are synchronous and fast (<5ms on first run,
 * <1ms on subsequent runs since they check existence before creating).
 */
class StealthGuardApp : Application() {

    companion object {
        private const val TAG = "StealthGuard_App"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application process started.")

        // Step 1: Create audio directory tree
        // Without this, MediaRecorder.setOutputFile() throws FileNotFoundException
        // on fresh install because the target directories don't exist yet.
        StorageManager(this).ensureDirectoriesExist()

        // Step 2 (Phase 6): Generate silence.wav fallback
        // Ensures AudioResolver always has something to play even if the user
        // hasn't recorded any audio yet. Moved off the main thread (M-04):
        // file existence + WAV synthesis is disk I/O and can ANR on a fresh
        // install where the file doesn't exist yet.
        CoroutineScope(Dispatchers.IO).launch {
            SilenceAudioGenerator.ensureExists(this@StealthGuardApp)
        }

        // Step 3: Start the trigger listener service
        startGuardService()
    }

    private fun startGuardService() {
        val intent = Intent(this, GuardForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "GuardForegroundService start signal sent.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GuardForegroundService: ${e.message}")
        }
    }
}
