package io.github.ljlies.earthping.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.ljlies.earthping.EventLog
import io.github.ljlies.earthping.R
import io.github.ljlies.earthping.service.ServiceStatus

/** Connection details and the diagnostic log (kept on the device only). */
class LogActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        setTitle(R.string.diagnostics)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<TextView>(R.id.copy).setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(getString(R.string.app_name), details() + "\n\n" + technical() + "\n\n" + EventLog.snapshot().joinToString("\n")))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.clear).setOnClickListener { EventLog.clear(); refresh() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onResume() { super.onResume(); ticker.run() }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }

    /** Diagnostics are always in English, whatever the app's language, to be useful in reports. */
    private val en by lazy {
        createConfigurationContext(android.content.res.Configuration(resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) })
    }

    private fun ago(ms: Long): String {
        if (ms <= 0) return "—"
        val s = (System.currentTimeMillis() - ms) / 1000
        return when {
            s < 0 -> "in ${-s} s"
            s < 90 -> "$s s ago"
            s < 5400 -> "${s / 60} min ago"
            else -> "${s / 3600} h ago"
        }
    }

    private fun details(): String {
        val s = ServiceStatus
        return listOf(
            en.getString(R.string.diag_state, s.state.name.lowercase()),
            en.getString(R.string.diag_since, ago(s.connectedSinceMs)),
            en.getString(R.string.last_traffic, ago(s.lastRxMs)),
            en.getString(R.string.messages_count, s.messages, s.reconnects),
            en.getString(R.string.identity, if (s.androidId != 0L) java.lang.Long.toHexString(s.androidId) else "—"),
            en.getString(R.string.diag_topics, s.topics.joinToString(", ").ifEmpty { "—" }),
            en.getString(R.string.diag_location, s.locationInfo ?: "—"),
        ).joinToString("\n") + (s.lastError?.let { "\n" + en.getString(R.string.last_error, it) } ?: "")
    }

    /** Device, settings and permissions: what is needed to understand a report. */
    private fun technical(): String {
        val p = io.github.ljlies.earthping.Prefs(this)
        val am = getSystemService(android.media.AudioManager::class.java)
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val engine = try { android.provider.Settings.Secure.getString(contentResolver, "tts_default_synth") } catch (e: Exception) { null }
        return listOf(
            "App ${io.github.ljlies.earthping.BuildConfig.VERSION_NAME} (${io.github.ljlies.earthping.BuildConfig.VERSION_CODE})",
            "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, ${android.os.Build.DISPLAY}",
            "Permissions: " + Perms.applicable().joinToString(", ") { "${it.key}=${if (it.granted(this)) "yes" else "NO"}" },
            "Settings: alerts=${p.serviceEnabled}, loud>=${p.loudMinMmi}, siren>=${p.sirenMinMmi}, notice=${p.noticeSound}, tone=${p.alarmTone}, volume=${p.alarmSound}, override=${p.overrideSilence}, " +
                "tts=${p.ttsEnabled} (${p.ttsLanguage.ifEmpty { "app" }}, engine $engine), heartbeat=${p.heartbeatMinutes} min, manual location=${p.manualLocationEnabled}",
            "Audio: ringer=${am.ringerMode}, alarm volume=${am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)}/${am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)}, DND filter=${nm.currentInterruptionFilter}",
            "Clock offset to Google: ${p.clockOffsetMs} ms",
        ).joinToString("\n")
    }

    private fun refresh() {
        findViewById<TextView>(R.id.details).text = details() + "\n\n" + technical()
        findViewById<TextView>(R.id.log).text = EventLog.snapshot().reversed().joinToString("\n").ifEmpty { "—" }
    }
}
