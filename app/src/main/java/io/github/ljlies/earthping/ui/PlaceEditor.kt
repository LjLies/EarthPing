package io.github.ljlies.earthping.ui

import android.app.Activity
import android.location.Geocoder
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.ljlies.earthping.Place
import io.github.ljlies.earthping.R
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Locale

/**
 * The place editor shared by Places and "My location": search (geocoding), name, coordinates
 * and a map where a long press sets the place. [onPick] receives a place found by search or
 * long press; the caller decides what to do with it (usually [show] it).
 */
class PlaceEditor(
    private val activity: Activity,
    private val root: View,
    private val defaultName: () -> String,
    private val onPick: (Place) -> Unit,
) {
    val map: MapView = root.findViewById(R.id.map)
    private val markers = ArrayList<Marker>()

    private fun field(id: Int) = root.findViewById<TextInputEditText>(id)

    init {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.minZoomLevel = 2.0
        map.controller.setZoom(2.5)
        // Let the map take gestures instead of the scroll view
        map.setOnTouchListener { v, e ->
            v.parent.requestDisallowInterceptTouchEvent(e.action != MotionEvent.ACTION_UP && e.action != MotionEvent.ACTION_CANCEL)
            false
        }
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                val name = field(R.id.name).text?.toString()?.trim().orEmpty()
                onPick(Place(name.ifEmpty { defaultName() }, p.latitude, p.longitude))
                return true
            }
        }))
        val search = field(R.id.search)
        root.findViewById<TextInputLayout>(R.id.search_layout).setEndIconOnClickListener { geocode(search.text.toString()) }
        search.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { geocode(search.text.toString()); true } else false
        }
    }

    fun show(p: Place?, hint: String) {
        // The search box belongs to the place being edited: start each one empty.
        field(R.id.search).setText("")
        field(R.id.name).setText(p?.name ?: "")
        field(R.id.lat).setText(p?.let { String.format(Locale.US, "%.4f", it.lat) } ?: "")
        field(R.id.lon).setText(p?.let { String.format(Locale.US, "%.4f", it.lon) } ?: "")
        root.findViewById<TextView>(R.id.slot_hint).text = hint
        p?.let { map.controller.animateTo(GeoPoint(it.lat, it.lon)) }
    }

    sealed class Read {
        object Empty : Read()
        object Invalid : Read()
        data class Ok(val place: Place) : Read()
    }

    /** The place in the fields: empty when both coordinates are blank. */
    fun read(): Read {
        val name = field(R.id.name).text?.toString()?.trim().orEmpty()
        val latText = field(R.id.lat).text?.toString()?.replace(',', '.')?.trim().orEmpty()
        val lonText = field(R.id.lon).text?.toString()?.replace(',', '.')?.trim().orEmpty()
        if (latText.isEmpty() && lonText.isEmpty()) return Read.Empty
        val la = latText.toDoubleOrNull()
        val lo = lonText.toDoubleOrNull()
        if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) return Read.Invalid
        return Read.Ok(Place(name.ifEmpty { defaultName() }, la, lo))
    }

    fun setMarkers(places: List<Place>) {
        markers.forEach { map.overlays.remove(it) }
        markers.clear()
        places.forEach { p ->
            markers += Marker(map).apply {
                position = GeoPoint(p.lat, p.lon)
                title = p.name
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(activity, R.drawable.ic_marker_place)
                map.overlays.add(this)
            }
        }
        map.invalidate()
    }

    @Suppress("DEPRECATION")
    private fun geocode(q: String) {
        if (q.isBlank()) return
        if (!Geocoder.isPresent()) {
            Toast.makeText(activity, R.string.no_geocoder, Toast.LENGTH_LONG).show()
            return
        }
        val ctx = activity.applicationContext
        Thread {
            val res = try { Geocoder(ctx).getFromLocationName(q, 1) } catch (e: Exception) { null }
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                val a = res?.firstOrNull()
                if (a == null) {
                    Toast.makeText(activity, R.string.place_not_found, Toast.LENGTH_SHORT).show()
                } else {
                    onPick(Place(q.trim().replaceFirstChar { it.titlecase(Locale.getDefault()) }, a.latitude, a.longitude))
                    map.controller.animateTo(GeoPoint(a.latitude, a.longitude), 8.0, 800L)
                }
            }
        }.start()
    }

    fun onResume() = map.onResume()
    fun onPause() = map.onPause()
}
