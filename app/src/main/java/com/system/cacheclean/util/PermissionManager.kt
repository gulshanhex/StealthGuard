package com.system.cacheclean.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat

/**
 * PermissionStatus
 *
 * Snapshot of a single permission's state.
 *
 * [isSpecial] = true means this permission requires a Settings screen redirect
 * rather than a standard ActivityResultLauncher.requestPermissions() call.
 * (SYSTEM_ALERT_WINDOW, Accessibility, Battery Optimization are all "special".)
 */
data class PermissionStatus(
    val key: String,
    val label: String,
    val description: String,
    val isGranted: Boolean,
    val isSpecial: Boolean = false,
    val isCritical: Boolean = true    // Critical = app won't function without it
)

/**
 * PermissionManager
 *
 * Single source of truth for ALL permissions StealthGuard requires.
 * Used by SetupFragment to build the permission dashboard and by
 * SOSManager/MainActivity to guard feature access.
 *
 * PERMISSION CATEGORIES:
 *
 *   Standard (request via ActivityResultLauncher):
 *     RECORD_AUDIO, ACCESS_FINE_LOCATION, SEND_SMS
 *
 *   Special (require Settings screen redirect):
 *     SYSTEM_ALERT_WINDOW  → Settings.ACTION_MANAGE_OVERLAY_PERMISSION
 *     Accessibility        → Settings.ACTION_ACCESSIBILITY_SETTINGS
 *     Battery Optimization → Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 */
object PermissionManager {

    // ─── Keys (used as stable identifiers in the UI) ──────────────────────────
    const val KEY_MICROPHONE    = "microphone"
    const val KEY_LOCATION      = "location"
    const val KEY_SMS           = "sms"
    const val KEY_OVERLAY       = "overlay"
    const val KEY_ACCESSIBILITY = "accessibility"
    const val KEY_BATTERY       = "battery"

    // ─── Full Permission List ─────────────────────────────────────────────────

    /**
     * Returns a snapshot of all permission states.
     * Call this from the main thread — Settings.canDrawOverlays() and the
     * Accessibility check require a Context but are fast (no I/O).
     */
    fun getAllStatuses(context: Context): List<PermissionStatus> = listOf(

        PermissionStatus(
            key         = KEY_MICROPHONE,
            label       = "Microphone",
            description = "Required for Speech-to-Text keyword detection and audio recording",
            isGranted   = context.isGranted(Manifest.permission.RECORD_AUDIO),
            isCritical  = true
        ),

        PermissionStatus(
            key         = KEY_LOCATION,
            label       = "Precise Location",
            description = "Required to include your GPS coordinates in the SOS message",
            isGranted   = context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION),
            isCritical  = false   // SOS still sends without location
        ),

        PermissionStatus(
            key         = KEY_SMS,
            label       = "Send SMS",
            description = "Required to send the silent SOS message to trusted contacts",
            isGranted   = context.isGranted(Manifest.permission.SEND_SMS),
            isCritical  = true
        ),

        PermissionStatus(
            key         = KEY_OVERLAY,
            label       = "Display Over Other Apps",
            description = "Required to show the fake call screen over the lockscreen",
            isGranted   = Settings.canDrawOverlays(context),
            isSpecial   = true,
            isCritical  = false   // Not actually used by FakeCallActivity at runtime
        ),

        PermissionStatus(
            key         = KEY_ACCESSIBILITY,
            label       = "Accessibility Service",
            description = "Required for volume button trigger detection (the core trigger)",
            isGranted   = isAccessibilityEnabled(context),
            isSpecial   = true,
            isCritical  = true
        ),

        PermissionStatus(
            key         = KEY_BATTERY,
            label       = "Unrestricted Battery",
            description = "Prevents MIUI / ColorOS / OneUI from killing the background service",
            isGranted   = BatteryOptimizationHelper.isExempt(context),
            isSpecial   = true,
            isCritical  = false   // App works without it, but may be killed on aggressive ROMs
        )
    )

    // ─── Convenience Queries ──────────────────────────────────────────────────

    /** Returns true only if ALL critical permissions are granted. */
    fun areAllCriticalGranted(context: Context): Boolean =
        getAllStatuses(context)
            .filter { it.isCritical }
            .all    { it.isGranted }

    /** Count of ungranted permissions (shown as badge on Setup tab). */
    fun ungrantedCount(context: Context): Int =
        getAllStatuses(context).count { !it.isGranted }

    // ─── Permission Granting ──────────────────────────────────────────────────

    /**
     * Opens the correct Settings screen for a special permission, or
     * returns the standard Android permission string for standard ones.
     *
     * For standard permissions, the caller (SetupFragment) launches
     * ActivityResultLauncher.launch(androidPermissionString).
     *
     * For special permissions, this function directly opens the Settings screen.
     */
    fun openSettingsForPermission(context: Context, status: PermissionStatus) {
        val intent = when (status.key) {

            KEY_OVERLAY -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )

            KEY_ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

            KEY_BATTERY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"))
            }

            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"))
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback if the specific intent isn't handled on this ROM
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")))
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun Context.isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks if StealthGuard's AccessibilityService is currently enabled.
     * Searches the enabled services list for our package name.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                    as AccessibilityManager
            val enabled = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            enabled.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the Android manifest permission string for standard permissions.
     * Used by SetupFragment's ActivityResultLauncher.
     */
    fun toAndroidPermission(key: String): String? = when (key) {
        KEY_MICROPHONE -> Manifest.permission.RECORD_AUDIO
        KEY_LOCATION   -> Manifest.permission.ACCESS_FINE_LOCATION
        KEY_SMS        -> Manifest.permission.SEND_SMS
        else           -> null   // Special permissions have no Android string
    }
}
