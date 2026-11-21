package io.github.luccoj.alertroid

import android.content.Context
import android.content.SharedPreferences
import io.github.luccoj.alertroid.gcm.Credentials
import io.github.luccoj.alertroid.geo.LatLng
import io.github.luccoj.alertroid.geo.S2

data class Place(val name: String, val lat: Double, val lon: Double) {
    val latLng get() = LatLng(lat, lon)
    val token get() = S2.token(lat, lon, 8)
}

data class Subscription(val place: Place, val primary: Boolean) {
    val token = place.token
    val topic = "ea.$token"
}

class Prefs(context: Context) {
    companion object {
        const val SOUND_SIREN_MAX = 0
        const val SOUND_SIREN = 1
        const val SOUND_BEEPS = 2
        const val SOUND_NONE = 3
        const val TONE_CHIME = 0
        const val TONE_SIREN = 1
    }

    private val p: SharedPreferences = context.applicationContext.getSharedPreferences("alertroid", Context.MODE_PRIVATE)

    private fun SharedPreferences.Editor.putDouble(k: String, v: Double) = putLong(k, java.lang.Double.doubleToRawLongBits(v))
    private fun getDouble(k: String, d: Double) = if (p.contains(k)) java.lang.Double.longBitsToDouble(p.getLong(k, 0)) else d

    var onboardingDone: Boolean
        get() = p.getBoolean("onboarding_done", false)
        set(v) = p.edit().putBoolean("onboarding_done", v).apply()

    var serviceEnabled: Boolean
        get() = p.getBoolean("service_enabled", true)
        set(v) = p.edit().putBoolean("service_enabled", v).apply()

    // ---- identity
    var credentials: Credentials?
        get() {
            val id = p.getString("android_id", null)?.toULongOrNull()?.toLong() ?: return null
            val tok = p.getString("security_token", null)?.toULongOrNull()?.toLong() ?: return null
            return Credentials(id, tok, p.getLong("creds_created", 0), p.getLong("creds_checkin", 0),
                p.getLong("creds_interval", 2L * 86400_000), p.getString("creds_digest", null))
        }
        set(c) {
            val e = p.edit()
            if (c == null) {
                e.remove("android_id").remove("security_token")
            } else {
                e.putString("android_id", java.lang.Long.toUnsignedString(c.androidId))
                    .putString("security_token", java.lang.Long.toUnsignedString(c.securityToken))
                    .putLong("creds_created", c.createdAtMs).putLong("creds_checkin", c.lastCheckinMs)
                    .putLong("creds_interval", c.checkinIntervalMs).putString("creds_digest", c.digest)
            }
            e.commit()
        }

    /** Wall-clock times of recent fresh registrations, for rate limiting. */
    var registrationTimes: List<Long>
        get() = p.getString("registrations", "")!!.split(',').mapNotNull { it.toLongOrNull() }
        set(v) = p.edit().putString("registrations", v.takeLast(20).joinToString(",")).apply()

    var useGenericProfile: Boolean
        get() = p.getBoolean("generic_profile", true)
        set(v) = p.edit().putBoolean("generic_profile", v).apply()

    var clockOffsetMs: Long
        get() = p.getLong("clock_offset", 0)
        set(v) = p.edit().putLong("clock_offset", v).apply()

    var pendingPersistentIds: List<String>
        get() = p.getString("pending_pids", "")!!.split('\n').filter { it.isNotBlank() }
        set(v) = p.edit().putString("pending_pids", v.takeLast(50).joinToString("\n")).apply()

    // ---- location
    fun lastLocation(): Pair<Place, Long>? {
        if (!p.contains("loc_lat")) return null
        return Place(p.getString("loc_src", "") ?: "", getDouble("loc_lat", 0.0), getDouble("loc_lon", 0.0)) to p.getLong("loc_time", 0)
    }

    fun setLastLocation(lat: Double, lon: Double, source: String, timeMs: Long) {
        p.edit().putDouble("loc_lat", lat).putDouble("loc_lon", lon).putString("loc_src", source).putLong("loc_time", timeMs).apply()
    }

    var manualLocationEnabled: Boolean
        get() = p.getBoolean("manual_loc", false)
        set(v) = p.edit().putBoolean("manual_loc", v).apply()

    var manualLocation: LatLng?
        get() = if (p.contains("manual_lat")) LatLng(getDouble("manual_lat", 0.0), getDouble("manual_lon", 0.0)) else null
        set(v) {
            if (v == null) p.edit().remove("manual_lat").remove("manual_lon").apply()
            else p.edit().putDouble("manual_lat", v.lat).putDouble("manual_lon", v.lon).apply()
        }

    /** The location alerts are computed for: manual if enabled, else the last fix. */
    fun effectiveLocation(): LatLng? {
        if (manualLocationEnabled) manualLocation?.let { return it }
        return lastLocation()?.first?.latLng
    }

    // ---- watched places ("decoys")
    var userDecoys: List<Place>
        get() = (0 until 3).mapNotNull { i ->
            val name = p.getString("decoy_${i}_name", null) ?: return@mapNotNull null
            Place(name, getDouble("decoy_${i}_lat", 0.0), getDouble("decoy_${i}_lon", 0.0))
        }
        set(list) {
            val e = p.edit()
            for (i in 0 until 3) {
                val d = list.getOrNull(i)
                if (d == null) e.remove("decoy_${i}_name").remove("decoy_${i}_lat").remove("decoy_${i}_lon")
                else e.putString("decoy_${i}_name", d.name).putDouble("decoy_${i}_lat", d.lat).putDouble("decoy_${i}_lon", d.lon)
            }
            e.apply()
        }

    var autoDecoyIndices: List<Int>
        get() = p.getString("auto_decoys", "")!!.split(',').mapNotNull { it.toIntOrNull() }
        set(v) = p.edit().putString("auto_decoys", v.joinToString(",")).apply()

    // ---- behaviour
    /** 0 = loud alert for every earthquake alert in my area; otherwise minimum estimated MMI. */
    var loudMinMmi: Int
        get() = p.getInt("loud_min_mmi", 0)
        set(v) = p.edit().putInt("loud_min_mmi", v).apply()

    var ttsEnabled: Boolean
        get() = p.getBoolean("tts", true)
        set(v) = p.edit().putBoolean("tts", v).apply()

    var overrideSilence: Boolean
        get() = p.getBoolean("override_silence", true)
        set(v) = p.edit().putBoolean("override_silence", v).apply()

    var watchedNotifications: Boolean
        get() = p.getBoolean("watched_notifs", true)
        set(v) = p.edit().putBoolean("watched_notifs", v).apply()

    /** One of [SOUND_SIREN_MAX], [SOUND_SIREN], [SOUND_BEEPS], [SOUND_NONE]. */
    var alarmSound: Int
        get() = p.getInt("alarm_sound", SOUND_SIREN_MAX)
        set(v) = p.edit().putInt("alarm_sound", v).apply()

    /** [TONE_CHIME] (default) or [TONE_SIREN]. */
    var alarmTone: Int
        get() = p.getInt("alarm_tone", TONE_CHIME)
        set(v) = p.edit().putInt("alarm_tone", v).apply()

    var testQuiet: Boolean
        get() = p.getBoolean("test_quiet", false)
        set(v) = p.edit().putBoolean("test_quiet", v).apply()

    var crisisAlerts: Boolean
        get() = p.getBoolean("crisis_alerts", true)
        set(v) = p.edit().putBoolean("crisis_alerts", v).apply()

    var heartbeatMinutes: Int
        get() = p.getInt("heartbeat_min", 5)
        set(v) = p.edit().putInt("heartbeat_min", v.coerceIn(1, 28)).apply()

    // ---- test mode
    var testDelayS: Int
        get() = p.getInt("test_delay", 10)
        set(v) = p.edit().putInt("test_delay", v).apply()
    var testLeadS: Int
        get() = p.getInt("test_lead", 25)
        set(v) = p.edit().putInt("test_lead", v).apply()
    var testMagnitude: Float
        get() = p.getFloat("test_mag", 5.8f)
        set(v) = p.edit().putFloat("test_mag", v).apply()
}
