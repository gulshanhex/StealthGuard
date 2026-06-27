package com.system.cacheclean.sos

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.system.cacheclean.R
import com.system.cacheclean.db.StealthGuardDatabase
import com.system.cacheclean.db.entity.SosContactEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SOSManager — Full Phase 5 Implementation
 *
 * A short-lived ForegroundService (location type) that:
 *   1. Resolves the device's last known GPS location (triple fallback)
 *   2. Formats a Google Maps URL with the coordinates
 *   3. Sends a silent SMS to every enabled trusted contact in Room DB
 *   4. Gives a silent vibration confirmation (3 short pulses)
 *   5. Stops itself immediately after — no lingering process
 *
 * STEALTH GUARANTEES:
 *   • No UI is shown at any point
 *   • No sound, ringtone, or visible notification
 *   • SMS is sent via SmsManager without opening the Messages app
 *   • The foreground notification is VISIBILITY_SECRET (hidden on lockscreen)
 *     and IMPORTANCE_MIN (no heads-up banner, no sound)
 *   • Service self-terminates in stopSelf() after sending
 *
 * RESILIENCE:
 *   • Every external call (location, SMS, DB) is wrapped in try-catch
 *   • A failure in one SMS send does NOT block subsequent contacts
 *   • If location is unavailable, SOS still sends without coordinates
 *   • If DB has no contacts, logs warning and exits cleanly
 *
 * TRIGGERED BY:
 *   StealthAccessibilityService (3× Volume UP + hold within 1.5s)
 *   TripleTapDetector (corner triple-tap + 2s hold)
 *
 * MANIFEST REQUIREMENT:
 *   android:foregroundServiceType="location" (already declared)
 */
class SOSManager : Service() {

    companion object {
        const val ACTION_SEND_SOS    = "com.system.cacheclean.ACTION_SEND_SOS"
        private const val TAG        = "StealthGuard_SOS"
        private const val NOTIF_ID   = 9002
        private const val CHANNEL_ID = "sos_exec_ch"

        /** Delay between SMS sends — reduces carrier throttle risk (A-13: 500ms
         *  was too aggressive for some Indian carriers like Jio/BSNL). */
        private const val SMS_STAGGER_MS = 1_000L

        /** SOS message template. {LOCATION} is replaced with Maps URL or fallback text. */
        private const val MSG_TEMPLATE =
            "EMERGENCY! I need help. Please call me immediately.\n" +
            "My last known location:\n{LOCATION}\n[Sent automatically]"

        private const val NO_LOCATION_TEXT =
            "Location unavailable. Find me urgently."
    }

    // SupervisorJob: one coroutine failing doesn't cancel siblings
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Guards against duplicate SOS sends if onStartCommand fires twice (M-05). */
    private val isExecuting = AtomicBoolean(false)

    // ─── Service Lifecycle ────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_SEND_SOS) {
            Log.w(TAG, "Unknown action — stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(TAG, "SOS triggered.")

        // startForeground() MUST be called within 5s of onStartCommand() on API 26+
        startForeground(NOTIF_ID, buildSilentNotification())

        // M-05: prevent duplicate SOS execution if onStartCommand is triggered
        // again (e.g. double key-press) while a send is already in flight.
        if (!isExecuting.compareAndSet(false, true)) {
            Log.w(TAG, "SOS already executing — ignoring duplicate trigger.")
            return START_NOT_STICKY
        }

        serviceScope.launch {
            executeSOS()
            withContext(Dispatchers.Main) { stopSelf() }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "SOSManager destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Core SOS Execution ───────────────────────────────────────────────────

    /**
     * Main SOS coroutine. Runs entirely on Dispatchers.IO.
     * Order: resolve location → load contacts → send SMS → vibrate.
     */
    private suspend fun executeSOS() {
        // Step 1: Resolve location
        val locationMessage = if (hasLocationPermission()) {
            val resolver  = LocationResolver(this)
            val location  = resolver.resolve()
            if (location != null) resolver.toMapsUrl(location) else NO_LOCATION_TEXT
        } else {
            Log.w(TAG, "Location permission missing — sending without coordinates.")
            NO_LOCATION_TEXT
        }

        val smsBody = MSG_TEMPLATE.replace("{LOCATION}", locationMessage)
        Log.i(TAG, "SOS message prepared (${smsBody.length} chars).")

        // A-03: SEND_SMS is a runtime permission on API 33+. Without this
        // check, sendSilentSms() fails silently and the user still gets the
        // "success" vibration even though no message was ever sent.
        if (!hasSmsPermission()) {
            Log.e(TAG, "SEND_SMS permission not granted — SOS cannot send messages. Aborting.")
            isExecuting.set(false)
            return
        }

        // Step 2: Load enabled contacts from Room DB (synchronous on IO)
        val contacts = loadEnabledContacts()
        if (contacts.isEmpty()) {
            Log.w(TAG, "No enabled SOS contacts configured — aborting.")
            isExecuting.set(false)
            return
        }

        // Step 3: Send SMS to each contact with stagger delay
        var sentCount = 0
        contacts.forEach { contact ->
            val sent = sendSilentSms(contact.phoneNumber, smsBody)
            if (sent) {
                sentCount++
                Log.i(TAG, "SMS sent to ${contact.nickname} (${contact.phoneNumber})")
            } else {
                Log.e(TAG, "SMS FAILED for ${contact.nickname} (${contact.phoneNumber})")
            }
            // Stagger to avoid carrier throttle (skip delay after last contact)
            if (contact != contacts.last()) delay(SMS_STAGGER_MS)
        }

        Log.i(TAG, "SOS complete. Sent $sentCount/${contacts.size} messages.")

        // Step 4: Silent vibration confirmation
        if (sentCount > 0) vibrateConfirmation()

        // Reset guard so a future SOS trigger (new onStartCommand) can run.
        isExecuting.set(false)
    }

    // ─── Location Permission Check ────────────────────────────────────────────

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ─── Database ─────────────────────────────────────────────────────────────

    /**
     * Synchronous Room query — safe on IO dispatcher.
     * Uses the sync DAO method that returns List directly (no suspend/LiveData).
     */
    private fun loadEnabledContacts(): List<SosContactEntity> {
        return try {
            StealthGuardDatabase.getInstance(this)
                .sosContactDao()
                .getEnabledContactsSync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load contacts from DB: ${e.message}")
            emptyList()
        }
    }

    // ─── SMS Sending ──────────────────────────────────────────────────────────

    /**
     * Sends a silent SMS without opening the Messages app.
     * Uses SmsManager.divideMessage() for long messages that exceed
     * the 160-character SMS limit (our template can be ~160-175 chars).
     *
     * sentIntent = null → no callback on send (stealth)
     * deliveryIntent = null → no callback on delivery (stealth)
     *
     * @return true if the send API call succeeded (not delivery confirmation).
     */
    @Suppress("DEPRECATION")   // SmsManager.getDefault() deprecated in API 31
    private fun sendSilentSms(phoneNumber: String, message: String): Boolean {
        return try {
            val smsManager = getSmsManager()
                ?: return false.also { Log.e(TAG, "SmsManager unavailable") }

            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                // Message fits in a single SMS (≤160 chars)
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            } else {
                // Message requires multipart SMS (>160 chars)
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SMS permission denied: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed for $phoneNumber: ${e.message}")
            false
        }
    }

    /**
     * Returns SmsManager using the modern API on API 31+, falling back
     * to the deprecated getDefault() on older APIs.
     */
    private fun getSmsManager(): SmsManager? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    // ─── Vibration Confirmation ───────────────────────────────────────────────

    /**
     * 3 short silent pulses to confirm SOS was sent.
     * Pattern: [wait 0ms, vibrate 100ms, pause 100ms] × 3
     *
     * Uses VibratorManager on API 31+, falls back to Vibrator for older APIs.
     * If the device has no vibrator, this call is a no-op (no crash).
     */
    private fun vibrateConfirmation() {
        try {
            val pattern = longArrayOf(0, 100, 100, 100, 100, 100)   // 3 pulses

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(pattern, -1)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
            Log.d(TAG, "Vibration confirmation sent.")
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed (non-critical): ${e.message}")
        }
    }

    // ─── Stealth Notification ─────────────────────────────────────────────────

    /**
     * Required foreground notification (ForegroundService mandate).
     * Made as invisible as possible:
     *   IMPORTANCE_MIN   → no sound, no heads-up, collapsed in shade
     *   VISIBILITY_SECRET → hidden on lockscreen
     *   setShowBadge(false) → no red dot on icon
     *   setOngoing(true)  → can't be swiped away (auto-dismissed when service stops)
     *
     * The service stops itself in ~5-10 seconds so this notification
     * disappears quickly with no user interaction.
     */
    private fun buildSilentNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Services",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                enableVibration(false)
                enableLights(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            // Intentionally generic: channel name "System Services" + "Syncing…"
            // text makes this notification look like a routine system process
            // to a casual observer checking notification settings (A-22).
            .setContentTitle("Syncing…")
            .setContentText("Background sync in progress")
            .setSmallIcon(R.drawable.ic_battery_stat)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .build()
    }
}
