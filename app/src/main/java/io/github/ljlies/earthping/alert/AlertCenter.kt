package io.github.ljlies.earthping.alert

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import io.github.ljlies.earthping.EventLog
import io.github.ljlies.earthping.Places
import io.github.ljlies.earthping.Prefs
import io.github.ljlies.earthping.R
import io.github.ljlies.earthping.Subscription
import io.github.ljlies.earthping.gcm.DataMessage
import io.github.ljlies.earthping.geo.Geo
import io.github.ljlies.earthping.geo.LatLng
import io.github.ljlies.earthping.geo.S2
import io.github.ljlies.earthping.ui.AlertActivity
import io.github.ljlies.earthping.ui.AppVisibility
import io.github.ljlies.earthping.ui.MainActivity
import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random

/** An earthquake alert for the user's own location, as shown on screen and spoken. */
class LocalQuake(
    @Volatile var alert: GoogleAlert,
    val user: LatLng,
    val isTest: Boolean,
    val receivedAtMs: Long,
    private val clockOffsetMs: Long,
) {
    @Volatile var loud = false
    @Volatile var dismissed = false
    /** Sound to use; tests may be quiet. */
    var sound = 0
    /** Playing the full-volume siren because the expected shaking is strong. */
    @Volatile var siren = false
    /** Incremented on every revision received from Google (the UI redraws on change). */
    @Volatile var revision = 0
    @Volatile var updatedAtMs = 0L
    /** The "earthquake nearby" chime was handled (it sounds once per quake). */
    @Volatile var noticeChimed = false
    /** The first alert's id: revisions may come with other ids. */
    val eventId = alert.id

    val quake get() = alert.quake
    val epicenter get() = quake?.epicenter ?: alert.center()
    val magnitude get() = quake?.magnitude
    val distanceKm get() = epicenter?.let { Geo.distanceKm(user, it) }
    val hypoKm get() = distanceKm?.let { Geo.hypocentralKm(it, quake?.depthKm) }
    val originMs get() = quake?.originMs

    /** S-wave speed: the one in the alert when present, otherwise a crustal average. */
    val sSpeedKms get() = quake?.waveSpeedMps?.takeIf { it in 2000..6000 }?.div(1000.0) ?: Geo.S_WAVE_KMS
    val pSpeedKms get() = sSpeedKms * (Geo.P_WAVE_KMS / Geo.S_WAVE_KMS)

    val sArrivalMs: Long? get() { val o = originMs ?: return null; val h = hypoKm ?: return null; return o + (h / sSpeedKms * 1000).toLong() }
    val pArrivalMs: Long? get() { val o = originMs ?: return null; val h = hypoKm ?: return null; return o + (h / pSpeedKms * 1000).toLong() }

    val estimatedMmi: Double? get() { val m = magnitude ?: return null; val h = hypoKm ?: return null; return Geo.estimateMmi(m, h) }

    /** Highest-intensity contour the user is inside (5 = MMI5+, 4, 3), or null. */
    val contourMmi: Int? get() = alert.contours.filter { c ->
        c.rings.any { Geo.inside(user, it) } || c.circles.any { Geo.distanceKm(user, it.center) * 1000 <= it.radiusM }
    }.maxOfOrNull { it.mmi }

    val effectiveMmi: Double? get() = listOfNotNull(estimatedMmi, contourMmi?.toDouble()).maxOrNull()

    fun now() = System.currentTimeMillis() + clockOffsetMs
    /** Corrected S-wave arrival expressed in this device's clock. */
    val sArrivalLocalMs: Long? get() = sArrivalMs?.minus(clockOffsetMs)
}

/**
 * Receives decoded push messages, decides what they mean for the user and raises the right
 * level of alarm. Must be [init]ed with a context before use.
 */
object AlertCenter {
    private lateinit var app: Context
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    lateinit var player: AlarmPlayer
        private set
    lateinit var speaker: Speaker
        private set
    private val seenPayloads = LinkedHashSet<String>()
    private val crisisSeen = HashMap<String, String>()

    @Volatile var active: LocalQuake? = null
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { it() } }

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = Prefs(app)
        player = AlarmPlayer(app).also { it.warmUp() }
        speaker = Speaker(app) { speaking -> player.duck(speaking) }
        speaker.setLocale(speechLocale())
        speaker.warmUp()
        AlertHistory.init(app)
    }

    private fun str(id: Int, vararg args: Any) = app.getString(id, *args)
    private fun fmt1(v: Double) = String.format(Locale.getDefault(), "%.1f", v)
    private fun km(v: Double) = if (v < 10) fmt1(v) else Math.round(v).toString()

    // ---- speech, in its own language (independent of the app's)

    /** The language to speak: the one chosen in settings, or the app's current language. */
    fun speechLocale(): Locale {
        val tag = prefs.ttsLanguage
        val l = if (tag.isNotBlank()) Locale.forLanguageTag(tag) else app.resources.configuration.locales[0] ?: Locale.getDefault()
        // Only language and region: regional-preference extensions (-u-…) confuse some engines.
        return Locale.Builder().setLanguage(l.language).setRegion(l.country).build()
    }

    private fun speechContext(): Context {
        val conf = android.content.res.Configuration(app.resources.configuration)
        conf.setLocale(speechLocale())
        return app.createConfigurationContext(conf)
    }

    private class Spoken(val ctx: Context, val locale: Locale) {
        fun s(id: Int, vararg args: Any) = ctx.getString(id, *args)
        fun seconds(n: Int) = ctx.resources.getQuantityString(R.plurals.tts_seconds, n, n)
        fun shaking(mmi: Double) = ctx.resources.getStringArray(R.array.shaking_terms)[(Math.round(mmi).toInt().coerceIn(3, 10)) - 3]
        fun fmt1(v: Double) = String.format(locale, "%.1f", v)
        fun km(v: Double) = if (v < 10) fmt1(v) else Math.round(v).toString()

        /**
         * A countdown step: "25 seconds." for the round markers above ten, then a bare "Ten."
         * (short, so it is not cut off; a sentence, so neural voices don't clip its start).
         */
        fun countdown(n: Int) = if (n > 10) ctx.resources.getQuantityString(R.plurals.tts_seconds_marker, n, n)
            else NumberWords.spell(n, locale).replaceFirstChar { it.titlecase(locale) } + "."
    }

    private fun spoken(): Spoken {
        val l = speechLocale()
        speaker.setLocale(l)
        speaker.rateFactor = prefs.ttsRate
        return Spoken(speechContext(), l)
    }

    /**
     * "Also in silent mode": the notification's own chime is muted in silent and vibrate mode,
     * so play it on the alarm stream instead. Not during Do Not Disturb, and not if the user
     * silenced the channel.
     */
    private fun chimeIfSilenced() {
        if (prefs.noticeSound != "chime_always") return
        val am = app.getSystemService(android.media.AudioManager::class.java)
        val nm = app.getSystemService(NotificationManager::class.java)
        if (am.ringerMode == android.media.AudioManager.RINGER_MODE_NORMAL) return
        if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        if (!Notifications.noticeChannelAudible(app)) return
        EventLog.log("Silent mode: playing the notification chime on the alarm stream")
        playNoticeChime()
    }

    /** Tests page: the chime of the discreet "earthquake nearby" notification, as configured. */
    fun playNoticeChime() {
        try {
            val usage = if (prefs.noticeSound == "chime_always") android.media.AudioAttributes.USAGE_ALARM
                else android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT
            android.media.MediaPlayer().apply {
                setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(usage)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(app, android.net.Uri.parse("android.resource://${app.packageName}/${R.raw.notice_chime}"))
                setOnCompletionListener { it.release() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            EventLog.log("Notice chime failed: $e")
        }
    }

    /** "Test speech" in settings: a sample warning in the chosen language. */
    fun speakSample(): Boolean? {
        if (!speaker.available) { speaker.warmUp(); return null }
        val sp = spoken()
        speaker.announce(listOf(sp.s(R.string.tts_test), sp.s(R.string.tts_earthquake), sp.s(R.string.tts_magnitude, sp.fmt1(5.8)),
            sp.s(R.string.tts_distance, sp.km(82.0)), sp.seconds(20)).joinToString(" "))
        return speaker.localeSupported
    }

    // ------------------------------------------------------------------ incoming

    fun onDataMessage(msg: DataMessage) {
        val wl = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "earthping:message")
        wl.acquire(60_000)
        try {
            val alerts = when {
                msg.rawData != null -> AlertDecoder.decodeBatch(msg.rawData)
                msg.appString("ea.msg") != null -> listOfNotNull(AlertDecoder.decodeLegacy(msg.appString("ea.msg")!!))
                else -> emptyList()
            }
            EventLog.log("Push message from ${msg.from ?: "?"} (${msg.category ?: "?"}, pid=${msg.persistentId}): ${alerts.size} alert(s)")
            if (alerts.isEmpty()) return
            val digest = MessageDigest.getInstance("SHA-256").digest(msg.rawData ?: msg.appString("ea.msg")!!.toByteArray())
            val key = (msg.persistentId ?: "") + "|" + digest.take(12).joinToString("") { "%02x".format(it) }
            synchronized(seenPayloads) {
                if (!seenPayloads.add(key)) { EventLog.log("Duplicate delivery ignored"); return }
                while (seenPayloads.size > 300) seenPayloads.remove(seenPayloads.first())
            }
            val subs = Places.subscriptions(app)
            for (a in alerts) {
                try { handle(a, msg.from, subs, msg.receivedAtMs) } catch (e: Exception) { EventLog.log("Alert handling failed: $e") }
            }
        } finally {
            if (wl.isHeld) wl.release()
        }
    }

    /** Which subscription (own cell or a watched place) an alert was delivered for. */
    fun match(a: GoogleAlert, from: String?, subs: List<Subscription>): Subscription? {
        if (subs.isEmpty()) return null
        from?.let { f -> subs.firstOrNull { f.contains(it.topic) }?.let { return it } }
        val ordered = subs.sortedByDescending { it.primary }
        ordered.firstOrNull { s -> a.cells.any { S2.related(it, s.token) } }?.let { return it }
        val rings = a.allRings()
        ordered.firstOrNull { s -> rings.any { Geo.inside(s.place.latLng, it) } }?.let { return it }
        val center = a.center() ?: return ordered.first()
        return subs.minByOrNull { s ->
            val ringDist = rings.minOfOrNull { Geo.minDistanceKm(s.place.latLng, it) }
            ringDist ?: Geo.distanceKm(s.place.latLng, center)
        }
    }

    private fun handle(a: GoogleAlert, from: String?, subs: List<Subscription>, receivedAt: Long) {
        val sub = match(a, from, subs)
        val label = a.quake?.let { "M${it.magnitude} at ${it.epicenter}" } ?: (a.title ?: a.legacyRegionName ?: a.type.name)
        EventLog.log("Alert ${a.id} [${a.type}/${a.mode}] $label -> ${sub?.let { if (it.primary) "my location" else it.place.name } ?: "unknown"}")
        if (a.mode == GoogleAlert.Mode.TEST) {
            AlertHistory.add(app, a, sub, note = "Google test alert (not shown)")
            return
        }
        if (a.isEarthquake) {
            if (sub == null || sub.primary) {
                val user = prefs.effectiveLocation()
                if (user != null) { localQuake(a, user, isTest = false, receivedAt); return }
            }
            watchedQuake(a, sub)
        } else {
            crisis(a, sub)
        }
    }

    // ------------------------------------------------------------------ local earthquakes

    private fun sameQuake(q: LocalQuake, a: GoogleAlert): Boolean {
        if (q.alert.id == a.id) return true
        // Tests only ever revise themselves, and real alerts never merge into a test.
        if (q.isTest || a.id.startsWith("test-")) return false
        val e1 = q.epicenter; val e2 = a.quake?.epicenter
        val o1 = q.originMs; val o2 = a.quake?.originMs
        return e1 != null && e2 != null && o1 != null && o2 != null &&
            Geo.distanceKm(e1, e2) < 150 && kotlin.math.abs(o1 - o2) < 90_000
    }

    private fun localQuake(a: GoogleAlert, user: LatLng, isTest: Boolean, receivedAt: Long) {
        val current = active
        if (current != null && sameQuake(current, a) && System.currentTimeMillis() - current.receivedAtMs < 10 * 60_000) {
            revise(current, a)
            return
        }
        val q = LocalQuake(a, user, isTest, receivedAt, prefs.clockOffsetMs)
        q.sound = if (isTest && prefs.testQuiet) Prefs.SOUND_NONE else prefs.alarmSound
        active = q
        AlertHistory.add(app, a, null, note = if (isTest) "Test" else null, local = true, distanceKm = q.distanceKm, at = user)
        val loud = shouldBeLoud(q)
        EventLog.log("Decision for ${a.id}: estimated intensity ${q.estimatedMmi?.let { String.format(Locale.US, "%.1f", it) } ?: "?"}" +
            ", inside contour ${q.contourMmi ?: "none"}, loud threshold ${prefs.loudMinMmi.takeIf { it > 0 } ?: "any"}" +
            ", siren threshold ${prefs.sirenMinMmi.takeIf { it > 0 } ?: "never"} → ${if (loud) "LOUD" else "notice"}")
        if (loud) startLoud(q) else postLocalNotification(q)
        changed()
    }

    /**
     * Google revises an alert within seconds (magnitude, epicentre, depth, contours), pushing
     * each revision on the same connection. Update everything shown, say what changed, and
     * escalate if the quake got worse.
     */
    private fun revise(q: LocalQuake, a: GoogleAlert) {
        val oldMag = q.magnitude
        val oldDist = q.distanceKm
        val oldMmi = q.effectiveMmi
        val oldS = q.sArrivalLocalMs
        q.alert = a
        q.revision++
        q.updatedAtMs = System.currentTimeMillis()
        AlertHistory.add(app, a, null, local = true, distanceKm = q.distanceKm, event = q.eventId, at = q.user)

        val newMag = q.magnitude
        val newDist = q.distanceKm
        val newMmi = q.effectiveMmi
        val newS = q.sArrivalLocalMs
        val magChanged = oldMag != null && newMag != null && kotlin.math.abs(newMag - oldMag) >= 0.1
        val distChanged = oldDist != null && newDist != null && kotlin.math.abs(newDist - oldDist) >= 5
        val timeChanged = oldS != null && newS != null && kotlin.math.abs(newS - oldS) >= 2000
        val mmiChanged = oldMmi != null && newMmi != null && Geo.roman(oldMmi) != Geo.roman(newMmi)
        val notes = ArrayList<String>()
        if (magChanged) notes += String.format(Locale.US, "M%.1f → M%.1f", oldMag, newMag)
        if (distChanged) notes += "${Math.round(oldDist!!)} → ${Math.round(newDist!!)} km"
        if (mmiChanged) notes += "intensity ${Geo.roman(oldMmi!!)} → ${Geo.roman(newMmi!!)}"
        EventLog.log("Revision ${q.revision} of ${a.id}: ${notes.joinToString(", ").ifEmpty { "no visible change" }}")

        val worse = (newMag != null && oldMag != null && newMag - oldMag >= 0.5) ||
            (newMmi != null && oldMmi != null && newMmi - oldMmi >= 1.0)
        when {
            q.dismissed && worse -> {
                // The user had silenced it, but it is now clearly stronger: alarm again.
                q.dismissed = false
                q.loud = false
                if (shouldBeLoud(q)) startLoud(q) else postLocalNotification(q)
            }
            q.dismissed -> Unit // stay silent; the screen and history still update
            !q.loud && shouldBeLoud(q) -> startLoud(q)
            else -> {
                val visibleChange = magChanged || distChanged || mmiChanged || timeChanged
                val shakingOver = (q.sArrivalLocalMs ?: q.receivedAtMs) < System.currentTimeMillis() - 30_000
                if (visibleChange && q.loud) {
                    if (shakingOver && !alertScreenVisible) {
                        // Long after the shaking, with the warning closed: a plain notification.
                        postUpdateNotification(q)
                    } else {
                        if (!q.siren && sirenWanted(q)) {
                            // Now expected to be strong: switch to the full-volume siren.
                            q.siren = true
                            player.stop()
                            player.start(Prefs.SOUND_SIREN_MAX, Prefs.TONE_SIREN, overrideSilence = true)
                            scheduleSirenStop(q)
                        } else if (q.sound != Prefs.SOUND_NONE) {
                            player.playUpdateChime()
                        }
                        if (prefs.ttsEnabled && (magChanged || mmiChanged)) {
                            updateSpeech(q, oldMag, mmiChanged, spoken()).takeIf { it.isNotEmpty() }?.let { speaker.announce(it) }
                        }
                    }
                }
                postLocalNotification(q) // updates the existing notification in place, quietly
            }
        }
        // Silenced, and the warning no longer on screen: changes arrive as a heads-up notification.
        if (q.dismissed && !worse && !alertScreenVisible && (magChanged || distChanged || mmiChanged)) postUpdateNotification(q)
        changed()
    }

    /** A revision that arrives when the warning is no longer on screen: an ordinary notification. */
    private fun postUpdateNotification(q: LocalQuake) {
        val nm = app.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(app, 13, Intent(app, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(Notifications.ID_LOCAL_QUAKE + 1, Notification.Builder(app, Notifications.CH_NOTICE)
            .setSmallIcon(R.drawable.ic_stat_quake)
            .setContentTitle(str(R.string.notif_update_title, localTitle(q)))
            .setContentText(localText(q))
            .setStyle(Notification.BigTextStyle().bigText(localText(q)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
        EventLog.log("Update notification posted")
        chimeIfSilenced()
    }

    /** The full-screen warning reports whether it is on screen (no heads-up should cover it). */
    @Volatile var alertScreenVisible = false
        private set

    fun onAlertScreen(visible: Boolean) {
        alertScreenVisible = visible
        EventLog.log("Alert screen ${if (visible) "shown" else "hidden"}")
        val q = active ?: return
        if (visible && q.loud && !q.dismissed && lastChannel != Notifications.CH_ALERT_SHOWN) {
            // Replace the heads-up with the silent version, so it doesn't cover the screen.
            app.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_LOCAL_QUAKE)
            postLocalNotification(q)
        }
    }

    private var lastChannel: String? = null

    /**
     * No "update" word (the chime says that). A new shaking level comes first: "Now expecting
     * strong shaking, magnitude 7.4."; otherwise just the direction: "Now higher magnitude 6.2."
     * With little time left only a new shaking level is said, without the magnitude.
     */
    private fun updateSpeech(q: LocalQuake, oldMag: Double?, mmi: Boolean, sp: Spoken): String {
        val newMag = q.magnitude
        val hurry = (secondsLeft(q) ?: Int.MAX_VALUE) <= 12
        val shaking = q.effectiveMmi?.let { sp.shaking(it) }
        return when {
            mmi && shaking != null && (hurry || newMag == null) -> sp.s(R.string.tts_expecting, shaking)
            mmi && shaking != null -> sp.s(R.string.tts_expecting_mag, shaking, sp.fmt1(newMag!!))
            hurry -> ""
            newMag != null && oldMag != null -> sp.s(if (newMag > oldMag) R.string.tts_mag_higher else R.string.tts_mag_lower, sp.fmt1(newMag))
            else -> ""
        }
    }

    /** Intensity index into the shaking/intensity term arrays (III and below → 0 … X → 7). */
    private fun termIndex(mmi: Double) = (Math.round(mmi).toInt().coerceIn(3, 10)) - 3

    fun intensityTerm(ctx: Context, mmi: Double): String = ctx.resources.getStringArray(R.array.intensity_terms)[termIndex(mmi)]

    /** The full-volume siren is used when the expected shaking reaches the chosen level. */
    private fun sirenWanted(q: LocalQuake): Boolean {
        if (q.isTest && prefs.testQuiet) return false
        val min = prefs.sirenMinMmi
        val mmi = q.effectiveMmi ?: return false
        return min > 0 && Math.round(mmi) >= min
    }

    private fun shouldBeLoud(q: LocalQuake): Boolean {
        val min = prefs.loudMinMmi
        if (min <= 0) return true
        val mmi = q.effectiveMmi ?: return true
        // Compare the same rounded level the user sees on screen ("VI"), not the raw estimate.
        return Math.round(mmi) >= min
    }

    private val SIREN_TOKEN = Any()

    private fun startLoud(q: LocalQuake) {
        q.loud = true
        app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "earthping:alert").acquire(3 * 60_000L)
        if (!speaker.available) speaker.warmUp()
        q.siren = sirenWanted(q)
        if (q.siren) EventLog.log("Strong shaking expected: full-volume siren")
        if (q.siren) player.start(Prefs.SOUND_SIREN_MAX, Prefs.TONE_SIREN, overrideSilence = true)
        else player.start(q.sound, prefs.alarmTone, prefs.overrideSilence)
        if (showAlertScreen()) {
            // The screen is on its way: only fall back to the loud notification if it didn't show.
            postLocalNotification(q, screenExpected = true)
            main.postDelayed({ if (!alertScreenVisible && active === q && !q.dismissed) postLocalNotification(q) }, 1500)
        } else {
            postLocalNotification(q)
        }
        if (prefs.ttsEnabled) {
            val sp = spoken()
            speaker.start(announcement(q, sp), { q.sArrivalLocalMs?.takeIf { it > System.currentTimeMillis() - 1000 } }, sp::seconds, sp::countdown)
        }
        scheduleSirenStop(q)
    }

    /** The siren stops a few seconds after the shaking should have arrived (or after 30 s). */
    private fun scheduleSirenStop(q: LocalQuake) {
        main.removeCallbacksAndMessages(SIREN_TOKEN)
        val now = System.currentTimeMillis()
        val stopAt = (q.sArrivalLocalMs?.let { it + 5_000 } ?: (now + 30_000)).coerceAtLeast(now + 8_000)
        main.postAtTime({
            // A revision may have pushed the arrival later: check again.
            val s = q.sArrivalLocalMs
            if (s != null && s + 5_000 > System.currentTimeMillis() + 1_000) scheduleSirenStop(q)
            else if (active === q) player.stop()
        }, SIREN_TOKEN, SystemClock.uptimeMillis() + (stopAt - now))
    }

    /**
     * The first sentence, shorter when there is little time: the distance goes below 15 s, the
     * magnitude below 8 s. The seconds left follow separately (Speaker), so they are current.
     */
    private fun announcement(q: LocalQuake, sp: Spoken): List<String> {
        val secs = secondsLeft(q) ?: Int.MAX_VALUE
        return listOfNotNull(
            if (q.isTest) sp.s(R.string.tts_test) else null,
            sp.s(R.string.tts_earthquake),
            q.magnitude?.takeIf { secs >= 8 }?.let { sp.s(R.string.tts_magnitude, sp.fmt1(it)) },
            q.distanceKm?.takeIf { secs >= 15 }?.let { sp.s(R.string.tts_distance, sp.km(it)) },
        )
    }

    private fun secondsLeft(q: LocalQuake): Int? = q.sArrivalLocalMs?.let { ((it - System.currentTimeMillis()) / 1000).toInt() }

    /** Wakes the screen and opens the warning directly when allowed. Returns whether it tried. */
    private fun showAlertScreen(): Boolean {
        // Turn the screen on right away (deprecated flag, but still honoured), so the alert
        // screen -- which is allowed over the lock screen -- is visible even if the display was off.
        try {
            @Suppress("DEPRECATION")
            app.getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "earthping:screen").acquire(15_000)
        } catch (e: Exception) {
            EventLog.log("Cannot wake the screen: $e")
        }
        val i = Intent(app, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Direct start works with "display over other apps" or while our UI is visible; the
        // full-screen intent on the notification is the other path (and covers the lock screen).
        if (AppVisibility.visible || Settings.canDrawOverlays(app)) {
            try {
                app.startActivity(i)
                return true
            } catch (e: Exception) {
                EventLog.log("Cannot open alert screen: $e")
            }
        } else {
            EventLog.log("Alert screen via full-screen notification (no overlay permission)")
        }
        return false
    }

    /** Title for the notification or screen; [ctx] decides the language (an activity may differ). */
    fun localTitle(q: LocalQuake, ctx: Context = app): String {
        val mag = q.magnitude?.let { " M${fmt1(it)}" } ?: ""
        return (if (q.isTest) ctx.getString(R.string.test_prefix) + " " else "") + ctx.getString(R.string.notif_quake_title, mag)
    }

    fun localText(q: LocalQuake, ctx: Context = app): String {
        val parts = ArrayList<String>()
        q.distanceKm?.let { parts += ctx.getString(R.string.km_away, km(it)) }
        q.effectiveMmi?.let { parts += ctx.getString(R.string.est_intensity, Geo.roman(it) + " (" + intensityTerm(ctx, it) + ")") }
        q.alert.title?.let { parts += it }
        return parts.joinToString(" · ")
    }

    private fun postLocalNotification(q: LocalQuake, screenExpected: Boolean = false) {
        val nm = app.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(app, 10, Intent(app, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getBroadcast(app, 11, Intent(app, StopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val channel = when {
            !q.loud -> Notifications.CH_NOTICE
            // The warning is on screen: a silent notification, so no heads-up covers it.
            alertScreenVisible || screenExpected -> Notifications.CH_ALERT_SHOWN
            else -> Notifications.CH_QUAKE
        }
        val b = Notification.Builder(app, channel)
            .setSmallIcon(io.github.ljlies.earthping.R.drawable.ic_stat_quake)
            .setContentTitle(localTitle(q))
            .setContentText(localText(q))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setColor(0xFFD32F2F.toInt())
        val s = q.sArrivalLocalMs
        if (s != null && s > System.currentTimeMillis()) {
            b.setWhen(s).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        }
        if (q.loud && channel != Notifications.CH_ALERT_SHOWN) b.setFullScreenIntent(open, true)
        if (q.loud) {
            b.addAction(Notification.Action.Builder(null, str(R.string.stop_alarm), stop).build())
            b.setOngoing(true)
        }
        nm.notify(Notifications.ID_LOCAL_QUAKE, b.build())
        if (!q.loud && !q.noticeChimed) { q.noticeChimed = true; chimeIfSilenced() }
        if (channel != lastChannel) EventLog.log("Notification: ${if (q.loud) "loud" else "notice"} on $channel")
        lastChannel = channel
    }

    /** User pressed "stop" (in the alert screen or the notification). */
    fun silence() {
        player.stop()
        speaker.stop()
        active?.let {
            it.dismissed = true
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.cancel(Notifications.ID_LOCAL_QUAKE)
        }
        changed()
    }

    // ------------------------------------------------------------------ watched places & crisis alerts

    private fun watchedQuake(a: GoogleAlert, sub: Subscription?) {
        val q = a.quake
        val place = sub?.place
        val dist = if (place != null && q?.epicenter != null) Geo.distanceKm(place.latLng, q.epicenter) else null
        AlertHistory.add(app, a, sub, distanceKm = dist)
        if (place == null || Places.isAutoDecoy(place)) return
        val title = str(R.string.notif_watched_quake, place.name, q?.magnitude?.let { "M${fmt1(it)}" } ?: "")
        val text = buildString {
            dist?.let { append(str(R.string.km_from, km(it), place.name)) }
            if (q?.magnitude != null && dist != null) {
                append(" · ").append(str(R.string.est_intensity, Geo.roman(Geo.estimateMmi(q.magnitude, Geo.hypocentralKm(dist, q.depthKm)))))
            }
        }
        notifyOther(a.id, Notifications.CH_WATCHED, title, text)
    }

    private fun crisis(a: GoogleAlert, sub: Subscription?) {
        val fingerprint = "${a.title}|${a.areaText}|${a.expiresMs}"
        val previous = crisisSeen.put(a.id, fingerprint)
        if (previous == fingerprint) return // periodic re-issue
        AlertHistory.add(app, a, sub)
        if (!prefs.crisisAlerts || sub == null) return
        val local = sub.primary
        if (!local && Places.isAutoDecoy(sub.place)) return
        val title = (a.title ?: str(R.string.crisis_generic)) + if (!local) " · ${sub.place.name}" else ""
        val text = listOfNotNull(a.areaText, a.sender ?: a.sourceName).joinToString(" · ")
        notifyOther(a.id, if (local) Notifications.CH_CRISIS else Notifications.CH_WATCHED_PUBLIC, title, text, silent = previous != null)
    }

    private fun notifyOther(id: String, channel: String, title: String, text: String, silent: Boolean = false) {
        val nm = app.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(app, 12, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(1000 + (id.hashCode() and 0xFFFFF), Notification.Builder(app, channel)
            .setSmallIcon(R.drawable.ic_stat_quake)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(silent)
            .build())
    }

    // ------------------------------------------------------------------ test mode

    /** Synthesizes an alert near the user whose S-wave arrives [Prefs.testLeadS] seconds from now. */
    /** Store screenshots: a real (recorded) alert delivered as if it had just arrived. */
    internal fun deliverForScreenshot(a: GoogleAlert, user: LatLng) = localQuake(a, user, isTest = false, System.currentTimeMillis())

    fun fireTest() {
        main.post {
            val user = prefs.effectiveLocation() ?: LatLng(41.9028, 12.4964)
            val lead = prefs.testLeadS.coerceIn(3, 120)
            val mag = Math.round(prefs.testMagnitude * 10) / 10.0
            val depth = 10.0
            val latencyS = 8.0 // typical delay between rupture and Google's alert
            val now = System.currentTimeMillis()
            val hypo = Geo.S_WAVE_KMS * (lead + latencyS)
            val epi = kotlin.math.sqrt((hypo * hypo - depth * depth).coerceAtLeast(1.0))
            val epicenter = Geo.destination(user, Random.nextDouble(0.0, 360.0), epi)
            val contours = listOf(5.0 to 1, 4.0 to 2, 3.0 to 3).mapNotNull { (mmi, level) ->
                Geo.radiusForMmi(mag, depth, mmi)?.takeIf { it > 1 }?.let { Contour(level, listOf(Geo.circle(epicenter, it)), emptyList()) }
            }
            val origin = now - (latencyS * 1000).toLong() + prefs.clockOffsetMs
            val alert = GoogleAlert(
                id = "test-$now", issuedMs = now, updatedMs = now, expiresMs = null,
                mode = GoogleAlert.Mode.LIVE, type = GoogleAlert.Type.EARTHQUAKE,
                cells = emptyList(), contours = contours, areaPolygons = emptyList(),
                title = null, areaText = null, sender = null, sourceName = "EarthPing test", eventCode = null,
                quake = Quake(mag, epicenter, depth, origin, null, null, true),
            )
            EventLog.log("Firing test alert: M$mag, ${Math.round(epi)} km away, S-wave in $lead s")
            localQuake(alert, user, isTest = true, now)
            if (lead >= 10) main.postDelayed({
                // Only while this test is still the current alert (another test may have started).
                if (active?.alert?.id != alert.id) return@postDelayed
                val q2 = alert.quake!!
                val mag2 = mag + 0.4
                val epi2 = Geo.destination(epicenter, Random.nextDouble(0.0, 360.0), 8.0)
                val contours2 = listOf(5.0 to 1, 4.0 to 2, 3.0 to 3).mapNotNull { (mmi, level) ->
                    Geo.radiusForMmi(mag2, depth, mmi)?.takeIf { it > 1 }?.let { Contour(level, listOf(Geo.circle(epi2, it)), emptyList()) }
                }
                localQuake(alert.copy(contours = contours2, quake = q2.copy(magnitude = mag2, epicenter = epi2)), user, isTest = true, now)
            }, (lead * 1000L / 2).coerceIn(4000, 10_000)) // after the first announcement
        }
    }
}

/** "Stop alarm" from the notification. */
class StopReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlertCenter.init(context)
        AlertCenter.silence()
    }
}
