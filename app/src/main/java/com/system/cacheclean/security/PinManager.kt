package com.system.cacheclean.security

import android.content.Context
import android.os.SystemClock
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * PinManager
 *
 * Handles all PIN-related operations using Jetpack Security's
 * EncryptedSharedPreferences (AES-256-GCM encrypted file on disk).
 *
 * SECURITY MODEL:
 *   - PIN is never stored in plaintext. Only its SHA-256 hash is persisted.
 *   - The SharedPreferences file itself is AES-256-GCM encrypted.
 *   - Wrong-attempt counter is stored in the same encrypted file.
 *   - After 3 wrong attempts: 60-second lockout. No error message shown to
 *     the attacker — the UI simply shows nothing unusual.
 *
 * USAGE:
 *   val pm = PinManager(context)
 *   if (!pm.isPinSet()) pm.setPin("4321")          // First launch
 *   if (pm.isLockedOut()) { /* show nothing */ }
 *   if (pm.verifyPin(input)) { /* open admin */ }
 */
class PinManager(context: Context) {

    companion object {
        private const val PREFS_FILE_NAME   = "sc_secure_store"
        private const val KEY_PIN_HASH      = "p"          // Short key = less metadata
        private const val KEY_FAIL_COUNT    = "f"
        private const val KEY_LOCKOUT_END   = "l"
        private const val KEY_PIN_SET       = "s"

        private const val MAX_ATTEMPTS      = 3
        private const val LOCKOUT_DURATION  = 60_000L      // 60 seconds in ms
    }

    // ─── Encrypted SharedPreferences ─────────────────────────────────────────

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ─── PIN State ────────────────────────────────────────────────────────────

    /** Returns true if a PIN has been set (not first launch). */
    fun isPinSet(): Boolean = prefs.getBoolean(KEY_PIN_SET, false)

    /**
     * Stores a new PIN. Hashes it first — the raw PIN never touches disk.
     * Call this only on first-launch setup or PIN change flow.
     */
    fun setPin(pin: String) {
        prefs.edit()
            .putString(KEY_PIN_HASH, hashPin(pin))
            .putBoolean(KEY_PIN_SET, true)
            .putInt(KEY_FAIL_COUNT, 0)
            .putLong(KEY_LOCKOUT_END, 0L)
            .apply()
    }

    /**
     * Verifies the entered PIN against the stored hash.
     *
     * @return PinResult — success, failure (with remaining attempts), or
     *         lockout state.
     *
     * NOTE: This does NOT record failures internally. Call [recordFailedAttempt]
     *       separately only when this returns false, so the caller controls flow.
     */
    fun verifyPin(pin: String): Boolean {
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val enteredHash = hashPin(pin)
        // N-06: String == short-circuits on first mismatched char, which is a
        // (mostly theoretical, given the 3-attempt lockout) timing side
        // channel. Constant-time compare closes it for free.
        return MessageDigest.isEqual(
            enteredHash.toByteArray(Charsets.UTF_8),
            storedHash.toByteArray(Charsets.UTF_8)
        )
    }

    // ─── Lockout Logic ────────────────────────────────────────────────────────

    /**
     * Call this after a failed PIN attempt.
     * @return remaining attempts before lockout (0 means lockout just triggered)
     */
    fun recordFailedAttempt(): Int {
        val current = prefs.getInt(KEY_FAIL_COUNT, 0) + 1
        if (current >= MAX_ATTEMPTS) {
            // Trigger lockout — reset counter so next cycle starts fresh.
            // A-01: elapsedRealtime() is monotonic and unaffected by the user
            // changing Settings → Date & Time, unlike currentTimeMillis().
            prefs.edit()
                .putLong(KEY_LOCKOUT_END, SystemClock.elapsedRealtime() + LOCKOUT_DURATION)
                .putInt(KEY_FAIL_COUNT, 0)
                .apply()
            return 0
        } else {
            prefs.edit().putInt(KEY_FAIL_COUNT, current).apply()
            return MAX_ATTEMPTS - current
        }
    }

    /**
     * Returns true if the user is currently in the lockout period.
     * Check this BEFORE showing the PIN dialog.
     */
    fun isLockedOut(): Boolean {
        return SystemClock.elapsedRealtime() < prefs.getLong(KEY_LOCKOUT_END, 0L)
    }

    /**
     * Remaining lockout time in whole seconds. Returns 0 if not locked out.
     */
    fun getLockoutRemainingSeconds(): Long {
        val end = prefs.getLong(KEY_LOCKOUT_END, 0L)
        return maxOf(0L, (end - SystemClock.elapsedRealtime()) / 1000L)
    }

    // ─── Hashing ─────────────────────────────────────────────────────────────

    /**
     * SHA-256 hash of the PIN string.
     * A 4-digit PIN has only 10,000 possibilities — this is not strong
     * cryptography, but it's appropriate for this threat model (offline,
     * physical-access attacker who doesn't know what app this is).
     */
    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes  = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
