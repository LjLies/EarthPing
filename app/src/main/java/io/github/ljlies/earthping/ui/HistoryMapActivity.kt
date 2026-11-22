package io.github.ljlies.earthping.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.ljlies.earthping.Places
import io.github.ljlies.earthping.Prefs
import io.github.ljlies.earthping.R
import io.github.ljlies.earthping.alert.AlertHistory
import io.github.ljlies.earthping.alert.HistoryEntry
import io.github.ljlies.earthping.geo.Geo
import io.github.ljlies.earthping.geo.LatLng
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.Date
import java.util.Locale

/** Recent alerts on a map, each with the latest parameters Google sent for it. */
class HistoryMapActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_EVENT = "event"
    }

    private lateinit var map: MapView
    private lateinit var entries: List<HistoryEntry>
    private var selected = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history_map)
        setTitle(R.string.history_map_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        AlertHistory.init(this)
        entries = AlertHistory.list().filter { it.position != null }
        map = findViewById<MapView>(R.id.map).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 2.0
        }
        // The list is newest first: "older" moves down it.
        findViewById<Button>(R.id.previous).setOnClickListener { if (selected < entries.size - 1) select(selected + 1, move = true) }
        findViewById<Button>(R.id.next).setOnClickListener { if (selected > 0) select(selected - 1, move = true) }

        val wanted = intent.getStringExtra(EXTRA_EVENT)
        val start = entries.indexOfFirst { it.event == wanted }
        if (start >= 0) {
            select(start, move = false)
            map.post { map.controller.setZoom(7.0); map.controller.setCenter(gp(entries[start].position!!)) }
        } else {
            select(if (entries.isEmpty()) -1 else 0, move = false)
            map.post { fitAll() }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }

    private fun gp(l: LatLng) = GeoPoint(l.lat, l.lon)

    private fun fitAll() {
        val pts = entries.mapNotNull { it.position } + listOfNotNull(Prefs(this).effectiveLocation())
        when {
            pts.size >= 2 -> map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts.map { gp(it) }).increaseByScale(1.3f), false, 48)
            pts.size == 1 -> { map.controller.setZoom(6.0); map.controller.setCenter(gp(pts[0])) }
            else -> map.controller.setZoom(3.0)
        }
    }

    private fun select(i: Int, move: Boolean) {
        selected = i
        findViewById<Button>(R.id.previous).isEnabled = i in 0 until entries.size - 1
        findViewById<Button>(R.id.next).isEnabled = i > 0
        val title = findViewById<TextView>(R.id.detail_title)
        val body = findViewById<TextView>(R.id.detail_body)
        if (i < 0) {
            title.setText(R.string.history_map_title)
            body.setText(R.string.history_map_empty)
        } else {
            val e = entries[i]
            title.text = title(e)
            body.text = details(e)
            if (move) map.controller.animateTo(gp(e.position!!))
        }
        drawMarkers()
    }

    private fun title(e: HistoryEntry): String = if (e.isEarthquake) {
        e.magnitude?.let { getString(R.string.history_quake_title, String.format(Locale.getDefault(), "%.1f", it)) } ?: getString(R.string.kind_earthquake)
    } else {
        StatusFragment.kindLabel(this, e.kind)
    }

    private fun details(e: HistoryEntry): String {
        val df = DateFormat.getMediumDateFormat(this)
        val tf = DateFormat.getTimeFormat(this)
        fun at(ms: Long) = "${df.format(Date(ms))} ${tf.format(Date(ms))}"
        val where = if (e.where == "my location") getString(R.string.my_location) else e.where
        val lines = ArrayList<String>()
        if (!e.isEarthquake) lines += e.summary
        e.originMs?.let { lines += getString(R.string.history_occurred, at(it)) }
        lines += getString(R.string.history_received, at(e.timeMs))
        e.position?.let { p ->
            val coords = String.format(Locale.US, "%.2f, %.2f", p.lat, p.lon)
            lines += if (e.isEarthquake) {
                e.depthKm?.let { getString(R.string.history_epicentre_depth, coords, Math.round(it).toString()) }
                    ?: getString(R.string.history_epicentre, coords)
            } else getString(R.string.history_area_centre, coords)
        }
        if (e.where != "?") {
            val parts = listOfNotNull(
                e.distanceKm?.let {
                    if (e.where == "my location") getString(R.string.history_km_from_you, Math.round(it).toString())
                    else getString(R.string.km_from, Math.round(it).toString(), where)
                },
                e.mmi?.let { getString(R.string.est_intensity, Geo.roman(it)) })
            if (parts.isNotEmpty()) lines += parts.joinToString(" · ")
        }
        if (e.revisions > 0 && e.updatedMs != null) {
            lines += resources.getQuantityString(R.plurals.history_updated, e.revisions, e.revisions, tf.format(Date(e.updatedMs)))
        }
        e.note?.let { lines += StatusFragment.noteLabel(this, it) }
        return lines.joinToString("\n")
    }

    /** Fill colour from the estimated intensity, as on the warning screen. */
    private fun color(e: HistoryEntry): Int = when {
        !e.isEarthquake -> 0xFF7E57C2.toInt()
        e.mmi == null -> 0xFF9E9E9E.toInt()
        Math.round(e.mmi) >= 5 -> 0xFFD32F2F.toInt()
        Math.round(e.mmi) >= 4 -> 0xFFFF9800.toInt()
        Math.round(e.mmi) >= 3 -> 0xFFFDD835.toInt()
        else -> 0xFF90A4AE.toInt()
    }

    private fun drawMarkers() {
        val density = resources.displayMetrics.density
        map.overlays.clear()
        Prefs(this).effectiveLocation()?.let { you ->
            map.overlays.add(Marker(map).apply {
                position = gp(you); title = getString(R.string.you)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@HistoryMapActivity, R.drawable.ic_you)
            })
        }
        Places.subscriptions(this).filter { !it.primary && !Places.isAutoDecoy(it.place) }.map { it.place }.forEach { p ->
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(p.lat, p.lon); title = p.name
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@HistoryMapActivity, R.drawable.ic_marker_place)
            })
        }
        // Oldest first so newer alerts are drawn on top; the selected one last of all.
        val order = entries.indices.reversed().filter { it != selected } + listOfNotNull(selected.takeIf { it >= 0 })
        for (i in order) {
            val e = entries[i]
            val isSel = i == selected
            val sizeDp = if (e.isEarthquake) (10 + 5 * ((e.magnitude ?: 4.0) - 3.0)).coerceIn(10.0, 36.0) else 16.0
            val px = (sizeDp * density).toInt()
            val dot = GradientDrawable().apply {
                shape = if (e.isEarthquake) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                setColor(color(e) and 0x00FFFFFF or (if (isSel) 0xF0000000 else 0xB0000000).toInt())
                setStroke(((if (isSel) 3f else 1.5f) * density).toInt(), if (isSel) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                setSize(px, px)
            }
            map.overlays.add(Marker(map).apply {
                position = gp(e.position!!)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = dot
                setOnMarkerClickListener { _, _ -> select(i, move = false); true }
            })
        }
        map.invalidate()
    }
}
