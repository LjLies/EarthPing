package io.github.ljlies.earthping

import android.app.NotificationManager
import io.github.ljlies.earthping.alert.Notifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationsTest {
    @Test fun channelsAndGroups() {
        val app = RuntimeEnvironment.getApplication()
        val nm = app.getSystemService(NotificationManager::class.java)
        // Before onboarding, without DND access, the warning channel waits.
        Notifications.ensureChannels(app)
        assertFalse(nm.notificationChannels.any { it.id == Notifications.CH_QUAKE })
        Prefs(app).onboardingDone = true
        Notifications.ensureChannels(app)
        val byGroup = nm.notificationChannels.groupBy({ it.group }, { it.id })
        assertEquals(setOf("quakes", "public", "status"), byGroup.keys)
        assertEquals(setOf(Notifications.CH_QUAKE, Notifications.CH_ALERT_SHOWN, Notifications.CH_NOTICE, Notifications.CH_WATCHED), byGroup["quakes"]!!.toSet())
        assertEquals(setOf(Notifications.CH_CRISIS, Notifications.CH_WATCHED_PUBLIC), byGroup["public"]!!.toSet())
        assertTrue(Notifications.noticeChannelAudible(app))
    }
}
