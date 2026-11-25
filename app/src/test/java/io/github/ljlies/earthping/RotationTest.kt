package io.github.ljlies.earthping

import io.github.ljlies.earthping.alert.AlertCenter
import io.github.ljlies.earthping.geo.LatLng
import io.github.ljlies.earthping.ui.AlertActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * The warning screen survives being turned to landscape and back (Robolectric keeps the old
 * layout on recreation, so the squeezed map of 0.4.2 is exercised too), and has a real map in
 * landscape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xhdpi")
class RotationTest {
    @Test(timeout = 60_000) fun rotateDuringAlert() {
        val app = RuntimeEnvironment.getApplication()
        Prefs(app).apply {
            onboardingDone = true; disclaimerShown = true
            manualLocation = LatLng(41.9, 12.5); manualLocationEnabled = true
            testQuiet = true; testLeadS = 25
        }
        AlertCenter.init(app)
        AlertCenter.fireTest()
        ShadowLooper.idleMainLooper()
        val controller = Robolectric.buildActivity(AlertActivity::class.java).setup()
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS)
        RuntimeEnvironment.setQualifiers("w891dp-h411dp-land")
        controller.configurationChange(android.content.res.Configuration(RuntimeEnvironment.getApplication().resources.configuration))
        layout(controller.get(), 1782, 760)
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        layout(controller.get(), 1782, 760)
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port")
        controller.configurationChange(android.content.res.Configuration(RuntimeEnvironment.getApplication().resources.configuration))
        layout(controller.get(), 822, 1782)
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
    }

    @Test(timeout = 60_000) @Config(qualifiers = "w891dp-h411dp-land-xhdpi")
    fun landscapeLayout() {
        val app = RuntimeEnvironment.getApplication()
        Prefs(app).apply {
            onboardingDone = true; disclaimerShown = true
            manualLocation = LatLng(41.9, 12.5); manualLocationEnabled = true
            testQuiet = true; testLeadS = 25
        }
        AlertCenter.init(app)
        AlertCenter.fireTest()
        ShadowLooper.idleMainLooper()
        val a = Robolectric.buildActivity(AlertActivity::class.java).setup().get()
        layout(a, 1782, 760, check = true)
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
    }

    private fun layout(a: android.app.Activity, w: Int, h: Int, check: Boolean = false) {
        val root = a.window.decorView
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val map = a.findViewById<android.view.View>(R.id.map)
        // The map must stay usable whichever way the phone is held.
        if (check) org.junit.Assert.assertTrue("map ${map.width}x${map.height}", map.height > h / 2 && map.width > w / 3)
        root.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)))
    }
}
