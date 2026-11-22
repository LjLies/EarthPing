package io.github.ljlies.earthping

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StayAliveTest {
    companion object {
        fun quake(id: String, mag: Double, lat: Double, lon: Double, depth: Double) = io.github.ljlies.earthping.alert.GoogleAlert(
            id, null, null, null, io.github.ljlies.earthping.alert.GoogleAlert.Mode.LIVE, io.github.ljlies.earthping.alert.GoogleAlert.Type.EARTHQUAKE,
            emptyList(), emptyList(), emptyList(), null, null, null, null, null,
            io.github.ljlies.earthping.alert.Quake(mag, io.github.ljlies.earthping.geo.LatLng(lat, lon), depth, 1_700_000_000_000, null, null, true))

        /** A few alerts, one of them revised twice. */
        fun seedHistory(ctx: Context) {
            val h = io.github.ljlies.earthping.alert.AlertHistory
            h.clear(ctx)
            val rome = io.github.ljlies.earthping.geo.LatLng(41.9028, 12.4964)
            h.add(ctx, quake("a", 4.2, 44.5, 11.3, 10.0), null, local = true, distanceKm = 300.0, at = rome)
            h.add(ctx, quake("b", 5.1, 42.6, 13.3, 9.0), null, local = true, distanceKm = 110.0, at = rome)
            h.add(ctx, quake("b2", 5.6, 42.7, 13.2, 8.0), null, local = true, distanceKm = 105.0, event = "b", at = rome)
            h.add(ctx, quake("b3", 5.8, 42.7, 13.2, 8.0), null, local = true, distanceKm = 105.0, event = "b", at = rome)
            h.add(ctx, quake("c", 6.4, 38.2, 15.6, 15.0), null, local = true, distanceKm = 520.0, at = rome)
        }
    }

    @Test fun revisionsUpdateOneEntry() {
        val app = RuntimeEnvironment.getApplication()
        seedHistory(app)
        val list = io.github.ljlies.earthping.alert.AlertHistory.list()
        assertEquals(3, list.size)
        val b = list.first { it.event == "b" }
        assertEquals(2, b.revisions)
        assertEquals(5.8, b.magnitude!!, 1e-9)
        assertEquals(42.7, b.lat!!, 1e-9)
        assertTrue(b.mmi != null)
        // Survives a reload from the file.
        val f = File(Storage.of(app).filesDir, "history.json")
        assertTrue(f.readText().contains("\"r\":2"))
    }

    @Test fun movesOldStateToDeviceStorage() {
        val app = RuntimeEnvironment.getApplication()
        val de = Storage.of(app)
        // State as older versions left it, in credential-protected storage.
        de.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit().putBoolean("onboarding_done", true).commit()
        File(app.filesDir, "history.json").writeText("[]")

        val moved = Storage.migrate(app)
        assertTrue(moved.toString(), moved.contains("earthping.xml") && moved.contains("history.json"))
        assertTrue(Prefs(app).onboardingDone)
        assertTrue(File(de.filesDir, "history.json").exists())
        assertFalse(File(app.filesDir, "history.json").exists())
        assertTrue(Storage.migrate(app).isEmpty())
    }

    @Test fun crashRestartsBackOff() {
        val p = Prefs(RuntimeEnvironment.getApplication())
        val t = 1_000_000_000L
        assertEquals(3_000L, p.noteCrash(t))
        assertEquals(10_000L, p.noteCrash(t + 60_000))
        assertEquals(60_000L, p.noteCrash(t + 120_000))
        assertEquals(300_000L, p.noteCrash(t + 180_000))
        assertEquals(900_000L, p.noteCrash(t + 240_000))
        assertEquals(900_000L, p.noteCrash(t + 300_000))
        // Quiet for more than 10 minutes: back to a quick restart.
        assertEquals(3_000L, p.noteCrash(t + 300_000 + 11 * 60_000))
    }
}
