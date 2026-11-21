package io.github.luccoj.alertroid.alert

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import io.github.luccoj.alertroid.EventLog
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a synthesized siren on the ALARM stream at full volume. When allowed, it temporarily
 * lifts Do Not Disturb and restores the previous volume / DND state afterwards.
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

    fun warmUp() { pcm.size }

    @Synchronized
    fun start(overrideSilence: Boolean) {
        if (track != null) return
        if (overrideSilence) liftSilence()
        try {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(pcm, 0, pcm.size)
            t.setLoopPoints(0, pcm.size, -1)
            t.play()
            track = t
        } catch (e: Exception) {
            EventLog.log("Siren failed: $e")
        }
        try {
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
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        restoreSilence()
    }

    val isPlaying: Boolean @Synchronized get() = track != null

    private fun liftSilence() {
        try {
            if (savedVolume == null) {
                savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            }
        } catch (e: Exception) {
            EventLog.log("Cannot raise alarm volume: $e")
        }
        try {
            if (savedFilter == null && nm.isNotificationPolicyAccessGranted) {
                val f = nm.currentInterruptionFilter
                if (f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                    savedFilter = f
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            }
        } catch (e: Exception) {
            EventLog.log("Cannot lift Do Not Disturb: $e")
        }
    }

    private fun restoreSilence() {
        savedVolume?.let { try { am.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } catch (_: Exception) {} }
        savedVolume = null
        savedFilter?.let { try { nm.setInterruptionFilter(it) } catch (_: Exception) {} }
        savedFilter = null
    }
}
