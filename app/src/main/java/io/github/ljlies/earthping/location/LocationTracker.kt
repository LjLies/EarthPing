package io.github.ljlies.earthping.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.ljlies.earthping.EventLog

/**
 * Location for the alert cell (~38 km wide, so precision hardly matters):
 *  1. the platform NETWORK provider (coarse; on microG this is UnifiedNlp), plus passive fixes;
 *  2. GPS, sparingly: a single short attempt (stopped at the first fix, at most 60 s) only when
 *     the network provider has given nothing for an hour, backing off from 2 h to 12 h.
 */
class LocationTracker(private val ctx: Context, private val onLocation: (Location) -> Unit) {
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var lastNetworkFixElapsed = 0L
    private var gpsActive = false
    private var lastGpsAttemptElapsed = 0L
    private var gpsGapMs = GPS_MIN_GAP_MS

    companion object {
        private const val NETWORK_INTERVAL_MS = 15 * 60_000L
        // No distance filter: a stationary phone must still get periodic fixes, or it would
        // look like the network provider stopped working and GPS would be used needlessly.
        private const val NETWORK_MIN_DISTANCE_M = 0f
        private const val CHECK_EVERY_MS = 15 * 60_000L
        /** GPS is considered only when the network provider has been silent this long. */
        private const val NETWORK_SILENT_MS = 60 * 60_000L
        /** GPS attempts back off from 2 h to 12 h while the network provider stays silent. */
        private const val GPS_MIN_GAP_MS = 2 * 3600_000L
        private const val GPS_MAX_GAP_MS = 12 * 3600_000L
        private const val GPS_TIMEOUT_MS = 60_000L
    }

    private fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val hasCoarse get() = has(Manifest.permission.ACCESS_COARSE_LOCATION) || hasFine
    val hasFine get() = has(Manifest.permission.ACCESS_FINE_LOCATION)

    private val networkListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            if (l.provider == LocationManager.NETWORK_PROVIDER) {
                lastNetworkFixElapsed = SystemClock.elapsedRealtime()
                gpsGapMs = GPS_MIN_GAP_MS
            }
            deliver(l)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            deliver(l)
            stopGps()
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun deliver(l: Location) {
        if (l.latitude == 0.0 && l.longitude == 0.0) return
        onLocation(l)
    }

    private fun providerUsable(p: String) = try {
        lm.allProviders.contains(p) && lm.isProviderEnabled(p)
    } catch (e: Exception) { false }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (!hasCoarse) {
            EventLog.log("No location permission: using the saved/manual location only")
            return
        }
        running = true
        // Freshest last-known fix from any provider, so subscriptions are right immediately.
        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER, "fused")
            .mapNotNull { p -> try { if (lm.allProviders.contains(p)) lm.getLastKnownLocation(p) else null } catch (e: Exception) { null } }
            .maxByOrNull { it.elapsedRealtimeNanos }
        last?.let { deliver(it) }
        try {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let {
                val age = (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000
                lastNetworkFixElapsed = SystemClock.elapsedRealtime() - age
            }
        } catch (_: Exception) {}
        if (lastNetworkFixElapsed == 0L) {
            // Never had a network fix: if nothing at all is known, allow GPS at the first check
            // (5 min); otherwise give the network provider the full hour.
            lastNetworkFixElapsed = SystemClock.elapsedRealtime() - (if (last == null) NETWORK_SILENT_MS + 1 else 0L)
        }
        try {
            if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, NETWORK_INTERVAL_MS, NETWORK_MIN_DISTANCE_M, networkListener, Looper.getMainLooper())
            } else {
                EventLog.log("No network location provider on this device; GPS will be used")
            }
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 5 * 60_000L, 500f, networkListener, Looper.getMainLooper())
        } catch (e: Exception) {
            EventLog.log("Location updates failed: $e")
        }
        main.postDelayed(fallbackCheck, 5 * 60_000L)
    }

    private val fallbackCheck = object : Runnable {
        override fun run() {
            if (!running) return
            val t = SystemClock.elapsedRealtime()
            val networkSilent = !providerUsable(LocationManager.NETWORK_PROVIDER) || t - lastNetworkFixElapsed > NETWORK_SILENT_MS
            if (networkSilent && (lastGpsAttemptElapsed == 0L || t - lastGpsAttemptElapsed >= gpsGapMs)) {
                lastGpsAttemptElapsed = t
                if (startGps()) gpsGapMs = minOf(gpsGapMs * 2, GPS_MAX_GAP_MS)
            }
            main.postDelayed(this, CHECK_EVERY_MS)
        }
    }

    @SuppressLint("MissingPermission")
    /** One short GPS attempt: stops at the first fix or after [GPS_TIMEOUT_MS]. */
    private fun startGps(): Boolean {
        if (gpsActive || !hasFine || !providerUsable(LocationManager.GPS_PROVIDER)) return false
        EventLog.log("Network location silent: one short GPS attempt")
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
            gpsActive = true
            main.postDelayed(gpsTimeout, GPS_TIMEOUT_MS)
            true
        } catch (e: Exception) {
            EventLog.log("GPS request failed: $e")
            false
        }
    }

    private val gpsTimeout = Runnable { stopGps() }

    private fun stopGps() {
        main.removeCallbacks(gpsTimeout)
        if (!gpsActive) return
        gpsActive = false
        try { lm.removeUpdates(gpsListener) } catch (_: Exception) {}
    }

    fun stop() {
        running = false
        main.removeCallbacks(fallbackCheck)
        stopGps()
        try { lm.removeUpdates(networkListener) } catch (_: Exception) {}
    }

    /** Restart after a permission change. */
    fun restart() { stop(); start() }

    fun locationServicesOn(): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (e: Exception) { true }
}
