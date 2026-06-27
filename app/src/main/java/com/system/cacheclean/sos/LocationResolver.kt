package com.system.cacheclean.sos

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * LocationResolver
 *
 * Attempts to obtain the device's last known location using a 3-level
 * fallback chain. Each level is tried in order; the first non-null
 * result is returned immediately.
 *
 * FALLBACK CHAIN:
 *   Level 1 — FusedLocationProviderClient.lastLocation
 *             Most accurate. Combines GPS + WiFi + cell triangulation.
 *             5-second timeout (lastLocation is a cached value, not a
 *             live request, so 5s is more than enough).
 *
 *   Level 2 — LocationManager (GPS_PROVIDER)
 *             Raw GPS last known fix. Works offline. May be null if the
 *             device has never acquired a GPS fix in this session.
 *
 *   Level 3 — LocationManager (NETWORK_PROVIDER)
 *             Cell tower / WiFi triangulation. Lower accuracy but works
 *             indoors and without GPS lock.
 *
 *   Level 4 — null
 *             All providers failed or returned null. SOSManager sends
 *             the message without a location string.
 *
 * ALL EXCEPTIONS are caught internally. A SecurityException (missing
 * permission) results in null — the SOS still sends without location.
 * This guarantees the SOS delivery is NEVER blocked by a location failure.
 *
 * PERMISSION: ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION must be
 * granted at runtime before this is called. SOSManager checks this.
 */
class LocationResolver(private val context: Context) {

    companion object {
        private const val TAG             = "StealthGuard_Location"
        private const val FUSED_TIMEOUT   = 5_000L   // ms
    }

    /**
     * Main entry point. Always returns quickly — either with a Location
     * object or null. Must be called from a coroutine (suspend).
     */
    @SuppressLint("MissingPermission")   // Permission checked by caller (SOSManager)
    suspend fun resolve(): Location? {
        Log.d(TAG, "Starting location resolution…")

        tryFused()?.let {
            Log.i(TAG, "Level 1 (Fused) → lat=${it.latitude} lng=${it.longitude} acc=${it.accuracy}m")
            return it
        }

        tryLocationManager(LocationManager.GPS_PROVIDER)?.let {
            Log.i(TAG, "Level 2 (GPS) → lat=${it.latitude} lng=${it.longitude}")
            return it
        }

        tryLocationManager(LocationManager.NETWORK_PROVIDER)?.let {
            Log.i(TAG, "Level 3 (Network) → lat=${it.latitude} lng=${it.longitude}")
            return it
        }

        Log.w(TAG, "Level 4 — all location providers returned null. Sending SOS without location.")
        return null
    }

    // ─── Level 1: FusedLocationProviderClient ────────────────────────────────

    @SuppressLint("MissingPermission")
    private suspend fun tryFused(): Location? = try {
        val client = LocationServices.getFusedLocationProviderClient(context)
        // lastLocation is a cached fix — it's fast. The timeout is just a safety net.
        withTimeoutOrNull(FUSED_TIMEOUT) {
            client.lastLocation.await()
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "Fused: permission denied — ${e.message}")
        null
    } catch (e: Exception) {
        Log.w(TAG, "Fused: failed — ${e.message}")
        null
    }

    // ─── Level 2 & 3: LocationManager ────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun tryLocationManager(provider: String): Location? = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(provider)) {
            Log.d(TAG, "Provider disabled: $provider")
            null
        } else {
            lm.getLastKnownLocation(provider)
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "$provider: permission denied — ${e.message}")
        null
    } catch (e: Exception) {
        Log.w(TAG, "$provider: failed — ${e.message}")
        null
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    /**
     * Formats a Location into a Google Maps URL.
     * Uses 6 decimal places (~0.1m precision — sufficient for emergency use).
     */
    fun toMapsUrl(location: Location): String =
        "https://maps.google.com/?q=%.6f,%.6f".format(location.latitude, location.longitude)
}
