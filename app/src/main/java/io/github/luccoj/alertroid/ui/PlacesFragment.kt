package io.github.luccoj.alertroid.ui

import android.location.Geocoder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.luccoj.alertroid.Place
import io.github.luccoj.alertroid.Places
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

/** Up to three extra places to be told about; they double as the protocol's privacy decoys. */
class PlacesFragment : Fragment() {
    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var map: MapView
    private val slotIds = intArrayOf(R.id.slot1, R.id.slot2, R.id.slot3)
    /** Places being edited (null = empty slot, filled by an automatic decoy). */
    private val places = arrayOfNulls<Place>(3)
    private var selected = 0
    private var binding = false
    private val markers = arrayOfNulls<Marker>(3)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_places, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        root = view
        prefs = Prefs(requireContext())
        prefs.userDecoys.forEachIndexed { i, p -> if (i < 3) places[i] = p }

        map = view.findViewById<MapView>(R.id.map).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 2.0
            controller.setZoom(2.5)
            // Let the map take gestures instead of the scroll view
            setOnTouchListener { v, e ->
                v.parent.requestDisallowInterceptTouchEvent(e.action != MotionEvent.ACTION_UP && e.action != MotionEvent.ACTION_CANCEL)
                false
            }
        }
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                val name = field(R.id.name).text?.toString()?.trim().orEmpty()
                setSlot(name.ifEmpty { getString(R.string.place_n, selected + 1) }, p.latitude, p.longitude)
                return true
            }
        }))

        view.findViewById<MaterialButtonToggleGroup>(R.id.slots).apply {
            check(slotIds[0])
            addOnButtonCheckedListener { _, id, checked ->
                if (!checked || binding) return@addOnButtonCheckedListener
                readFields()
                selected = slotIds.indexOf(id)
                showSlot()
            }
        }
        view.findViewById<Button>(R.id.clear).setOnClickListener {
            places[selected] = null
            showSlot()
            save(quiet = true)
        }
        view.findViewById<Button>(R.id.save).setOnClickListener {
            if (readFields()) save(quiet = false)
        }
        val search = field(R.id.search)
        view.findViewById<TextInputLayout>(R.id.search_layout).setEndIconOnClickListener { geocode(search.text.toString()) }
        search.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { geocode(search.text.toString()); true } else false
        }
        showSlot()
    }

    private fun field(id: Int) = root.findViewById<TextInputEditText>(id)

    private fun slotLabel(i: Int) = places[i]?.name ?: getString(R.string.place_n, i + 1)

    private fun showSlot() {
        val p = places[selected]
        field(R.id.name).setText(p?.name ?: "")
        field(R.id.lat).setText(p?.let { String.format(Locale.US, "%.4f", it.lat) } ?: "")
        field(R.id.lon).setText(p?.let { String.format(Locale.US, "%.4f", it.lon) } ?: "")
        root.findViewById<TextView>(R.id.slot_hint).text =
            if (p == null) getString(R.string.slot_empty_hint) else getString(R.string.slot_set_hint)
        slotIds.forEachIndexed { i, id -> root.findViewById<Button>(id).text = slotLabel(i) }
        updateMarkers()
        p?.let { map.controller.animateTo(GeoPoint(it.lat, it.lon)) }
    }

    /** Validates the editor into the selected slot. Blank coordinates leave the slot empty. */
    private fun readFields(): Boolean {
        val name = field(R.id.name).text?.toString()?.trim().orEmpty()
        val latText = field(R.id.lat).text?.toString()?.replace(',', '.')?.trim().orEmpty()
        val lonText = field(R.id.lon).text?.toString()?.replace(',', '.')?.trim().orEmpty()
        if (latText.isEmpty() && lonText.isEmpty()) {
            places[selected] = null
            return true
        }
        val la = latText.toDoubleOrNull()
        val lo = lonText.toDoubleOrNull()
        if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) {
            Toast.makeText(requireContext(), getString(R.string.invalid_place, selected + 1), Toast.LENGTH_LONG).show()
            return false
        }
        places[selected] = Place(name.ifEmpty { getString(R.string.place_n, selected + 1) }, la, lo)
        return true
    }

    private fun setSlot(name: String, lat: Double, lon: Double) {
        places[selected] = Place(name, lat, lon)
        showSlot()
    }

    private fun updateMarkers() {
        for (i in 0 until 3) {
            markers[i]?.let { map.overlays.remove(it) }
            markers[i] = null
            val p = places[i] ?: continue
            markers[i] = Marker(map).apply {
                position = GeoPoint(p.lat, p.lon)
                title = p.name
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_place)
                map.overlays.add(this)
            }
        }
        map.invalidate()
    }

    @Suppress("DEPRECATION")
    private fun geocode(q: String) {
        if (q.isBlank()) return
        if (!Geocoder.isPresent()) {
            Toast.makeText(requireContext(), R.string.no_geocoder, Toast.LENGTH_LONG).show()
            return
        }
        val ctx = requireContext().applicationContext
        Thread {
            val res = try { Geocoder(ctx).getFromLocationName(q, 1) } catch (e: Exception) { null }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val a = res?.firstOrNull()
                if (a == null) {
                    Toast.makeText(requireContext(), R.string.place_not_found, Toast.LENGTH_SHORT).show()
                } else {
                    setSlot(q.trim().replaceFirstChar { it.titlecase(Locale.getDefault()) }, a.latitude, a.longitude)
                    map.controller.animateTo(GeoPoint(a.latitude, a.longitude), 8.0, 800L)
                }
            }
        }.start()
    }

    private fun save(quiet: Boolean) {
        prefs.userDecoys = places.filterNotNull()
        // Keep slot positions stable after compaction
        val saved = prefs.userDecoys
        for (i in 0 until 3) places[i] = saved.getOrNull(i)
        selected = selected.coerceAtMost((saved.size).coerceAtMost(2))
        binding = true
        root.findViewById<MaterialButtonToggleGroup>(R.id.slots).check(slotIds[selected])
        binding = false
        showSlot()
        ListenerService.refresh(requireContext())
        if (!quiet) Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_SHORT).show()
        Places.subscriptions(requireContext())
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}
