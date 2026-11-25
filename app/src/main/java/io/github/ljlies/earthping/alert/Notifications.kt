package io.github.ljlies.earthping.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import io.github.ljlies.earthping.R
import io.github.ljlies.earthping.Storage

/**
 * Notification channels. Channels that must break through Do Not Disturb are (re)created once
 * the user has granted notification-policy access, because Android only honours
 * "bypass DND" for channels created while that access is held.
 */
object Notifications {
    const val CH_SERVICE = "service"
    const val CH_STATUS = "status"
    private const val CH_QUAKE_BASE = "quake_local"
    /**
     * Earthquakes below the loud-alarm threshold, and updates after a warning was closed: an
     * ordinary notification (no DND bypass). "Also in silent mode" is done by the app playing the
     * chime itself, so one channel serves both choices.
     */
    const val CH_NOTICE = "quake_notice_chime"
    /** Public alerts for my location: an ordinary, prominent notification. */
    const val CH_CRISIS = "crisis"
    /** While the full-screen warning is on screen: silent, so no heads-up covers it. */
    const val CH_ALERT_SHOWN = "alert_on_screen"
    const val CH_WATCHED = "watched"
    /** Channels of older versions that are no longer used. */
    private val OBSOLETE_PREFIXES = listOf("quake_info_", "crisis_local_")
    private val OBSOLETE_IDS = listOf("quake_notice", "quake_updates", "quake_notice_chime_always", "quake_updates_headsup")

    const val ID_SERVICE = 1
    const val ID_STATUS = 2
    const val ID_LOCAL_QUAKE = 100

    fun quakeChannel(ctx: Context) = current(ctx, CH_QUAKE_BASE)

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun current(ctx: Context, base: String): String {
        val gen = Storage.of(ctx).getSharedPreferences("channels", Context.MODE_PRIVATE).getInt(base, 1)
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
        nm.notificationChannels.filter { ch -> OBSOLETE_PREFIXES.any { ch.id.startsWith(it) } || ch.id in OBSOLETE_IDS }
            .forEach { nm.deleteNotificationChannel(it.id) }
        val chime = android.net.Uri.parse("android.resource://${ctx.packageName}/${R.raw.notice_chime}")
        val notifAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(NotificationChannel(CH_NOTICE, ctx.getString(R.string.ch_info), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.ch_info_desc)
            setSound(chime, notifAttrs)
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
        // The loud alert plays its own siren on the alarm stream; the channel itself only vibrates.
        // It is created once DND access is granted (or onboarding ends without it), because
        // only a new channel can be told to override Do Not Disturb.
        if (!hasDnd && !io.github.ljlies.earthping.Prefs(ctx).onboardingDone) return
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
        // Never recreated: Android would list the old one as "deleted" in the app's settings.
        // A channel made before DND access is fixed by the user instead (see Perms).
        val id = current(ctx, base)
        if (nm(ctx).getNotificationChannel(id) == null) nm(ctx).createNotificationChannel(make(id))
    }

    /** DND access is granted, but the warning channel was created without the DND override. */
    fun quakeChannelMissesDndBypass(ctx: Context): Boolean {
        val nm = nm(ctx)
        if (!nm.isNotificationPolicyAccessGranted) return false
        val ch = nm.getNotificationChannel(quakeChannel(ctx)) ?: return false
        return !ch.canBypassDnd()
    }

    /** The user hasn't silenced or blocked the "Earthquake nearby" channel. */
    fun noticeChannelAudible(ctx: Context): Boolean {
        val nm = nm(ctx)
        val ch = nm.getNotificationChannel(CH_NOTICE) ?: return false
        return nm.areNotificationsEnabled() && ch.importance >= NotificationManager.IMPORTANCE_DEFAULT
    }

    fun quakeChannelBypassesDnd(ctx: Context): Boolean = nm(ctx).getNotificationChannel(quakeChannel(ctx))?.canBypassDnd() == true
}
