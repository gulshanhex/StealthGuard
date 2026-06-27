package com.system.cacheclean.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

// ═════════════════════════════════════════════════════════════════════════════
// BootReceiver
// ═════════════════════════════════════════════════════════════════════════════

/**
 * BootReceiver
 *
 * Listens for device boot/restart events and immediately re-launches the
 * GuardForegroundService so protection resumes without user interaction.
 *
 * Handles two boot actions:
 *   - ACTION_BOOT_COMPLETED         : Standard Android boot.
 *   - QUICKBOOT_POWERON             : Huawei/HTC fast-boot (MIUI devices).
 *
 * Manifest requirement (already in AndroidManifest.xml):
 *   <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "StealthGuard_Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // N-04: extend the 10s broadcast timeout to ~30s. On budget devices
        // under heavy boot I/O, startForegroundService() may not return
        // before the default timeout, causing the system to kill the
        // broadcast (and skip the service start) before it completes.
        val pendingResult = goAsync()
        val action = intent.action

        val isBootEvent = action == Intent.ACTION_BOOT_COMPLETED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON"

        if (!isBootEvent) {
            pendingResult.finish()
            return
        }

        Log.i(TAG, "Boot detected ($action) — starting GuardForegroundService.")

        val serviceIntent = Intent(context, GuardForegroundService::class.java)

        // startForegroundService() is required on API 26+ when targeting
        // foreground services. The service MUST call startForeground()
        // within 5 seconds or the OS will ANR-crash it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        pendingResult.finish()
    }
}


// ═════════════════════════════════════════════════════════════════════════════
// ServiceRestartWorker
// ═════════════════════════════════════════════════════════════════════════════

/**
 * ServiceRestartWorker
 *
 * WorkManager periodic worker that acts as Layer 3 of our survival strategy.
 * Runs every 15 minutes (WorkManager's minimum interval).
 *
 * WHY WORKMANAGER:
 *   Unlike AlarmManager, WorkManager is guaranteed to run even when:
 *     - The device is in Doze Mode.
 *     - MIUI's "MIUI Optimization" kills background processes.
 *     - ColorOS's battery saver terminates background services.
 *   WorkManager negotiates with the OS JobScheduler and runs when resources
 *   are available, making it the most resilient scheduling mechanism available
 *   without root access.
 *
 * BEHAVIOR:
 *   Simply sends a startForegroundService() intent. If the service is already
 *   running, onStartCommand() fires again harmlessly (START_STICKY handles it).
 *   If the service was killed, this brings it back.
 */
class ServiceRestartWorker(
    private val context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "StealthGuard_Worker"
    }

    override fun doWork(): Result {
        Log.d(TAG, "WorkManager heartbeat fired — ensuring service is alive.")

        return try {
            val serviceIntent = Intent(context, GuardForegroundService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.d(TAG, "Service restart signal sent successfully.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service: ${e.message}")
            // A-19: IllegalStateException/SecurityException are permanent
            // (bad component, missing permission) — retrying every 15min
            // forever just wastes battery. Only retry transient failures.
            if (e is IllegalStateException || e is SecurityException) {
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }
}
