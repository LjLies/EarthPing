package io.github.luccoj.alertroid.alert

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.luccoj.alertroid.EventLog
import java.util.Locale

/**
 * Text-to-speech warning and countdown, spoken on the alarm stream. The engine is initialized
 * when the service starts so that the first words come out without delay.
 */
class Speaker(private val ctx: Context, private val onSpeaking: (Boolean) -> Unit) {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val main = Handler(Looper.getMainLooper())
    private var countdownTarget: (() -> Long?)? = null
    private var lastSpoken = Int.MAX_VALUE
    private var finished = true
    private var finalText = ""
    @Volatile private var wanted: Locale = Locale.getDefault()
    private var applied: Locale? = null

    /** Whether the engine has a voice for the last locale asked for (null = unknown yet). */
    @Volatile var localeSupported: Boolean? = null
        private set

    /** Language for what is spoken next; independent of the app's language. */
    fun setLocale(locale: Locale) { wanted = locale }

    private fun applyLocale(t: TextToSpeech) {
        if (applied == wanted) return
        val res = t.setLanguage(wanted)
        localeSupported = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
        if (localeSupported == false) {
            EventLog.log("No ${wanted.displayName} voice installed for text-to-speech; using the engine default")
        }
        applied = wanted
    }

    fun warmUp() {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                applied = null
                applyLocale(t)
                t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                t.setSpeechRate(1.15f)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = onSpeaking(true)
                    override fun onDone(id: String?) = onSpeaking(false)
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = onSpeaking(false)
                })
                ready = true
            } else {
                EventLog.log("Text-to-speech is not available (status $status)")
                // Allow a later retry (e.g. after an engine is installed or enabled).
                main.post { try { tts?.shutdown() } catch (_: Exception) {}; tts = null }
            }
        }
    }

    val available get() = ready

    private fun say(text: String, flush: Boolean) {
        val t = tts ?: return
        if (!ready) return
        applyLocale(t)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
        t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, "a" + System.nanoTime())
    }

    /**
     * Speaks [announcement], then counts down to the time returned by [target] (S-wave arrival,
     * wall clock ms, already clock-corrected), and finally speaks [atZero].
     */
    fun start(announcement: String, target: () -> Long?, atZero: String) {
        main.post {
            stopInternal()
            finished = false
            finalText = atZero
            countdownTarget = target
            lastSpoken = Int.MAX_VALUE
            say(announcement, flush = true)
            main.postDelayed(tick, 250)
        }
    }

    fun announce(text: String) = main.post { say(text, flush = false) }

    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            val target = countdownTarget?.invoke()
            if (target == null) { finished = true; return }
            val remainingMs = target - System.currentTimeMillis()
            val r = Math.ceil(remainingMs / 1000.0).toInt()
            if (r <= 0) {
                say(finalText, flush = true)
                finished = true
                return
            }
            if (r < lastSpoken && shouldSpeak(r)) {
                val busy = tts?.isSpeaking == true
                if (r <= 5 || !busy) {
                    say(r.toString(), flush = r <= 5 || busy)
                    lastSpoken = r
                }
            }
            main.postDelayed(this, 100)
        }
    }

    private fun shouldSpeak(r: Int) = r <= 10 || (r <= 60 && r % 5 == 0) || r % 10 == 0

    private fun stopInternal() {
        finished = true
        main.removeCallbacks(tick)
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun stop() = main.post { stopInternal() }

    fun shutdown() {
        main.post {
            stopInternal()
            tts?.shutdown()
            tts = null
            ready = false
        }
    }

}
