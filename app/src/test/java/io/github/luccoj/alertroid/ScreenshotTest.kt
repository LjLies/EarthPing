package io.github.luccoj.alertroid

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import io.github.luccoj.alertroid.alert.AlertCenter
import io.github.luccoj.alertroid.geo.LatLng
import io.github.luccoj.alertroid.ui.AlertActivity
import io.github.luccoj.alertroid.ui.MainActivity
import io.github.luccoj.alertroid.ui.OnboardingActivity
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Renders the screens to PNGs for a look; only when -Pscreenshots=DIR is given. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotTest {
    private fun prefs(onboarded: Boolean = true) = Prefs(RuntimeEnvironment.getApplication()).apply {
        onboardingDone = onboarded
        manualLocation = LatLng(41.9028, 12.4964)
        manualLocationEnabled = true
        userDecoys = listOf(Place("Napoli", 40.85, 14.27))
    }

    private fun <T : Activity> shoot(name: String, cls: Class<T>, height: Int = 1782, prepare: (T) -> Unit = {}) {
        val dir = System.getProperty("screenshots")
        assumeTrue(dir != null)
        val activity = Robolectric.buildActivity(cls).setup().get()
        prepare(activity)
        ShadowLooper.idleMainLooper()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(822, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 822, height)
        val bitmap = Bitmap.createBitmap(822, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(dir!!).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun alerts() { prefs(); shoot("alerts", MainActivity::class.java) }

    @Test @Config(qualifiers = "it-w411dp-h891dp-night-xhdpi")
    fun alertsItalianNight() { prefs(); shoot("alerts-it-night", MainActivity::class.java) }

    @Test fun alertsOff() { prefs().serviceEnabled = false; shoot("alerts-off", MainActivity::class.java) }

    @Test fun tests() { prefs(); shoot("tests", io.github.luccoj.alertroid.ui.TestActivity::class.java) }

    @Test fun location() { prefs(); shoot("location", io.github.luccoj.alertroid.ui.LocationActivity::class.java) }

    @Test fun places() { prefs(); shoot("places", MainActivity::class.java) { it.findViewById<View>(R.id.tab_places).performClick() } }

    @Test fun settings() { prefs(); shoot("settings", MainActivity::class.java) { it.findViewById<View>(R.id.tab_settings).performClick() } }

    @Test @Config(qualifiers = "it-w411dp-h891dp-xhdpi")
    fun settingsItalian() { prefs(); shoot("settings-it", MainActivity::class.java) { it.findViewById<View>(R.id.tab_settings).performClick() } }

    @Test fun onboarding() { prefs(onboarded = false); shoot("onboarding", OnboardingActivity::class.java) }

    @Test @Config(qualifiers = "it-w411dp-h891dp-xhdpi")
    fun alertScreen() {
        val p = prefs()
        p.testLeadS = 25
        p.testQuiet = true
        AlertCenter.init(RuntimeEnvironment.getApplication())
        AlertCenter.fireTest()
        ShadowLooper.idleMainLooper()
        shoot("alert-it", AlertActivity::class.java)
    }
}
