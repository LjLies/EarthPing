package io.github.ljlies.earthping.alert

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import io.github.ljlies.earthping.EventLog
import io.github.ljlies.earthping.Prefs
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a synthesized siren (or soft beeps) on the ALARM stream, optionally at full volume.
 * When allowed, it temporarily lifts Do Not Disturb and restores volume / DND afterwards.
 */
class AlarmPlayer(private val ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val vibrator = ctx.getSystemService(Vibrator::class.java)
    private var track: AudioTrack? = null
    private var savedVolume: Int? = null
    private var savedFilter: Int? = null
    private val sampleRate = 22050

    /** One second of a rising/falling two-tone siren, pre-computed so starting is instant. */
    private val pcm: ShortArray by lazy {
        val n = sampleRate
        var phase = 0.0
        ShortArray(n) { i ->
            val t = i.toDouble() / n
            val f = if (t < 0.5) 650 + 900 * (t / 0.5) else 1550 - 900 * ((t - 0.5) / 0.5)
            phase += 2 * PI * f / sampleRate
            val env = when { i < 200 -> i / 200.0; i > n - 200 -> (n - i) / 200.0; else -> 1.0 }
            // a little square-ish harmonic content cuts through noise better than a pure sine
            val s = sin(phase) + 0.3 * sin(3 * phase)
            (s / 1.3 * env * Short.MAX_VALUE * 0.95).toInt().toShort()
        }
    }

    /**
     * An original alert chime in the spirit of an early-warning signal (not a copy of any
     * existing one): bright bell-like dyads (each note plus its tritone) rising through a
     * diminished-seventh arpeggio, then two higher repeats and a pause. 1.6 s per loop.
     */
    private val chime: ShortArray by lazy {
        val n = (sampleRate * 1.6).toInt()
        val mix = DoubleArray(n)
        val notes = listOf(0.00 to 988.0, 0.13 to 1175.0, 0.26 to 1397.0, 0.39 to 1661.0, 0.62 to 1976.0, 0.80 to 1976.0)
        for ((start, f) in notes) {
            val s0 = (start * sampleRate).toInt()
            val len = (0.32 * sampleRate).toInt()
            for (k in 0 until len) {
                val i = s0 + k
                if (i >= n) break
                val t = k.toDouble() / sampleRate
                val attack = minOf(1.0, t / 0.003)
                val body = kotlin.math.exp(-t / 0.10)
                val shimmer = kotlin.math.exp(-t / 0.03)
                var v = 0.0
                for (freq in doubleArrayOf(f, f * 1.41421356)) {
                    v += sin(2 * PI * freq * t) * body + 0.35 * sin(2 * PI * freq * 2.76 * t) * shimmer
                }
                mix[i] += v * attack
            }
        }
        val peak = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        ShortArray(n) { (mix[it] / peak * Short.MAX_VALUE * 0.92).toInt().toShort() }
    }

    /** One second with a short, soft 880 Hz chime: gentle enough for testing. */
    private val beeps: ShortArray by lazy {
        val n = sampleRate
        val len = sampleRate / 5
        ShortArray(n) { i ->
            if (i >= len) 0 else {
                val env = kotlin.math.exp(-5.0 * i / len) * minOf(1.0, i / 100.0)
                (sin(2 * PI * 880 * i / sampleRate) * env * Short.MAX_VALUE * 0.35).toInt().toShort()
            }
        }
    }

    /** A short, soft two-note chime announcing an update to an alert already under way. */
    private val updateChime: ShortArray by lazy {
        val n = (sampleRate * 0.55).toInt()
        val mix = DoubleArray(n)
        for ((start, f) in listOf(0.0 to 1318.5, 0.16 to 1760.0)) {
            val s0 = (start * sampleRate).toInt()
            for (k in 0 until n - s0) {
                val t = k.toDouble() / sampleRate
                mix[s0 + k] += sin(2 * PI * f * t) * kotlin.math.exp(-t / 0.08) * minOf(1.0, t / 0.004)
            }
        }
        val peak = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        ShortArray(n) { (mix[it] / peak * Short.MAX_VALUE * 0.45).toInt().toShort() }
    }

    fun warmUp() { pcm.size; beeps.size; chime.size; updateChime.size }

    private fun attrs() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    /** Short tracks being played; referenced so they are not collected before they finish. */
    private val oneShots = java.util.Collections.synchronizedList(ArrayList<AudioTrack>())

    /** Plays the update chime once, alongside whatever else is playing. */
    fun playUpdateChime() {
        try {
            val data = updateChime
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(data, 0, data.size)
            t.setNotificationMarkerPosition(data.size - 1)
            t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) { oneShots.remove(track); track.release() }
                override fun onPeriodicNotification(track: AudioTrack) {}
            }, android.os.Handler(android.os.Looper.getMainLooper()))
            oneShots += t
            t.play()
            // Safety net in case the end marker is never reported.
            handler.postDelayed({ if (oneShots.remove(t)) t.release() }, 3000)
            EventLog.log("Update chime played")
        } catch (e: Exception) {
            EventLog.log("Update chime failed: $e")
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val previewStop = Runnable { stop() }

    /** Settings preview: a few seconds of the tone at the current alarm volume, never louder. */
    @Synchronized
    fun preview(sound: Int, tone: Int, seconds: Int = 4): Boolean {
        if (started) { stop(); return false }
        val s = if (sound == Prefs.SOUND_SIREN_MAX) Prefs.SOUND_SIREN else sound
        start(s, tone, overrideSilence = false, vibrate = s == Prefs.SOUND_NONE)
        handler.postDelayed(previewStop, seconds * 1000L)
        return true
    }

    private var started = false

    @Synchronized
    fun start(sound: Int, tone: Int, overrideSilence: Boolean, vibrate: Boolean = true) {
        if (started) return
        started = true
        handler.removeCallbacks(previewStop)
        EventLog.log("Alarm sound start: sound=$sound tone=$tone override=$overrideSilence")
        // Speech also uses the alarm stream, so DND is lifted even when no siren is played;
        // the alarm volume is only forced to maximum for the full siren.
        if (overrideSilence) liftSilence(raiseVolume = sound == Prefs.SOUND_SIREN_MAX)
        val data = when (sound) {
            Prefs.SOUND_SIREN_MAX, Prefs.SOUND_SIREN -> if (tone == Prefs.TONE_SIREN) pcm else chime
            Prefs.SOUND_BEEPS -> beeps
            else -> null
        }
        if (data != null) try {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(data, 0, data.size)
            t.setLoopPoints(0, data.size, -1)
            t.play()
            track = t
        } catch (e: Exception) {
            EventLog.log("Alarm sound failed: $e")
        }
        if (vibrate) try {
            val pattern = longArrayOf(0, 700, 300)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        } catch (e: Exception) {
            EventLog.log("Vibration failed: $e")
        }
    }

    /** Lowers the siren while speech is playing. */
    @Synchronized
    fun duck(ducked: Boolean) {
        try { track?.setVolume(if (ducked) 0.2f else 1.0f) } catch (_: Exception) {}
    }

    @Synchronized
    fun stop() {
        if (started) EventLog.log("Alarm sound stop")
        started = false
        handler.removeCallbacks(previewStop)
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        restoreSilence()
    }

    val isPlaying: Boolean @Synchronized get() = started

    private fun liftSilence(raiseVolume: Boolean) {
        try {
            if (raiseVolume && savedVolume == null) {
                savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            }
        } catch (e: Exception) {
            EventLog.log("Cannot raise alarm volume: $e")
        }
        try {
            if (savedFilter == null && nm.isNotificationPolicyAccessGranted) {
                val f = nm.currentInterruptionFilter
                if (f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN && alarmsBlocked(nm, f)) {
                    if (android.os.Build.VERSION.SDK_INT >= 35) {
                        // Android 15 Modes: an app's setInterruptionFilter only switches the app's
                        // own mode, never the user's, so there is nothing EarthPing can lift.
                        EventLog.log("Do Not Disturb blocks alarms, and Android 15+ doesn't let apps lift it")
                        return
                    }
                    EventLog.log("Do Not Disturb blocks alarms: lifting it during the alarm")
                    savedFilter = f
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            }
        } catch (e: Exception) {
            EventLog.log("Cannot lift Do Not Disturb: $e")
        }
    }

    companion object {
        /**
         * Whether Do Not Disturb, as it is now, silences alarm sounds (the siren and speech play
         * as alarms). Usually it doesn't: "Alarms only" and most priority setups let them through.
         */
        fun alarmsBlocked(nm: NotificationManager, filter: Int = nm.currentInterruptionFilter, ifUnknown: Boolean = true): Boolean = when (filter) {
            NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> false
            NotificationManager.INTERRUPTION_FILTER_NONE -> true      // "Total silence"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> false   // "Alarms only"
            else -> if (android.os.Build.VERSION.SDK_INT < 28) false  // alarms always passed "Priority only"
            else try {
                // Android 15 combines manual DND with any active Modes.
                val policy = if (android.os.Build.VERSION.SDK_INT >= 35) nm.consolidatedNotificationPolicy else nm.notificationPolicy
                (policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) == 0
            } catch (e: Exception) {
                ifUnknown
            }
        }

        /**
         * 0.4.1–0.4.3 "restored" DND on Android 15+ by setting the filter it had read, which
         * there switches on a Do Not Disturb mode owned by EarthPing (and leaves it on). Turn
         * that mode off once; on Android 15+ this call affects only the app's own mode.
         */
        fun clearOwnDndMode(ctx: android.content.Context) {
            if (android.os.Build.VERSION.SDK_INT < 35) return
            val prefs = io.github.ljlies.earthping.Prefs(ctx)
            if (prefs.raw.getBoolean("own_dnd_mode_cleared", false)) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            try {
                if (nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    EventLog.log("Turned off EarthPing's own Do Not Disturb mode, if it was on")
                }
                prefs.raw.edit().putBoolean("own_dnd_mode_cleared", true).apply()
            } catch (e: Exception) {
                EventLog.log("Cannot clear EarthPing's Do Not Disturb mode: $e")
            }
        }
    }

    private fun restoreSilence() {
        savedVolume?.let { try { am.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } catch (_: Exception) {} }
        savedVolume = null
        savedFilter?.let { try { nm.setInterruptionFilter(it) } catch (_: Exception) {} }
        savedFilter = null
    }
}
