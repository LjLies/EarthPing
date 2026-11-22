package io.github.luccoj.alertroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.luccoj.alertroid.EventLog
import io.github.luccoj.alertroid.R
import io.github.luccoj.alertroid.service.ServiceStatus

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
                ClipData.newPlainText(getString(R.string.app_name), details() + "\n\n" + EventLog.snapshot().joinToString("\n")))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onResume() { super.onResume(); ticker.run() }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }

    private fun ago(ms: Long) = if (ms <= 0) "—" else DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)

    private fun details(): String {
        val s = ServiceStatus
        return listOf(
            getString(R.string.diag_state, s.state.name.lowercase()),
            getString(R.string.diag_since, ago(s.connectedSinceMs)),
            getString(R.string.last_traffic, ago(s.lastRxMs)),
            getString(R.string.messages_count, s.messages, s.reconnects),
            getString(R.string.identity, if (s.androidId != 0L) java.lang.Long.toHexString(s.androidId) else "—"),
            getString(R.string.diag_topics, s.topics.joinToString(", ").ifEmpty { "—" }),
            getString(R.string.diag_location, s.locationInfo ?: "—"),
        ).joinToString("\n") + (s.lastError?.let { "\n" + getString(R.string.last_error, it) } ?: "")
    }

    private fun refresh() {
        findViewById<TextView>(R.id.details).text = details()
        findViewById<TextView>(R.id.log).text = EventLog.snapshot().reversed().joinToString("\n").ifEmpty { "—" }
    }
}
