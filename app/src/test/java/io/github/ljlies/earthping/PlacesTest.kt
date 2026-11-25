package io.github.ljlies.earthping

import android.view.View
import android.widget.Button
import android.widget.EditText
import io.github.ljlies.earthping.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Watched places: the top buttons show what's saved; Save only with a change; Cancel reverts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlacesTest {
    @Test fun draftAndSave() {
        val app = RuntimeEnvironment.getApplication()
        Prefs(app).apply {
            onboardingDone = true; disclaimerShown = true
            userDecoys = listOf(Place("Miyako", 39.64, 141.95))
        }
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.findViewById<View>(R.id.tab_places).performClick()
        ShadowLooper.idleMainLooper()
        val save = a.findViewById<Button>(R.id.save)
        val clear = a.findViewById<Button>(R.id.clear)
        val slot1 = a.findViewById<Button>(R.id.slot1)
        fun field(id: Int) = a.findViewById<EditText>(id)

        assertFalse("nothing to save yet", save.isEnabled)
        assertEquals("Miyako", slot1.text.toString())

        field(R.id.name).setText("Tonosí")
        field(R.id.lat).setText("7.41")
        field(R.id.lon).setText("-80.45")
        assertTrue(save.isEnabled)
        assertEquals("the saved name stays on top until Save", "Miyako", slot1.text.toString())

        clear.performClick() // Cancel
        assertEquals("Miyako", field(R.id.name).text.toString())
        assertFalse(save.isEnabled)

        field(R.id.name).setText("Tonosí")
        field(R.id.lat).setText("7.41")
        field(R.id.lon).setText("-80.45")
        save.performClick()
        assertEquals("Tonosí", slot1.text.toString())
        assertFalse(save.isEnabled)
        assertEquals("Tonosí", Prefs(app).userDecoys.first().name)
    }
}
