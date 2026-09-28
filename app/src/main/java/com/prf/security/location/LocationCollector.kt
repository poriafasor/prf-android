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
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone














class LocationCollector(private val context: Context) {

    


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
                
                Log.w(TAG, "provider " + provider + " unavailable: " + t.message)
            }
        }
        if (candidates.isEmpty()) return null
        
        return candidates.maxBy { it.time }
    }

    
















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
                
                
                
                
                
                suspendCancellableCoroutine<Location?> { cont ->
                    try {
                        lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { loc ->
                            if (cont.isActive) cont.resume(loc)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "single-shot fix refused: " + t.message)
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "single-shot fix failed: " + t.message)
            null
        } finally {
            signal.cancel()
        }
    }

    


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
            KEY_SOURCE to sourceOf(fix),
            KEY_ACCURACY to fix.accuracy.toInt().toString(),
        )
    }

    /**
     * Which of the two answers this fix actually is.
     *
     * Android returns a fix from the satellite receiver, from the cell network,
     * or the last one it was handed passively, and the panel shows GPS position
     * and network position as two separate cards. Saying which one this is what
     * puts the fix in the right card: without it every position lands in the GPS
     * card, including coarse cell estimates, which is how a two-kilometre
     * network answer ends up presented as a satellite fix.
     */
    private fun sourceOf(fix: Location): String = when (fix.provider) {
        LocationManager.GPS_PROVIDER -> "gps"
        LocationManager.NETWORK_PROVIDER -> "network"
        else -> "passive"
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

    
    private fun stampName(timeMs: Long): String =
        "location_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(timeMs) + ".txt"

    companion object {
        private const val TAG = "LocationCollector"

        
        private const val PLUS_CODE_LENGTH = 10

        
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
        const val KEY_SOURCE = "source"
        const val KEY_ACCURACY = "accuracyM"
    }
}
