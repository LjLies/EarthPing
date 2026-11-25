package io.github.ljlies.earthping.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.text.format.DateFormat
import io.github.ljlies.earthping.EventLog
import io.github.ljlies.earthping.Prefs
import io.github.ljlies.earthping.R
import io.github.ljlies.earthping.alert.AlarmPlayer
import io.github.ljlies.earthping.alert.Notifications
import io.github.ljlies.earthping.ui.MainActivity
import java.util.Date

/**
 * Notices when Do Not Disturb (or an Android 15 mode) silences alarm sounds, whether or not an
 * alert arrives meanwhile, and afterwards says for how long earthquake alarms couldn't be heard.
 * While it lasts, a notification would be hidden by the same mode, so the notice comes after.
 */
class DndWatch(private val ctx: Context) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val prefs = Prefs(ctx)
    private var receiver: BroadcastReceiver? = null

    fun start() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = check()
        }
        val filter = IntentFilter().apply {
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED)
            if (Build.VERSION.SDK_INT >= 35) addAction(NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED)
        }
        ctx.registerReceiver(r, filter)
        receiver = r
        check()
    }

    fun stop() {
        receiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
    }

    /**
     * Blocked means: alarms are silenced and EarthPing can't lift that during an alarm (Android
     * 15+, no DND access, or "Override silent mode" off).
     */
    private fun blocked(): Boolean {
        if (!AlarmPlayer.alarmsBlocked(nm, ifUnknown = false)) return false
        val canLift = Build.VERSION.SDK_INT < 35 && nm.isNotificationPolicyAccessGranted && prefs.overrideSilence
        return !canLift
    }

    fun check() {
        val since = prefs.raw.getLong("dnd_blocked_since", 0)
        val now = System.currentTimeMillis()
        val blocked = try { blocked() } catch (e: Exception) { false }
        when {
            blocked && since == 0L -> {
                prefs.raw.edit().putLong("dnd_blocked_since", now).apply()
                EventLog.log("Do Not Disturb is silencing alarms: earthquake alarms can't be heard")
            }
            !blocked && since != 0L -> {
                prefs.raw.edit().remove("dnd_blocked_since").apply()
                EventLog.log("Do Not Disturb no longer silences alarms")
                if (now - since >= 60_000) notify(since, now)
            }
        }
    }

    private fun notify(from: Long, to: Long) {
        val df = DateFormat.getTimeFormat(ctx)
        val sameDay = DateFormat.format("yyyyMMdd", from) == DateFormat.format("yyyyMMdd", to)
        fun at(ms: Long) = if (sameDay) df.format(Date(ms)) else "${DateFormat.getMediumDateFormat(ctx).format(Date(ms))} ${df.format(Date(ms))}"
        val text = ctx.getString(R.string.dnd_blocked_text, at(from), at(to))
        val open = PendingIntent.getActivity(ctx, 21, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(Notifications.ID_STATUS + 2, Notification.Builder(ctx, Notifications.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_quake)
            .setContentTitle(ctx.getString(R.string.dnd_blocked_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}
