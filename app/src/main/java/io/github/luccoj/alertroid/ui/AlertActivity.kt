package io.github.luccoj.alertroid.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.luccoj.alertroid.R
import io.github.luccoj.alertroid.alert.AlertCenter
import io.github.luccoj.alertroid.alert.LocalQuake
import io.github.luccoj.alertroid.geo.Geo
import io.github.luccoj.alertroid.geo.LatLng
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.util.Locale

/**
 * The full-screen warning: seconds until the S-wave, and a map with the epicentre, the user,
 * Google's intensity contours, and the expanding P- and S-wave fronts.
 */
class AlertActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var header: TextView
    private lateinit var countdown: TextView
    private lateinit var info: TextView
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

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF111111.toInt()) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        header = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 26f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            maxLines = 1; setLines(1); ellipsize = android.text.TextUtils.TruncateAt.END
            setBackgroundColor(0xFFC62828.toInt()); setPadding(16, 24, 16, 24)
        }
        // Fixed-height, single-line texts: nothing on screen may move while counting down.
        countdown = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 64f; setTypeface(Typeface.DEFAULT_BOLD); gravity = Gravity.CENTER
            maxLines = 1; setLines(1)
            setAutoSizeTextTypeUniformWithConfiguration(24, 64, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        info = TextView(this).apply {
            setTextColor(0xFFEEEEEE.toInt()); textSize = 16f; gravity = Gravity.CENTER; setPadding(16, 0, 16, 16)
            setLines(2); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            setBackgroundColor(0xFF263238.toInt())
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stopBtn = Button(this).apply { text = getString(R.string.stop_alarm); isAllCaps = false; setOnClickListener { AlertCenter.silence() } }
        val close = Button(this).apply { text = getString(R.string.close); isAllCaps = false; setOnClickListener { AlertCenter.silence(); finish() } }
        buttons.addView(stopBtn, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(close, LinearLayout.LayoutParams(0, -2, 1f))

        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        root.addView(countdown, LinearLayout.LayoutParams(-1, -2))
        root.addView(info, LinearLayout.LayoutParams(-1, -2))
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        frame.run()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        main.removeCallbacks(frame)
    }

    private val frame = object : Runnable {
        override fun run() {
            update()
            main.postDelayed(this, 100)
        }
    }

    private fun gp(l: LatLng) = GeoPoint(l.lat, l.lon)

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
        if (refit) map.post {
            if (pts.size >= 2) {
                val box = BoundingBox.fromGeoPoints(pts.map { gp(it) })
                val padded = box.increaseByScale(1.6f)
                map.zoomToBoundingBox(padded, false, 40)
            } else {
                map.controller.setZoom(8.0); map.controller.setCenter(gp(q.user))
            }
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
        header.text = AlertCenter.localTitle(q)
        header.setBackgroundColor(if (q.isTest) 0xFF6A1B9A.toInt() else 0xFFC62828.toInt())
        info.text = AlertCenter.localText(q)
        stopBtn.isEnabled = AlertCenter.player.isPlaying

        val now = q.now() // Google-clock time
        val s = q.sArrivalMs
        countdown.text = when {
            s == null -> getString(R.string.take_cover)
            s > now -> getString(R.string.seconds_left, Math.ceil((s - now) / 1000.0).toInt())
            else -> getString(R.string.seconds_left, 0)
        }
        countdown.setTextColor(if (s != null && s - now < 10_000) 0xFFFF5252.toInt() else Color.WHITE)

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

    @Suppress("unused")
    private fun fmt(v: Double) = String.format(Locale.getDefault(), "%.1f", v)
}
