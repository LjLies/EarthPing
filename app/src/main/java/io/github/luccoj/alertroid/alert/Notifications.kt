package io.github.luccoj.alertroid.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import io.github.luccoj.alertroid.R

/**
 * Notification channels. Channels that must break through Do Not Disturb are (re)created once
 * the user has granted notification-policy access, because Android only honours
 * "bypass DND" for channels created while that access is held.
 */
object Notifications {
    const val CH_SERVICE = "service"
    const val CH_STATUS = "status"
    private const val CH_QUAKE_BASE = "quake_local"
    /** Earthquakes below the loud-alarm threshold: an ordinary notification (no DND bypass). */
    const val CH_NOTICE = "quake_notice"
    /** Public alerts for my location: an ordinary, prominent notification. */
    const val CH_CRISIS = "crisis"
    /** While the full-screen warning is on screen: silent, so no heads-up covers it. */
    const val CH_ALERT_SHOWN = "alert_on_screen"
    /** Revisions arriving after the warning was closed. */
    const val CH_UPDATES = "quake_updates"
    const val CH_WATCHED = "watched"
    /** Channels of older versions that are no longer used. */
    private val OBSOLETE_PREFIXES = listOf("quake_info_", "crisis_local_")

    const val ID_SERVICE = 1
    const val ID_STATUS = 2
    const val ID_LOCAL_QUAKE = 100

    fun quakeChannel(ctx: Context) = current(ctx, CH_QUAKE_BASE)

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun current(ctx: Context, base: String): String {
        val gen = ctx.getSharedPreferences("channels", Context.MODE_PRIVATE).getInt(base, 1)
        return "${base}_$gen"
    }

    fun ensureChannels(ctx: Context) {
        val nm = nm(ctx)
        val hasDnd = nm.isNotificationPolicyAccessGranted
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, ctx.getString(R.string.ch_service), NotificationManager.IMPORTANCE_MIN).apply {
            description = ctx.getString(R.string.ch_service_desc)
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, ctx.getString(R.string.ch_status), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = ctx.getString(R.string.ch_status_desc)
        })
        nm.createNotificationChannel(NotificationChannel(CH_WATCHED, ctx.getString(R.string.ch_watched), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = ctx.getString(R.string.ch_watched_desc)
        })
        nm.notificationChannels.filter { ch -> OBSOLETE_PREFIXES.any { ch.id.startsWith(it) } }
            .forEach { nm.deleteNotificationChannel(it.id) }
        val notifAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(NotificationChannel(CH_NOTICE, ctx.getString(R.string.ch_info), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.ch_info_desc)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), notifAttrs)
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_CRISIS, ctx.getString(R.string.ch_crisis), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.ch_crisis_desc)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), notifAttrs)
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT_SHOWN, ctx.getString(R.string.ch_alert_shown), NotificationManager.IMPORTANCE_LOW).apply {
            description = ctx.getString(R.string.ch_alert_shown_desc)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_UPDATES, ctx.getString(R.string.ch_updates), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = ctx.getString(R.string.ch_updates_desc)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        // The loud alert plays its own siren on the alarm stream; the channel itself only vibrates.
        ensureDndChannel(ctx, CH_QUAKE_BASE, hasDnd) {
            NotificationChannel(it, ctx.getString(R.string.ch_quake), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.ch_quake_desc)
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
        }
    }

    private fun ensureDndChannel(ctx: Context, base: String, hasDnd: Boolean, make: (String) -> NotificationChannel) {
        val nm = nm(ctx)
        val sp = ctx.getSharedPreferences("channels", Context.MODE_PRIVATE)
        var gen = sp.getInt(base, 1)
        val existing = nm.getNotificationChannel("${base}_$gen")
        if (existing != null && hasDnd && !existing.canBypassDnd() && !sp.getBoolean("${base}_dnd_$gen", false)) {
            // Created before DND access was granted: replace it so the bypass takes effect.
            nm.deleteNotificationChannel(existing.id)
            gen++
            sp.edit().putInt(base, gen).apply()
        }
        if (nm.getNotificationChannel("${base}_$gen") == null) {
            nm.createNotificationChannel(make("${base}_$gen"))
            if (hasDnd) sp.edit().putBoolean("${base}_dnd_$gen", true).apply()
        }
    }

    fun quakeChannelBypassesDnd(ctx: Context): Boolean = nm(ctx).getNotificationChannel(quakeChannel(ctx))?.canBypassDnd() == true
}
