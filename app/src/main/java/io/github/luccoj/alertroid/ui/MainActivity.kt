package io.github.luccoj.alertroid.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.widget.LinearLayout
import android.widget.TextView
import io.github.luccoj.alertroid.EventLog
import io.github.luccoj.alertroid.Places
import io.github.luccoj.alertroid.Prefs
import io.github.luccoj.alertroid.R
import io.github.luccoj.alertroid.alert.AlertCenter
import io.github.luccoj.alertroid.alert.AlertHistory
import io.github.luccoj.alertroid.service.ListenerService
import io.github.luccoj.alertroid.service.ServiceStatus
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var warnCard: LinearLayout
    private lateinit var placesText: TextView
    private lateinit var historyBox: LinearLayout
    private lateinit var logText: TextView
    private lateinit var toggle: android.widget.Button
    private var showLog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        AlertCenter.init(this)
        if (!prefs.onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        title = getString(R.string.app_name)
        val col = Ui.scrollColumn(this)

        warnCard = Ui.card(col, 0x33FF9800)

        val status = Ui.card(col)
        Ui.title(status, getString(R.string.status))
        statusText = Ui.text(status)
        toggle = Ui.button(status, "") {
            prefs.serviceEnabled = !prefs.serviceEnabled
            if (prefs.serviceEnabled) ListenerService.start(this, "user")
            else stopService(Intent(this, ListenerService::class.java))
            refresh()
        }

        val places = Ui.card(col)
        Ui.title(places, getString(R.string.subscriptions))
        placesText = Ui.text(places)
        Ui.button(places, getString(R.string.edit_places)) { startActivity(Intent(this, PlacesActivity::class.java)) }

        val test = Ui.card(col)
        Ui.title(test, getString(R.string.test_mode))
        Ui.text(test, getString(R.string.test_mode_desc), 13f)
        val delay = Ui.field(test, getString(R.string.test_delay), prefs.testDelayS.toString())
        val lead = Ui.field(test, getString(R.string.test_lead), prefs.testLeadS.toString())
        val mag = Ui.field(test, getString(R.string.test_magnitude), String.format(Locale.US, "%.1f", prefs.testMagnitude), decimal = true)
        test.addView(android.widget.CheckBox(this).apply {
            text = getString(R.string.test_quiet)
            isChecked = prefs.testQuiet
            setOnCheckedChangeListener { _, c -> prefs.testQuiet = c }
        })
        Ui.button(test, getString(R.string.test_start)) {
            prefs.testDelayS = delay.text.toString().toIntOrNull()?.coerceIn(0, 3600) ?: 10
            prefs.testLeadS = lead.text.toString().toIntOrNull()?.coerceIn(3, 120) ?: 25
            prefs.testMagnitude = mag.text.toString().replace(',', '.').toFloatOrNull()?.coerceIn(2f, 9.5f) ?: 5.8f
            ListenerService.start(this, "test", ListenerService.ACTION_TEST_SCHEDULE)
            android.widget.Toast.makeText(this, getString(R.string.test_scheduled, prefs.testDelayS), android.widget.Toast.LENGTH_LONG).show()
        }

        val hist = Ui.card(col)
        Ui.title(hist, getString(R.string.recent_alerts))
        historyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hist.addView(historyBox)

        val nav = Ui.card(col)
        Ui.button(nav, getString(R.string.settings)) { startActivity(Intent(this, SettingsActivity::class.java)) }
        Ui.button(nav, getString(R.string.permissions)) { startActivity(Intent(this, OnboardingActivity::class.java).putExtra("review", true)) }
        Ui.button(nav, getString(R.string.show_log)) { showLog = !showLog; refresh() }
        logText = Ui.text(nav, "", 11f).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }

        Ui.text(col, getString(R.string.disclaimer), 12f)
    }

    override fun onResume() {
        super.onResume()
        if (!prefs.onboardingDone) return
        if (prefs.serviceEnabled) ListenerService.refresh(this)
        tick.run()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    private fun ago(ms: Long) = if (ms <= 0) "—" else DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString()

    private fun refresh() {
        // Warnings for missing permissions
        val missing = Perms.missing(this)
        warnCard.removeAllViews()
        warnCard.visibility = if (missing.isEmpty() && prefs.effectiveLocation() != null) android.view.View.GONE else android.view.View.VISIBLE
        if (missing.isNotEmpty()) {
            Ui.title(warnCard, getString(R.string.needs_attention))
            missing.forEach { Ui.text(warnCard, "• " + getString(it.title) + if (it.required) " " + getString(R.string.required_tag) else "") }
            Ui.button(warnCard, getString(R.string.fix_permissions)) { startActivity(Intent(this, OnboardingActivity::class.java).putExtra("review", true)) }
        }
        if (prefs.effectiveLocation() == null) Ui.text(warnCard, getString(R.string.no_location_yet))

        val s = ServiceStatus
        val stateText = if (!prefs.serviceEnabled) getString(R.string.state_disabled) else when (s.state) {
            ServiceStatus.State.CONNECTED -> getString(R.string.state_connected, s.port, ago(s.connectedSinceMs))
            ServiceStatus.State.CONNECTING -> getString(R.string.state_connecting)
            ServiceStatus.State.NO_NETWORK -> getString(R.string.state_no_network)
            ServiceStatus.State.WAITING -> getString(R.string.state_waiting, ago(s.nextAttemptMs))
            ServiceStatus.State.STOPPED -> getString(R.string.state_stopped)
        }
        val lines = ArrayList<String>()
        lines += stateText
        if (s.state == ServiceStatus.State.CONNECTED) lines += getString(R.string.last_traffic, ago(s.lastRxMs))
        s.lastError?.takeIf { s.state != ServiceStatus.State.CONNECTED }?.let { lines += getString(R.string.last_error, it) }
        lines += getString(R.string.messages_count, s.messages, s.reconnects)
        if (s.androidId != 0L) lines += getString(R.string.identity, java.lang.Long.toHexString(s.androidId))
        statusText.text = lines.joinToString("\n")
        statusText.setTextColor(if (s.state == ServiceStatus.State.CONNECTED && prefs.serviceEnabled) Ui.OK else Ui.WARN)
        toggle.text = getString(if (prefs.serviceEnabled) R.string.disable_service else R.string.enable_service)

        val subs = Places.subscriptions(this)
        val loc = prefs.lastLocation()
        placesText.text = buildString {
            val me = prefs.effectiveLocation()
            if (me != null) {
                append(getString(R.string.my_location_line, String.format(Locale.US, "%.3f, %.3f", me.lat, me.lon),
                    if (prefs.manualLocationEnabled) getString(R.string.manual) else (loc?.first?.name ?: "?") + ", " + ago(loc?.second ?: 0)))
                subs.firstOrNull { it.primary }?.let { append("  → ").append(it.topic) }
                append('\n')
            }
            subs.filter { !it.primary }.forEach {
                append("• ").append(it.place.name)
                if (Places.isAutoDecoy(it.place)) append(" ").append(getString(R.string.auto_tag))
                append("  → ").append(it.topic).append('\n')
            }
        }.trim()

        historyBox.removeAllViews()
        val hist = AlertHistory.list()
        if (hist.isEmpty()) Ui.text(historyBox, getString(R.string.no_alerts_yet), 13f)
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        hist.take(15).forEach { e ->
            Ui.text(historyBox, buildString {
                append(df.format(Date(e.timeMs))).append(" · ").append(e.where).append('\n')
                append(e.kind).append(": ").append(e.summary)
                e.distanceKm?.let { append(" · ").append(Math.round(it)).append(" km") }
                e.note?.let { append(" (").append(it).append(")") }
            }, 13f)
        }

        logText.visibility = if (showLog) android.view.View.VISIBLE else android.view.View.GONE
        if (showLog) logText.text = EventLog.snapshot().takeLast(120).reversed().joinToString("\n")
    }
}
