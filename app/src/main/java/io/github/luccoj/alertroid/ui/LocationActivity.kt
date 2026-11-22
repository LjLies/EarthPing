package io.github.luccoj.alertroid.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.luccoj.alertroid.Place
import io.github.luccoj.alertroid.Prefs
import io.github.luccoj.alertroid.R
import io.github.luccoj.alertroid.geo.LatLng
import io.github.luccoj.alertroid.service.ListenerService

/** "My location" set by hand, with the same search / name / map editor as Places. */
class LocationActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var editor: PlaceEditor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location)
        setTitle(R.string.location)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        editor = PlaceEditor(this, findViewById(android.R.id.content), { getString(R.string.my_location) }) { p ->
            editor.show(p, getString(R.string.location_hint))
            editor.setMarkers(listOf(p))
        }
        val current = prefs.manualLocation?.let { Place(prefs.manualLocationName ?: getString(R.string.my_location), it.lat, it.lon) }
            ?: prefs.lastLocation()?.first?.let { Place(getString(R.string.my_location), it.lat, it.lon) }
        editor.show(current, getString(R.string.location_hint))
        current?.let { editor.setMarkers(listOf(it)); editor.map.controller.setZoom(8.0) }
        findViewById<TextView>(R.id.location_state).setText(
            if (prefs.manualLocationEnabled) R.string.location_state_manual else R.string.location_state_auto)

        // "Clear" here means: go back to the detected location.
        findViewById<Button>(R.id.clear).apply {
            setText(R.string.use_detected_location)
            setOnClickListener {
                prefs.manualLocationEnabled = false
                ListenerService.refresh(this@LocationActivity)
                Toast.makeText(this@LocationActivity, R.string.location_auto_summary, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        findViewById<Button>(R.id.save).setOnClickListener {
            when (val r = editor.read()) {
                is PlaceEditor.Read.Ok -> {
                    prefs.manualLocation = LatLng(r.place.lat, r.place.lon)
                    prefs.manualLocationName = r.place.name
                    prefs.manualLocationEnabled = true
                    ListenerService.refresh(this)
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                    finish()
                }
                else -> Toast.makeText(this, R.string.invalid_coords, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() { super.onResume(); editor.onResume() }
    override fun onPause() { super.onPause(); editor.onPause() }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
