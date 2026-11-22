package io.github.ljlies.earthping.gcm

import io.github.ljlies.earthping.proto.ProtoMessage
import io.github.ljlies.earthping.proto.ProtoWriter
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** An anonymous push identity: no Google account, no Play Services. */
data class Credentials(
    val androidId: Long,
    val securityToken: Long,
    val createdAtMs: Long,
    val lastCheckinMs: Long,
    val checkinIntervalMs: Long,
    val digest: String?,
)

/** What the checkin tells the server about this device (the real hardware by default). */
data class DeviceProfile(
    val fingerprint: String,
    val product: String,
    val brand: String,
    val radio: String,
    val bootloader: String,
    val device: String,
    val sdkInt: Int,
    val model: String,
    val manufacturer: String,
    val buildTimeS: Long,
    val locale: String,
    val timeZone: String,
) {
    companion object {
        /** A generic, well-known profile, used only if the server rejects the real one. */
        fun generic(locale: String, timeZone: String) = DeviceProfile(
            fingerprint = "google/oriole/oriole:14/UP1A.231005.007/10754064:user/release-keys",
            product = "oriole", brand = "google", radio = "g5123b-230810-230919-B-10762410",
            bootloader = "slider-1.2-10492471", device = "oriole", sdkInt = 34, model = "Pixel 6",
            manufacturer = "Google", buildTimeS = 1696464000L, locale = locale, timeZone = timeZone,
        )
    }
}

class CheckinException(message: String, val httpCode: Int = 0) : IOException(message)

/**
 * Google's device checkin (https://android.clients.google.com/checkin), which hands out the
 * android_id / security_token pair that authenticates the MCS push connection.
 */
object Checkin {
    private const val URL_CHECKIN = "https://android.clients.google.com/checkin"
    const val DEFAULT_INTERVAL_MS = 2L * 24 * 3600 * 1000
    private const val MIN_INTERVAL_MS = 12L * 3600 * 1000
    private const val MAX_INTERVAL_MS = 7L * 24 * 3600 * 1000
    private const val GMS_VERSION = 240913000L

    /** Server time measured on the last checkin (ms), for clock-offset estimation. */
    @Volatile var lastServerTimeOffsetMs: Long? = null
        private set

    fun buildRequest(profile: DeviceProfile, creds: Credentials?): ByteArray {
        val w = ProtoWriter()
        w.varint(2, creds?.androidId ?: 0L)                               // id
        creds?.digest?.let { w.string(3, it) }                           // settings digest
        w.message(4) {                                                    // AndroidCheckinProto
            message(1) {                                                  // AndroidBuildProto
                string(1, profile.fingerprint)
                string(2, profile.product)
                string(3, profile.brand)
                string(4, profile.radio)
                string(5, profile.bootloader)
                string(6, "android-google")                               // client id
                varint(7, profile.buildTimeS)
                varint(8, GMS_VERSION)                                    // google services version
                string(9, profile.device)
                varint(10, profile.sdkInt)
                string(11, profile.model)
                string(12, profile.manufacturer)
                string(13, profile.product)
                bool(14, false)                                           // ota installed
            }
            varint(2, creds?.lastCheckinMs ?: 0L)                         // last checkin
            varint(12, 1)                                                 // DEVICE_ANDROID_OS
        }
        w.string(6, profile.locale)
        w.string(12, profile.timeZone)
        w.fixed64(13, creds?.securityToken ?: 0L)
        w.varint(14, 3)                                                   // protocol version
        w.varint(22, 0)                                                   // user serial number
        return w.toByteArray()
    }

    /** Performs a checkin; with [creds] it refreshes that identity, without it registers a new one. */
    fun run(profile: DeviceProfile, creds: Credentials?, nowMs: Long = System.currentTimeMillis()): Credentials {
        val body = buildRequest(profile, creds)
        val conn = URL(URL_CHECKIN).openConnection() as HttpURLConnection
        val t0 = System.currentTimeMillis()
        val data: ByteArray
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Content-Type", "application/x-protobuffer")
            conn.setRequestProperty("User-Agent", "Android-Checkin/2.0 (${profile.device} ${profile.fingerprint.substringAfter(':').substringBefore('/')})")
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 200) throw CheckinException("Checkin HTTP $code", code)
            data = conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
        val t1 = System.currentTimeMillis()
        val r = ProtoMessage.parseOrNull(data) ?: throw CheckinException("Unparseable checkin response")
        val androidId = r.long(7) ?: 0L
        val token = r.long(8) ?: 0L
        if (androidId == 0L || token == 0L) throw CheckinException("Checkin returned no identity")
        r.long(3)?.let { serverMs -> lastServerTimeOffsetMs = serverMs - (t0 + t1) / 2 }
        var interval = DEFAULT_INTERVAL_MS
        r.messages(5).forEach { s ->
            if (s.string(1) == "checkin_interval") {
                s.string(2)?.toLongOrNull()?.let { interval = it * 1000 }
            }
        }
        return Credentials(
            androidId = androidId,
            securityToken = token,
            createdAtMs = creds?.createdAtMs ?: nowMs,
            lastCheckinMs = nowMs,
            checkinIntervalMs = interval.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
            digest = r.string(4) ?: creds?.digest,
        )
    }
}
