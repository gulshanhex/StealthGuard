package com.system.cacheclean.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * BatteryOptimizationHelper
 *
 * Manages Android's battery optimization exemption for StealthGuard.
 *
 * WHY THIS MATTERS:
 *   Android's default Doze mode and aggressive OEM battery savers
 *   (MIUI, ColorOS, OneUI, EMUI) routinely kill background services even
 *   when they are declared as ForegroundService with START_STICKY.
 *
 *   Being on the battery optimization allowlist tells the OS to treat
 *   our process as "unrestricted" — it can run in background, wake from
 *   Doze, and receive broadcasts without restrictions.
 *
 *   WorkManager and AlarmManager are our Layers 2 & 3 of survival,
 *   but Layer 0 is getting off the optimization list entirely.
 *
 * ROM-SPECIFIC NOTES:
 *   Standard Android → ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (works)
 *   MIUI             → Also needs: Settings → Apps → Manage Apps → StealthGuard
 *                       → Battery Saver → No restrictions + Autostart ON
 *   ColorOS          → Settings → Battery → App energy management → add app
 *   OneUI            → Settings → Apps → StealthGuard → Battery → Unrestricted
 *   These extra steps are shown as instructions in SetupFragment.
 */
object BatteryOptimizationHelper {

    private const val TAG = "StealthGuard_Battery"

    // ─── Status Check ─────────────────────────────────────────────────────────

    /**
     * Returns true if StealthGuard is on the battery optimization whitelist.
     * Only meaningful on API 23+. Returns true on older APIs (no Doze mode).
     */
    fun isExempt(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true   // Pre-Marshmallow: no Doze mode, always "exempt"
        }
    }

    // ─── Open Settings Fallback ───────────────────────────────────────────────

    /**
     * Opens the full "Battery Optimization" app list where the user can
     * find StealthGuard and set it to "Don't optimize".
     * Used as a fallback on ROMs where the direct dialog isn't supported.
     */
    fun openAllAppsOptimizationList(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "Could not open battery settings: ${e.message}")
        }
    }

    // ─── ROM Detection ────────────────────────────────────────────────────────

    /**
     * Detected ROM manufacturer for showing ROM-specific instructions in UI.
     */
    fun detectRom(): RomType {
        val brand = android.os.Build.BRAND.lowercase()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") ||
            manufacturer.contains("xiaomi")                      -> RomType.MIUI
            brand.contains("oppo") || brand.contains("realme")  -> RomType.COLOROS
            brand.contains("samsung")                            -> RomType.ONEUI
            brand.contains("huawei") || brand.contains("honor") -> RomType.EMUI
            brand.contains("vivo")                               -> RomType.FUNTOUCH
            else                                                 -> RomType.STOCK
        }
    }

    enum class RomType { STOCK, MIUI, COLOROS, ONEUI, EMUI, FUNTOUCH }

    /**
     * Returns ROM-specific extra steps beyond the standard battery exemption.
     * Shown as a numbered list in SetupFragment.
     */
    fun getRomSpecificInstructions(romType: RomType): String = when (romType) {
        RomType.MIUI -> """
            Extra steps for MIUI:
            1. Settings → Apps → Manage Apps → System Cache Manager
            2. Battery Saver → select "No Restrictions"
            3. Go back → Autostart → Enable
        """.trimIndent()

        RomType.COLOROS -> """
            Extra steps for ColorOS:
            1. Settings → Battery → App Energy Management
            2. Find System Cache Manager → Customize
            3. Allow background activity
        """.trimIndent()

        RomType.ONEUI -> """
            Extra steps for Samsung OneUI:
            1. Settings → Apps → System Cache Manager → Battery
            2. Select "Unrestricted"
        """.trimIndent()

        RomType.EMUI -> """
            Extra steps for EMUI:
            1. Settings → Battery → App Launch
            2. Find System Cache Manager → turn off Auto-manage
            3. Enable Auto-launch, Secondary launch, Run in background
        """.trimIndent()

        RomType.FUNTOUCH -> """
            Extra steps for Vivo FuntouchOS:
            1. Settings → Battery → High background power consumption
            2. Find System Cache Manager → Allow
        """.trimIndent()

        RomType.STOCK -> "No extra steps needed for stock Android."
    }
}
