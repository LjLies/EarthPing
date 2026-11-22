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
