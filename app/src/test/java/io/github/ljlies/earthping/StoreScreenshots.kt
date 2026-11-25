package io.github.ljlies.earthping

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import io.github.ljlies.earthping.alert.AlertCenter
import io.github.ljlies.earthping.alert.AlertDecoder
import io.github.ljlies.earthping.geo.LatLng
import io.github.ljlies.earthping.ui.AlertActivity
import io.github.ljlies.earthping.ui.HistoryMapActivity
import io.github.ljlies.earthping.ui.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.net.URL
import java.util.Base64
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Screenshots for the store listing (fastlane), only with -Pscreenshots=DIR. They use the real
 * Panama earthquake alert from the test fixtures, delivered as if it had just arrived to someone
 * in Chitré, with OpenStreetMap tiles downloaded on the fly (Robolectric can't load them itself).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Android 13: Robolectric can't grant the Android 14+ full-screen permission, which would show as missing.
@Config(sdk = [33], qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
class StoreScreenshots {
    private val w = 1233
    private val h = 2673
    private val user = LatLng(7.9614, -80.4297)

    private fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        // A phone with everything allowed, connected for a while.
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
        val nm = org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
        nm.setNotificationPolicyAccessGranted(true)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        io.github.ljlies.earthping.alert.Notifications.ensureChannels(app)
        val now0 = System.currentTimeMillis()
        io.github.ljlies.earthping.service.ServiceStatus.apply {
            state = io.github.ljlies.earthping.service.ServiceStatus.State.CONNECTED
            connectedSinceMs = now0 - 3 * 3600_000L; lastRxMs = now0 - 40_000
            topics = listOf("ea.a", "ea.b", "ea.c", "ea.d")
        }
        Prefs(app).apply {
            onboardingDone = true; disclaimerShown = true
            manualLocation = user; manualLocationName = "Chitré"; manualLocationEnabled = true
            userDecoys = listOf(Place("Panamá", 8.9824, -79.5199), Place("San José", 9.9281, -84.0907))
            alarmSound = Prefs.SOUND_NONE
            raw.edit().putBoolean("tts", false).commit()
        }
        val raw = Base64.getMimeDecoder().decode(javaClass.classLoader!!.getResourceAsStream("quake_panama_20261031_rawdata.b64").readBytes())
        val recorded = AlertDecoder.decodeBatch(raw).first()
        // Same alert, moved in time so that the strong shaking is still a few seconds away.
        val now = System.currentTimeMillis()
        val a = recorded.copy(issuedMs = now - 2_000, updatedMs = now - 2_000, quake = recorded.quake!!.copy(originMs = now - 9_000))
        AlertCenter.init(app)
        AlertCenter.deliverForScreenshot(a, user)
        ShadowLooper.idleMainLooper()
    }

    @Test fun shots() {
        val dir = System.getProperty("screenshots")
        assumeTrue(dir != null)
        setUp()
        shoot(File(dir, "1.png"), AlertActivity::class.java)
        AlertCenter.silence()
        // Back to the default sound settings for the settings screenshot.
        Prefs(RuntimeEnvironment.getApplication()).raw.edit().remove("alarm_sound").remove("tts").commit()
        shoot(File(dir, "2.png"), MainActivity::class.java)
        shoot(File(dir, "3.png"), HistoryMapActivity::class.java)
        shoot(File(dir, "4.png"), MainActivity::class.java) { it.findViewById<View>(R.id.tab_places).performClick() }
        shoot(File(dir, "5.png"), MainActivity::class.java) { it.findViewById<View>(R.id.tab_settings).performClick() }
    }

    private fun <T : Activity> shoot(file: File, cls: Class<T>, prepare: (T) -> Unit = {}) {
        val activity = Robolectric.buildActivity(cls).setup().get()
        prepare(activity)
        val root = activity.window.decorView
        fun layoutAndDraw(): Bitmap {
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, w, h)
            ShadowLooper.idleMainLooper()
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        layoutAndDraw()
        // The place editor's map starts zoomed out on the whole world: show the watched places.
        maps(root).filter { it.zoomLevelDouble < 4 }.forEach { it.controller.setZoom(6.8); it.controller.setCenter(GeoPoint(9.3, -81.6)) }
        layoutAndDraw()
        maps(root).forEach { addTiles(it) }
        val bitmap = layoutAndDraw()
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun maps(v: View): List<MapView> = when {
        v is MapView -> listOf(v)
        v is android.view.ViewGroup -> (0 until v.childCount).flatMap { maps(v.getChildAt(it)) }
        else -> emptyList()
    }.filter { it.isShown && it.width > 0 }

    // ---- OpenStreetMap tiles, drawn by an overlay under the app's own overlays

    private fun tileX(lon: Double, z: Int) = floor((lon + 180) / 360 * (1 shl z)).toInt()
    private fun tileY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat)
        return floor((1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * (1 shl z)).toInt()
    }
    private fun lonOf(x: Int, z: Int) = x.toDouble() / (1 shl z) * 360 - 180
    private fun latOf(y: Int, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl z)))))

    private fun addTiles(map: MapView) {
        val z = Math.round(map.zoomLevelDouble).toInt().coerceIn(2, 16)
        val box = map.boundingBox
        val tiles = HashMap<Pair<Int, Int>, Bitmap>()
        for (x in tileX(box.lonWest, z)..tileX(box.lonEast, z)) for (y in tileY(box.latNorth, z)..tileY(box.latSouth, z)) {
            tile(z, x, y)?.let { tiles[x to y] = it }
        }
        map.overlays.add(0, object : Overlay() {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            override fun draw(c: Canvas, m: MapView, shadow: Boolean) {
                if (shadow) return
                val p = m.projection
                for ((xy, bmp) in tiles) {
                    val nw = p.toPixels(GeoPoint(latOf(xy.second, z), lonOf(xy.first, z)), null)
                    val se = p.toPixels(GeoPoint(latOf(xy.second + 1, z), lonOf(xy.first + 1, z)), null)
                    c.drawBitmap(bmp, null, RectF(nw.x.toFloat(), nw.y.toFloat(), se.x.toFloat(), se.y.toFloat()), paint)
                }
            }
        })
        map.invalidate()
    }

    private fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val n = 1 shl z
        if (y !in 0 until n) return null
        val xx = ((x % n) + n) % n
        val cache = File(System.getProperty("java.io.tmpdir"), "earthping-tiles/$z/$xx/$y.png")
        if (!cache.exists()) {
            try {
                val c = URL("https://tile.openstreetmap.org/$z/$xx/$y.png").openConnection()
                c.setRequestProperty("User-Agent", "EarthPing store screenshots (github.com/LjLies/EarthPing)")
                cache.parentFile!!.mkdirs()
                c.getInputStream().use { input -> cache.outputStream().use { input.copyTo(it) } }
            } catch (e: Exception) {
                println("tile $z/$xx/$y: $e")
                return null
            }
        }
        return BitmapFactory.decodeFile(cache.path)
    }
}
