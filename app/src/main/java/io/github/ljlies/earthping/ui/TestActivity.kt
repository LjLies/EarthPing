package io.github.ljlies.earthping.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import io.github.ljlies.earthping.Prefs
import io.github.ljlies.earthping.R
import io.github.ljlies.earthping.alert.AlertCenter
import io.github.ljlies.earthping.service.ListenerService
import java.util.Locale

/** Tests: a full staged alert, the alarm tone and the speech. Work even with alerts turned off. */
class TestActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SECTION = "section"
    }

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tests)
        setTitle(R.string.tests_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        AlertCenter.init(this)

        val delay = findViewById<Slider>(R.id.delay)
        val lead = findViewById<Slider>(R.id.lead)
        val mag = findViewById<Slider>(R.id.magnitude)
        delay.value = (prefs.testDelayS.coerceIn(0, 120) / 5 * 5).toFloat()
        lead.value = (prefs.testLeadS.coerceIn(5, 90) / 5 * 5).toFloat()
        mag.value = Math.round(prefs.testMagnitude.coerceIn(3f, 8.5f) * 10) / 10f
        val update = {
            findViewById<TextView>(R.id.delay_value).text = getString(R.string.seconds_short, delay.value.toInt())
            findViewById<TextView>(R.id.lead_value).text = getString(R.string.seconds_short, lead.value.toInt())
            findViewById<TextView>(R.id.magnitude_value).text = String.format(Locale.getDefault(), "M%.1f", mag.value)
        }
        update()
        delay.addOnChangeListener { _, v, _ -> prefs.testDelayS = v.toInt(); update() }
        lead.addOnChangeListener { _, v, _ -> prefs.testLeadS = v.toInt(); update() }
        mag.addOnChangeListener { _, v, _ -> prefs.testMagnitude = v; update() }
        findViewById<MaterialSwitch>(R.id.quiet).apply {
            isChecked = prefs.testQuiet
            setOnCheckedChangeListener { _, c -> prefs.testQuiet = c }
        }
        findViewById<Button>(R.id.test_start).setOnClickListener {
            ListenerService.scheduleTest(this)
            Toast.makeText(this, getString(R.string.test_scheduled, prefs.testDelayS), Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.play_tone).setOnClickListener {
            val playing = AlertCenter.player.preview(prefs.alarmSound, prefs.alarmTone)
            (it as Button).setText(if (playing) R.string.test_tone_stop else R.string.test_tone_play)
            if (playing) it.postDelayed({ it.setText(R.string.test_tone_play) }, 4200)
        }
        findViewById<Button>(R.id.play_update).setOnClickListener { AlertCenter.player.playUpdateChime() }
        findViewById<Button>(R.id.play_notice).setOnClickListener { AlertCenter.playNoticeChime() }
        fun openSettings(key: String) = startActivity(android.content.Intent(this, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SETTINGS_KEY, key))
        findViewById<Button>(R.id.alarm_settings).setOnClickListener { openSettings("loud_min_mmi") }
        findViewById<Button>(R.id.speech_settings).setOnClickListener { openSettings("tts") }
        // Opened from a "Test" link in the settings: show that section.
        intent.getStringExtra(EXTRA_SECTION)?.let { section ->
            val target = when (section) { "speech" -> R.id.speech_settings; else -> 0 }
            if (target != 0) findViewById<android.view.View>(target).post {
                (findViewById<android.view.View>(R.id.column).parent as androidx.core.widget.NestedScrollView)
                    .smoothScrollTo(0, findViewById<android.view.View>(target).top)
            }
        }
        findViewById<Button>(R.id.speak).setOnClickListener {
            when (AlertCenter.speakSample()) {
                null -> Toast.makeText(this, R.string.tts_unavailable, Toast.LENGTH_LONG).show()
                false -> Toast.makeText(this, getString(R.string.tts_voice_missing,
                    AlertCenter.speechLocale().getDisplayLanguage(Locale.getDefault())), Toast.LENGTH_LONG).show()
                true -> Unit
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AlertCenter.speaker.refreshEngine()
        val tones = resources.getStringArray(R.array.tone_entries)
        val sounds = resources.getStringArray(R.array.sound_entries)
        findViewById<TextView>(R.id.tone_desc).text = getString(R.string.test_tone_desc,
            tones.getOrElse(prefs.alarmTone) { tones[0] }, sounds.getOrElse(prefs.alarmSound) { sounds[0] })
        val lang = AlertCenter.speechLocale().let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } }
        findViewById<TextView>(R.id.speech_desc).text =
            if (prefs.ttsEnabled) getString(R.string.tts_try_desc, lang) else getString(R.string.tts_off_note)
    }

    override fun onPause() {
        AlertCenter.player.let { if (it.isPlaying && AlertCenter.active == null) it.stop() }
        super.onPause()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
