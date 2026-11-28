package io.github.ljlies.earthping

import android.app.NotificationManager
import io.github.ljlies.earthping.alert.AlertCenter
import io.github.ljlies.earthping.alert.AlertDecoder
import io.github.ljlies.earthping.alert.Notifications
import io.github.ljlies.earthping.ui.HistoryMapActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.Base64

/** A real alert for a watched place notifies, and tapping it opens that alert on the map. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchedNotificationTest {
    @Test fun opensTheAlertOnTheMap() {
        val app = RuntimeEnvironment.getApplication()
        Prefs(app).apply {
            onboardingDone = true; disclaimerShown = true
            manualLocation = io.github.ljlies.earthping.geo.LatLng(45.48, 9.16); manualLocationEnabled = true
            userDecoys = listOf(Place("Tonosí", 7.41, -80.45))
        }
        Notifications.ensureChannels(app)
        AlertCenter.init(app)
        val raw = Base64.getMimeDecoder().decode(javaClass.classLoader!!.getResourceAsStream("quake_panama_20261031_rawdata.b64").readBytes())
        AlertCenter.deliverForWatchedTest(AlertDecoder.decodeBatch(raw).first())
        ShadowLooper.idleMainLooper()

        val n = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single { it.channelId == Notifications.CH_WATCHED }
        val intents = shadowOf(n.contentIntent).savedIntents
        val map = intents.last()
        assertEquals(HistoryMapActivity::class.java.name, map.component!!.className)
        assertEquals("8fad9-2026-10-31T15:50:25.645Z", map.getStringExtra(HistoryMapActivity.EXTRA_EVENT))
    }
}
