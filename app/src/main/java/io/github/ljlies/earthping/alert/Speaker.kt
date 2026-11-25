package io.github.ljlies.earthping.alert

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.ljlies.earthping.EventLog
import java.util.Locale

/**
 * Text-to-speech warning and countdown, spoken on the alarm stream.
 *
 * - The engine is bound when the service starts, so the first words come out without delay,
 *   and re-bound when the user picks another engine in the system settings.
 * - The language is requested explicitly (language and, where possible, a matching voice),
 *   and countdown numbers are spelled out as words in that language, so engines that guess
 *   the language of short texts don't read "10" in another language.
 * - Numbers are started early by the engine's measured latency, and are queued rather than
 *   cut off, so slow engines don't skip them.
 */
class Speaker(private val ctx: Context, private val onSpeaking: (Boolean) -> Unit) {
    private var tts: TextToSpeech? = null
    private var engine: String? = null
    @Volatile private var ready = false
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<() -> Unit>()

    @Volatile private var wanted: Locale = Locale.getDefault().let { Locale.Builder().setLanguage(it.language).setRegion(it.country).build() }
    private var applied: Locale? = null

    /** Whether the engine has a voice for the last locale asked for (null = unknown yet). */
    @Volatile var localeSupported: Boolean? = null
        private set

    // Countdown state (main thread only)
    private var countdownTarget: (() -> Long?)? = null
    private var lastSpoken = Int.MAX_VALUE
    private var finished = true
    private var numberText: (Int) -> String = { it.toString() }

    // Utterance bookkeeping (main thread only)
    private val issuedAt = HashMap<String, Long>()
    private val numberIds = HashSet<String>()
    private var queued = 0
    private var speakingId: String? = null
    private var latencyMs = 400L

    private fun systemEngine(): String? = try {
        Settings.Secure.getString(ctx.contentResolver, "tts_default_synth")
    } catch (e: Exception) { null }

    /** Binds the engine now (call early); re-binds if the system default engine changed. */
    fun warmUp() = main.post { ensureEngine() }

    /** Settings screen came back to the foreground: pick up a newly chosen engine. */
    fun refreshEngine() = main.post { ensureEngine() }

    private fun ensureEngine() {
        val want = systemEngine()
        if (tts != null && (want == null || want == engine)) return
        tts?.let {
            EventLog.log("Text-to-speech engine changed ($engine → $want): reconnecting")
            try { it.shutdown() } catch (_: Exception) {}
        }
        ready = false
        applied = null
        engine = want
        val created = arrayOfNulls<TextToSpeech>(1)
        val t = TextToSpeech(ctx.applicationContext) { status -> main.post { onInit(created[0], status) } }
        created[0] = t
        tts = t
    }

    private fun onInit(t: TextToSpeech?, status: Int) {
        if (t == null || t !== tts) return
        if (status != TextToSpeech.SUCCESS) {
            EventLog.log("Text-to-speech is not available (engine ${engine ?: "default"}, status $status)")
            try { t.shutdown() } catch (_: Exception) {}
            tts = null
            engine = null
            return
        }
        engine = t.defaultEngine ?: engine
        t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        t.setSpeechRate(1.1f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = main.post { started(id) }.let { }
            override fun onDone(id: String?) = main.post { done(id) }.let { }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = main.post { done(id); EventLog.log("Text-to-speech error ($id)") }.let { }
            override fun onError(id: String?, errorCode: Int) = main.post { done(id); EventLog.log("Text-to-speech error $errorCode ($id)") }.let { }
        })
        ready = true
        applyLocale(t)
        EventLog.log("Text-to-speech ready: engine $engine, voice ${t.voice?.name ?: "?"}")
        val run = pending.toList()
        pending.clear()
        run.forEach { it() }
    }

    private fun started(id: String?) {
        id ?: return
        queued = (queued - 1).coerceAtLeast(0)
        speakingId = id
        issuedAt.remove(id)?.let { t ->
            val sample = (SystemClock.elapsedRealtime() - t).coerceIn(0, 2000)
            latencyMs = (latencyMs * 2 + sample) / 3
        }
        onSpeaking(true)
    }

    private fun done(id: String?) {
        if (id != null && issuedAt.remove(id) != null) queued = (queued - 1).coerceAtLeast(0) // never started
        numberIds.remove(id)
        if (speakingId == id) speakingId = null
        onSpeaking(false)
    }

    /** Language for what is spoken next; independent of the app's language. */
    fun setLocale(locale: Locale) { wanted = locale }

    /**
     * Chooses the voice for [wanted], respecting the user's engine settings as far as possible:
     *  1. the engine's default voice (the one chosen in its settings), if it speaks that language;
     *  2. otherwise whatever voice the engine itself picks for the language;
     *  3. only if the engine still answers in another language, a voice chosen here.
     * The log says which of the three happened.
     */
    private fun applyLocale(t: TextToSpeech) {
        if (applied == wanted) return
        var source: String? = null
        try {
            val def = t.defaultVoice
            if (def != null && def.locale.language == wanted.language && t.setVoice(def) == TextToSpeech.SUCCESS) {
                source = "the engine's default voice"
                localeSupported = true
            }
        } catch (_: Exception) {}
        if (source == null) {
            // Ask for the language with its region, then (some engines only match exactly) without.
            for (l in listOf(wanted, Locale(wanted.language)).distinct()) {
                val res = t.setLanguage(l)
                localeSupported = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                if (localeSupported == true && t.voice?.locale?.language == wanted.language) {
                    source = "the engine's choice for ${l.toLanguageTag()}"
                    break
                }
            }
            if (source == null) {
                // Some engines keep their previous voice after setLanguage: pick one ourselves.
                try {
                    t.voices?.filter { it.locale.language == wanted.language && !it.isNetworkConnectionRequired }
                        ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == wanted.country }.thenByDescending { it.quality })
                        ?.firstOrNull()?.let {
                            t.voice = it
                            localeSupported = true
                            source = "EarthPing's fallback (the engine kept another language)"
                        }
                } catch (_: Exception) {}
            }
        }
        EventLog.log("Speech language ${wanted.toLanguageTag()}: " +
            if (source != null) "voice ${t.voice?.name ?: "?"}, from $source" else "no voice installed, engine default used")
        applied = wanted
    }

    val available get() = ready

    private fun whenReady(block: () -> Unit) {
        ensureEngine()
        if (ready) block() else {
            pending += block
            if (pending.size > 20) pending.removeAt(0)
        }
    }

    private fun say(text: String, flush: Boolean, isNumber: Boolean = false) {
        val t = tts ?: return
        if (!ready) return
        applyLocale(t)
        val id = "a" + System.nanoTime()
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
        if (flush) { queued = 0; issuedAt.clear(); numberIds.clear() }
        issuedAt[id] = SystemClock.elapsedRealtime()
        if (isNumber) numberIds += id
        queued++
        t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, id)
    }

    /**
     * Speaks [announcement], then counts down to the time returned by [target] (S-wave arrival,
     * device clock ms), saying each number as [number] words it. Nothing is said at zero.
     */
    fun start(announcement: String, target: () -> Long?, number: (Int) -> String) = main.post {
        stopInternal()
        finished = false
        numberText = number
        countdownTarget = target
        lastSpoken = Int.MAX_VALUE
        whenReady {
            if (finished) return@whenReady
            EventLog.log("Speaking (${wanted.toLanguageTag()}, latency ~$latencyMs ms): $announcement")
            say(announcement, flush = true)
            main.postDelayed(tick, 100)
        }
    }

    fun announce(text: String) = main.post {
        whenReady {
            EventLog.log("Speaking: $text")
            say(text, flush = false)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            val target = countdownTarget?.invoke()
            if (target == null) { finished = true; return }
            // Speak each number early by the engine's latency, so it is heard on time.
            val remainingMs = target - System.currentTimeMillis() - latencyMs
            if (remainingMs <= 0) {
                finished = true
                return
            }
            val n = Math.ceil(remainingMs / 1000.0).toInt()
            if (n < lastSpoken && shouldSpeak(n)) {
                val behind = queued > 0
                val talkingOverNumber = speakingId != null && speakingId !in numberIds
                when {
                    // Something is still waiting to be spoken: a number now would be late.
                    behind && n > 5 -> Unit
                    // The last seconds always come out, cutting a longer sentence if needed.
                    n <= 5 && (behind || talkingOverNumber) -> say(number(n), flush = true, isNumber = true)
                    talkingOverNumber && n > 10 -> Unit
                    else -> say(number(n), flush = false, isNumber = true)
                }
                lastSpoken = n
            }
            main.postDelayed(this, 50)
        }
    }

    private fun shouldSpeak(r: Int) = r <= 10 || (r <= 60 && r % 5 == 0) || r % 10 == 0

    private fun number(n: Int): String = numberText(n)

    private fun stopInternal() {
        finished = true
        main.removeCallbacks(tick)
        pending.clear()
        queued = 0
        issuedAt.clear()
        numberIds.clear()
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun stop() = main.post { stopInternal() }

    fun shutdown() = main.post {
        stopInternal()
        tts?.shutdown()
        tts = null
        ready = false
    }
}

/**
 * Countdown numbers as words in any language (ICU's spell-out rules). Bare digits would leave the
 * engine to guess the language from nothing, and "9." reads as an ordinal in German or Turkish.
 */
object NumberWords {
    fun spell(n: Int, locale: Locale): String = try {
        // ICU puts soft hyphens inside long Italian and German words: they would only confuse engines.
        android.icu.text.MessageFormat("{0,spellout}", locale).format(arrayOf<Any>(n)).replace("\u00AD", "")
    } catch (e: Exception) {
        n.toString()
    }
}
