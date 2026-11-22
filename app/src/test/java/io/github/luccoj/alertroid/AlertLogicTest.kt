package io.github.luccoj.alertroid

import io.github.luccoj.alertroid.alert.AlertCenter
import io.github.luccoj.alertroid.geo.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Loud-vs-notice decisions, revisions and tests, end to end through AlertCenter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlertLogicTest {
    private fun fire(): io.github.luccoj.alertroid.alert.LocalQuake {
        AlertCenter.fireTest()
        ShadowLooper.idleMainLooper()
        return AlertCenter.active!!
    }

    @Test fun decisions() {
        val app = RuntimeEnvironment.getApplication()
        val p = Prefs(app)
        p.manualLocation = LatLng(45.48, 9.16)
        p.manualLocationEnabled = true
        p.testQuiet = true
        p.testLeadS = 25          // M5.8 about 115 km away: estimated intensity ~3.8
        p.testMagnitude = 5.8f
        AlertCenter.init(app)

        // "Any alert for my area": loud
        p.raw.edit().putString("loud_min_mmi", "0").commit()
        val first = fire()
        assertTrue("any alert must be loud", first.loud)
        AlertCenter.silence()

        // A second test shortly after is a new alert, never a revision of the first
        val second = fire()
        assertNotSame(first, second)
        assertTrue(second.loud)
        assertFalse(second.dismissed)
        AlertCenter.silence()

        // Threshold V: starts as a notice...
        p.raw.edit().putString("loud_min_mmi", "5").commit()
        val third = fire()
        assertFalse("M5.8 at 115 km is below V", third.loud)
        // ...and the simulated revision (M6.2) is still below V: stays a notice
        ShadowLooper.idleMainLooper(12, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(EventLog.snapshot().takeLast(8).joinToString("\n"), 1, third.revision)
        assertFalse(third.loud)

        // Threshold IV: the revision crosses it and turns into a full alarm
        p.raw.edit().putString("loud_min_mmi", "4").commit()
        val fourth = fire()
        assertFalse(fourth.loud)
        ShadowLooper.idleMainLooper(12, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("crossing the threshold must escalate", fourth.loud)
        AlertCenter.silence()

        // A big quake close by: the strong-shaking siren takes over (quiet tests excepted)
        p.testQuiet = false
        p.testMagnitude = 7.6f
        p.testLeadS = 5
        p.raw.edit().putString("siren_min_mmi", "7").commit()
        val big = fire()
        assertTrue(big.loud)
        assertTrue("expected ${big.effectiveMmi}", big.siren)
        AlertCenter.silence()
    }

    @Test fun oldThirdLevelMigratesToAny() {
        val p = Prefs(RuntimeEnvironment.getApplication())
        p.raw.edit().putString("loud_min_mmi", "3").commit()
        p.migrate()
        assertEquals(0, p.loudMinMmi)
    }
}
