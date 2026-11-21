package io.github.luccoj.alertroid.ui

import android.app.Activity
import android.location.Geocoder
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import io.github.luccoj.alertroid.Place
import io.github.luccoj.alertroid.Prefs
import io.github.luccoj.alertroid.R
import io.github.luccoj.alertroid.service.ListenerService
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Locale

/** Up to three extra places to be told about (they double as the protocol's privacy decoys). */
class PlacesActivity : Activity() {
    private lateinit var prefs: Prefs
    private val names = ArrayList<EditText>()
    private val lats = ArrayList<EditText>()
    private val lons = ArrayList<EditText>()
    private lateinit var slots: RadioGroup
    private lateinit var map: MapView
    private val markers = arrayOfNulls<Marker>(3)
    private val slotIds = IntArray(3) { android.view.View.generateViewId() }
    private fun selectedSlot() = slotIds.indexOf(slots.checkedRadioButtonId)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = getString(R.string.edit_places)
        val col = Ui.scrollColumn(this)
        Ui.text(col, getString(R.string.places_desc), 13f)

        val current = prefs.userDecoys
        slots = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for (i in 0 until 3) {
            slots.addView(RadioButton(this).apply { id = slotIds[i]; text = getString(R.string.place_n, i + 1) })
        }
        slots.check(slotIds[0])
        for (i in 0 until 3) {
            val card = Ui.card(col)
            Ui.title(card, getString(R.string.place_n, i + 1), 15f)
            val p = current.getOrNull(i)
            names += Ui.field(card, getString(R.string.place_name), p?.name ?: "", numeric = false)
            val row = Ui.row(card)
            val la = Ui.field(LinearLayout(this).also { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f)); (it as LinearLayout).orientation = LinearLayout.VERTICAL },
                getString(R.string.latitude), p?.lat?.toString() ?: "", signed = true, decimal = true)
            val lo = Ui.field(LinearLayout(this).also { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f)); (it as LinearLayout).orientation = LinearLayout.VERTICAL },
                getString(R.string.longitude), p?.lon?.toString() ?: "", signed = true, decimal = true)
            lats += la; lons += lo
            Ui.button(card, getString(R.string.clear)) { names[i].setText(""); lats[i].setText(""); lons[i].setText(""); updateMarkers() }
        }

        val search = Ui.card(col)
        Ui.title(search, getString(R.string.pick_place), 15f)
        search.addView(slots)
        val query = Ui.field(search, getString(R.string.search_place), "", numeric = false)
        Ui.button(search, getString(R.string.search)) { geocode(query.text.toString()) }
        Ui.text(search, getString(R.string.long_press_hint), 12f)

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(2.5)
            minZoomLevel = 2.0
        }
        search.addView(map, LinearLayout.LayoutParams(-1, Ui.dp(this, 320)))
        // Let the map take touch gestures instead of the scroll view.
        map.setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                setSlot(selectedSlot(), null, p.latitude, p.longitude)
                return true
            }
        }))
        Ui.text(col, getString(R.string.osm_attribution), 11f)
        Ui.button(col, getString(R.string.save)) { save() }
        updateMarkers()
    }

    private fun setSlot(i: Int, name: String?, lat: Double, lon: Double) {
        if (i !in 0..2) return
        if (name != null) names[i].setText(name)
        else if (names[i].text.isBlank()) names[i].setText(getString(R.string.place_n, i + 1))
        lats[i].setText(String.format(Locale.US, "%.4f", lat))
        lons[i].setText(String.format(Locale.US, "%.4f", lon))
        updateMarkers()
    }

    private fun parse(i: Int): Place? {
        val la = lats[i].text.toString().replace(',', '.').toDoubleOrNull() ?: return null
        val lo = lons[i].text.toString().replace(',', '.').toDoubleOrNull() ?: return null
        if (la !in -90.0..90.0 || lo !in -180.0..180.0) return null
        return Place(names[i].text.toString().ifBlank { getString(R.string.place_n, i + 1) }, la, lo)
    }

    private fun updateMarkers() {
        for (i in 0 until 3) {
            markers[i]?.let { map.overlays.remove(it) }
            markers[i] = null
            val p = parse(i) ?: continue
            markers[i] = Marker(map).apply {
                position = GeoPoint(p.lat, p.lon)
                title = p.name
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(this)
            }
        }
        map.invalidate()
    }

    @Suppress("DEPRECATION")
    private fun geocode(q: String) {
        if (q.isBlank()) return
        if (!Geocoder.isPresent()) {
            Toast.makeText(this, R.string.no_geocoder, Toast.LENGTH_LONG).show()
            return
        }
        val slot = selectedSlot()
        Thread {
            val res = try { Geocoder(this).getFromLocationName(q, 1) } catch (e: Exception) { null }
            runOnUiThread {
                val a = res?.firstOrNull()
                if (a == null) {
                    Toast.makeText(this, R.string.place_not_found, Toast.LENGTH_SHORT).show()
                } else {
                    setSlot(slot, q.trim(), a.latitude, a.longitude)
                    map.controller.animateTo(GeoPoint(a.latitude, a.longitude), 8.0, 800L)
                }
            }
        }.start()
    }

    private fun save() {
        val list = ArrayList<Place>()
        for (i in 0 until 3) {
            val blank = lats[i].text.isBlank() && lons[i].text.isBlank()
            if (blank) continue
            val p = parse(i)
            if (p == null) {
                Toast.makeText(this, getString(R.string.invalid_place, i + 1), Toast.LENGTH_LONG).show()
                return
            }
            list += p
        }
        prefs.userDecoys = list
        ListenerService.refresh(this)
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}
