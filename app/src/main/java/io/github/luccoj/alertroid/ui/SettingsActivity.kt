package io.github.luccoj.alertroid.ui

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import io.github.luccoj.alertroid.Prefs
import io.github.luccoj.alertroid.R
import io.github.luccoj.alertroid.geo.LatLng
import io.github.luccoj.alertroid.service.ListenerService

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = getString(R.string.settings)
        val col = Ui.scrollColumn(this)

        val alerting = Ui.card(col)
        Ui.title(alerting, getString(R.string.alerting))
        Ui.text(alerting, getString(R.string.loud_threshold), 13f)
        val options = intArrayOf(0, 3, 4, 5)
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(
            getString(R.string.loud_any), getString(R.string.loud_mmi, "III"), getString(R.string.loud_mmi, "IV"), getString(R.string.loud_mmi, "V")))
        spinner.setSelection(options.indexOf(prefs.loudMinMmi).coerceAtLeast(0))
        alerting.addView(spinner)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { prefs.loudMinMmi = options[pos] }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        check(alerting, R.string.tts_enabled, prefs.ttsEnabled) { prefs.ttsEnabled = it }
        check(alerting, R.string.override_silence, prefs.overrideSilence) { prefs.overrideSilence = it }
        check(alerting, R.string.watched_notifs, prefs.watchedNotifications) { prefs.watchedNotifications = it }
        check(alerting, R.string.crisis_alerts, prefs.crisisAlerts) { prefs.crisisAlerts = it }

        val loc = Ui.card(col)
        Ui.title(loc, getString(R.string.location))
        Ui.text(loc, getString(R.string.manual_location_desc), 13f)
        val manualBox = CheckBox(this).apply { text = getString(R.string.manual_location); isChecked = prefs.manualLocationEnabled }
        loc.addView(manualBox)
        val ml = prefs.manualLocation ?: prefs.lastLocation()?.first?.latLng
        val lat = Ui.field(loc, getString(R.string.latitude), ml?.lat?.toString() ?: "", signed = true, decimal = true)
        val lon = Ui.field(loc, getString(R.string.longitude), ml?.lon?.toString() ?: "", signed = true, decimal = true)
        Ui.button(loc, getString(R.string.save)) {
            val la = lat.text.toString().replace(',', '.').toDoubleOrNull()
            val lo = lon.text.toString().replace(',', '.').toDoubleOrNull()
            if (manualBox.isChecked && (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0)) {
                android.widget.Toast.makeText(this, R.string.invalid_coords, android.widget.Toast.LENGTH_SHORT).show()
                return@button
            }
            if (la != null && lo != null) prefs.manualLocation = LatLng(la, lo)
            prefs.manualLocationEnabled = manualBox.isChecked
            ListenerService.refresh(this)
            android.widget.Toast.makeText(this, R.string.saved, android.widget.Toast.LENGTH_SHORT).show()
        }

        val conn = Ui.card(col)
        Ui.title(conn, getString(R.string.connection))
        val hb = Ui.field(conn, getString(R.string.heartbeat_minutes), prefs.heartbeatMinutes.toString())
        Ui.text(conn, getString(R.string.heartbeat_desc), 12f)
        Ui.button(conn, getString(R.string.save)) {
            hb.text.toString().toIntOrNull()?.let { prefs.heartbeatMinutes = it }
            android.widget.Toast.makeText(this, R.string.saved, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun check(parent: LinearLayout, label: Int, value: Boolean, set: (Boolean) -> Unit) {
        parent.addView(CheckBox(this).apply {
            text = getString(label)
            isChecked = value
            setOnCheckedChangeListener { _, c -> set(c); ListenerService.refresh(this@SettingsActivity) }
        })
    }
}
