package io.github.ljlies.earthping

import android.app.NotificationManager
import io.github.ljlies.earthping.alert.Notifications
import io.github.ljlies.earthping.service.DndWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DndWatchTest {
    @Test fun noticeAfterAlarmsWereSilenced() {
        val app = RuntimeEnvironment.getApplication()
        val nm = app.getSystemService(NotificationManager::class.java)
        Notifications.ensureChannels(app)
        val prefs = Prefs(app)
        val watch = DndWatch(app)

        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE) // total silence
        watch.check()
        val since = prefs.raw.getLong("dnd_blocked_since", 0)
        assertNotEquals(0L, since)

        // Pretend it lasted ten minutes, then DND ends.
        prefs.raw.edit().putLong("dnd_blocked_since", since - 600_000).commit()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        watch.check()
        assertEquals(0L, prefs.raw.getLong("dnd_blocked_since", 0))
        val posted = shadowOf(nm).allNotifications
        assertTrue(posted.any { it.channelId == Notifications.CH_STATUS })
    }
}
