package io.github.ljlies.earthping.ui

import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import io.github.ljlies.earthping.R
import io.github.ljlies.earthping.alert.AlertCenter
import io.github.ljlies.earthping.alert.LocalQuake
import io.github.ljlies.earthping.geo.Geo
import io.github.ljlies.earthping.geo.LatLng
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * The full-screen warning: seconds until the S-wave, and a map with the epicentre, the user,
 * Google's intensity contours, and the expanding P- and S-wave fronts.
 */
class AlertActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var header: TextView
    private lateinit var countdown: TextView
    private lateinit var info: TextView
    private lateinit var countdownLabel: TextView
    private lateinit var map: MapView
    private lateinit var stopBtn: Button
    private var pWave: Polygon? = null
    private var sWave: Polygon? = null
    private var shown: LocalQuake? = null
    private var shownAlertId: String? = null
    private var shownRevision = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertCenter.init(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_alert)
        header = findViewById(R.id.header)
        countdown = findViewById(R.id.countdown)
        countdownLabel = findViewById(R.id.countdown_label)
        info = findViewById(R.id.info)
        map = findViewById<MapView>(R.id.map).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
        }
        stopBtn = findViewById<Button>(R.id.stop).apply { setOnClickListener { AlertCenter.silence() } }
        findViewById<Button>(R.id.close).setOnClickListener { AlertCenter.silence(); finish() }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        AlertCenter.onAlertScreen(true)
        frame.run()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        AlertCenter.onAlertScreen(false)
        main.removeCallbacks(frame)
    }

    private val frame = object : Runnable {
        override fun run() {
            update()
            main.postDelayed(this, 100)
        }
    }

    private fun gp(l: LatLng) = GeoPoint(l.lat, l.lon)

    /** A revision changed this text: show it in a highlight colour for two seconds. */
    private fun flashIfChanged(view: android.widget.TextView, text: String, normal: Int) {
        val old = view.text?.toString()
        if (old == text) return
        view.text = text
        if (old.isNullOrEmpty()) { view.setTextColor(getColor(normal)); return }
        view.setTextColor(getColor(R.color.alert_changed))
        view.removeCallbacks(view.tag as? Runnable)
        val reset = Runnable { view.setTextColor(getColor(normal)) }
        view.tag = reset
        view.postDelayed(reset, 2000)
    }

    private fun rebuildMap(q: LocalQuake, refit: Boolean = true) {
        map.overlays.clear()
        pWave = null; sWave = null
        val colors = mapOf(5 to 0x55D32F2F, 4 to 0x55FF9800, 3 to 0x55FFEB3B)
        for (c in q.alert.contours.sortedBy { it.mmi }) {
            val fill = colors[c.mmi] ?: 0x55FFEB3B
            (c.rings + c.circles.map { Geo.circle(it.center, it.radiusM / 1000.0) }).forEach { ring ->
                map.overlays.add(Polygon(map).apply {
                    points = ring.map { gp(it) } + gp(ring.first())
                    fillPaint.color = fill
                    outlinePaint.color = fill or 0xFF000000.toInt()
                    outlinePaint.strokeWidth = 2f
                    setOnClickListener { _, _, _ -> true }
                })
            }
        }
        val epi = q.epicenter
        if (q.originMs != null && epi != null) {
            pWave = waveFront(0xFF42A5F5.toInt())
            sWave = waveFront(0xFFE53935.toInt())
            map.overlays.add(pWave); map.overlays.add(sWave)
        }
        epi?.let {
            map.overlays.add(Marker(map).apply {
                position = gp(it); title = getString(R.string.epicenter)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = getDrawable(R.drawable.ic_epicenter)
            })
        }
        map.overlays.add(Marker(map).apply {
            position = gp(q.user); title = getString(R.string.you)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = getDrawable(R.drawable.ic_you)
        })
        val pts = listOfNotNull(q.user, epi)
        if (refit) map.post { fit(pts) }
    }

    /**
     * Shows [pts] with some margin. osmdroid computes a NaN zoom (and stops responding) when the
     * margins leave no room, so the margin shrinks with the map, and a tiny map just centres.
     */
    private fun fit(pts: List<LatLng>) {
        val w = map.width
        val h = map.height
        val border = (minOf(w, h) / 8).coerceAtMost(40)
        if (pts.size >= 2 && w >= 120 && h >= 120) {
            val box = BoundingBox.fromGeoPoints(pts.map { gp(it) }).increaseByScale(1.6f)
            map.zoomToBoundingBox(box, false, border)
        } else {
            map.controller.setZoom(7.0)
            map.controller.setCenter(gp(pts.first()))
        }
    }

    /** A revision: redraw contours, epicentre and wave fronts without moving the user's view. */
    private fun rebuildOverlaysOnly(q: LocalQuake) {
        val center = map.mapCenter
        val zoom = map.zoomLevelDouble
        rebuildMap(q, refit = false)
        map.controller.setZoom(zoom)
        map.controller.setCenter(center)
    }

    private fun waveFront(color: Int) = Polygon(map).apply {
        fillPaint.color = Color.TRANSPARENT
        outlinePaint.color = color
        outlinePaint.strokeWidth = 6f
        outlinePaint.style = Paint.Style.STROKE
        setOnClickListener { _, _, _ -> true }
    }

    private fun update() {
        val q = AlertCenter.active ?: run { header.text = getString(R.string.no_active_alert); return }
        if (q !== shown || q.alert.id != shownAlertId || q.revision != shownRevision) {
            val keepView = q === shown
            shown = q; shownAlertId = q.alert.id; shownRevision = q.revision
            if (keepView) rebuildOverlaysOnly(q) else rebuildMap(q)
        }
        flashIfChanged(header, AlertCenter.localTitle(q, this), R.color.alert_text)
        val headerColor = getColor(if (q.isTest) R.color.alert_header_test else R.color.alert_header)
        header.setBackgroundColor(headerColor)
        window.statusBarColor = headerColor
        flashIfChanged(info, AlertCenter.localText(q, this), R.color.alert_text_soft)
        stopBtn.isEnabled = AlertCenter.player.isPlaying

        val now = q.now() // Google-clock time
        val s = q.sArrivalMs
        countdownLabel.setText(if (s == null) R.string.no_countdown else R.string.strong_shaking_in)
        countdown.text = when {
            s == null -> "—"
            s > now -> getString(R.string.seconds_left, Math.ceil((s - now) / 1000.0).toInt())
            else -> getString(R.string.seconds_left, 0)
        }
        countdown.setTextColor(getColor(if (s != null && s - now < 10_000) R.color.alert_urgent else R.color.alert_text))

        val epi = q.epicenter
        val origin = q.originMs
        if (epi != null && origin != null) {
            val elapsedS = (now - origin) / 1000.0
            // Wavefront on the surface: radius where the hypocentral distance equals speed × t.
            fun radiusKm(speed: Double): Double? {
                val r = speed * elapsedS
                val d = q.quake?.depthKm ?: 10.0
                return if (r > d) kotlin.math.sqrt(r * r - d * d) else null
            }
            listOf(pWave to radiusKm(q.pSpeedKms), sWave to radiusKm(q.sSpeedKms)).forEach { (poly, r) ->
                poly ?: return@forEach
                if (r == null || r > 2500) poly.points = emptyList()
                else poly.points = Geo.circle(epi, r, 90).map { gp(it) }.let { it + it.first() }
            }
            map.invalidate()
        }
    }
}
