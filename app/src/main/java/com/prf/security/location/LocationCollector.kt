package com.prf.security.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.openlocationcode.OpenLocationCode
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Collects one location fix per check-in and renders it in the three transport formats
 * the relay writes to the database. v1.2.0.
 *
 * Only the last known fix is read. The check-in path is short-lived and must not block on
 * a GPS lock, so this never waits for a fresh callback - if the device has any recent fix
 * from any provider, that is what gets recorded; if it has none, the check-in still
 * uploads without a location block rather than hanging in the queue forever.
 *
 * The three formats exist so a location is recoverable with nothing but the text file:
 * the maps URL opens in any browser, the geo: URI resolves in any mapping app, and the
 * Plus Code is a short code that names the same cell even where neither of those works.
 */
class LocationCollector(private val context: Context) {

    /**
     * The most recent fix available from any provider, or null if the device has none.
     */
    @SuppressLint("MissingPermission")
    fun lastKnownFix(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val candidates = mutableListOf<Location>()
        for (provider in PROVIDER_ORDER) {
            try {
                val providerEnabled = lm.isProviderEnabled(provider)
                if (!providerEnabled) continue
                @Suppress("DEPRECATION")
                val fix = lm.getLastKnownLocation(provider) ?: continue
                candidates.add(fix)
            } catch (t: Throwable) {
                // A provider present in the manifest but unusable on this device is fine.
                Log.w(TAG, "provider " + provider + " unavailable: " + t.message)
            }
        }
        if (candidates.isEmpty()) return null
        // Newest fix wins, so a stale passive reading never overrides a fresh GPS one.
        return candidates.maxBy { it.time }
    }

    /**
     * A location for a capture that is happening right now.
     *
     * The last known fix is tried first and returns instantly — it is usually
     * good enough, and the caller should never pay for a GPS lock. Only when
     * there is no fix at all does this ask for a fresh one, and that ask is
     * bounded, because a fresh fix is worth a few seconds of an on-screen
     * capture and an indoor phone waiting forever is not.
     *
     * Returns null rather than throwing: a record with no location is still a
     * valid record, and the caller says so on the step list.
     *
     * Before API 30 there is no single-shot call to make, and the alternative is
     * a standing listener plus a blocking wait on the main thread. That is a
     * worse trade than a missing fix, so on those versions this returns whatever
     * the instant read found and nothing more.
     */
    @SuppressLint("MissingPermission")
    suspend fun awaitFix(waitMs: Long): Location? {
        lastKnownFix()?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val provider = PROVIDER_ORDER.firstOrNull {
            runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
        } ?: return null
        val signal = CancellationSignal()
        return try {
            withTimeoutOrNull(waitMs) {
                lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { it }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "single-shot fix failed: " + t.message)
            null
        } finally {
            signal.cancel()
        }
    }

    /**
     * Renders [fix] into the relay payload map. Keys are the contract the server reads.
     */
    fun toPayload(fix: Location): Map<String, String> {
        val lat = fix.latitude
        val lon = fix.longitude
        val plus = plusCode(lat, lon)
        return mapOf(
            KEY_MAPS to mapsUrl(lat, lon),
            KEY_GEO to geoUri(lat, lon, fix.altitude, fix.accuracy),
            KEY_PLUS to plus,
            KEY_RAW to rawLine(fix),
            KEY_STAMP to stampName(fix.time),
        )
    }

    private fun mapsUrl(lat: Double, lon: Double): String =
        "https://www.google.com/maps?q=" + lat + "," + lon

    private fun geoUri(lat: Double, lon: Double, alt: Double, acc: Float): String {
        val sb = StringBuilder("geo:").append(lat).append(",").append(lon)
        if (alt != 0.0) sb.append("?z=").append(alt)
        if (acc >= 0f) {
            sb.append(if (sb.contains("?")) "&" else "?")
            sb.append("u=").append(acc.toInt())
        }
        return sb.toString()
    }

    /**
     * Plus Code (Open Location Code). Names the same cell as the fix with a short code
     * that stays valid offline. The official library is used rather than a hand-rolled
     * encoder: the code length matters and getting it wrong silently moves the point.
     */
    fun plusCode(lat: Double, lon: Double): String = try {
        OpenLocationCode.encode(lat, lon, PLUS_CODE_LENGTH)
    } catch (t: Throwable) {
        Log.w(TAG, "plus code failed: " + t.message)
        ""
    }

    private fun rawLine(fix: Location): String {
        val sb = StringBuilder()
        sb.append("lat=").append(fix.latitude).append(" lon=").append(fix.longitude)
        sb.append(" alt=").append(fix.altitude)
        sb.append(" acc=").append(fix.accuracy)
        sb.append(" provider=").append(fix.provider)
        sb.append(" time=").append(fix.time)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && fix.verticalAccuracyMeters >= 0f) {
            sb.append(" vaccc=").append(fix.verticalAccuracyMeters)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && fix.speedAccuracyMetersPerSecond >= 0f) {
            sb.append(" saccc=").append(fix.speedAccuracyMetersPerSecond)
        }
        return sb.toString()
    }

    /** File name for the location text: `location_2026-09-24_10-30-00.txt`. */
    private fun stampName(timeMs: Long): String =
        "location_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(timeMs) + ".txt"

    companion object {
        private const val TAG = "LocationCollector"

        /** Plus Code local length: the global code plus 4 local digits (~20 m cell). */
        private const val PLUS_CODE_LENGTH = 10

        /** Newest-fix-first preference: GPS, then network, then the passive listener. */
        private val PROVIDER_ORDER = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )

        const val KEY_MAPS = "maps"
        const val KEY_GEO = "geo"
        const val KEY_PLUS = "plusCode"
        const val KEY_RAW = "raw"
        const val KEY_STAMP = "stamp"
    }
}
