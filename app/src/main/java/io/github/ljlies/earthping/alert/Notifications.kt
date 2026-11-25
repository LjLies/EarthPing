package io.github.ljlies.earthping.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import io.github.ljlies.earthping.Prefs
import io.github.ljlies.earthping.R

/**
 * Notification channels, in three groups: earthquakes, other public alerts, and the app's own
 * status. Which alerts notify, and how, is configured in Android's settings for each channel.
 *
 * Channels are never deleted and recreated (Android lists deleted channels in the app's
 * settings), so the only thing that depends on when one is created is the warning channel's
 * Do Not Disturb override: Android grants it only to a channel created while the app holds DND
 * access. That channel is therefore created once access is granted, or when onboarding ends
 * without it; if access comes later, the user switches the override on (see Perms).
 */
object Notifications {
    private const val GROUP_QUAKES = "quakes"
    private const val GROUP_PUBLIC = "public"
    private const val GROUP_STATUS = "status"

    const val CH_SERVICE = "service"
    const val CH_STATUS = "status"
    /** The loud warning for my location. The siren is played by the app; the channel only vibrates. */
    const val CH_QUAKE = "quake_warning"
    /**
     * Earthquakes below the loud-alarm threshold, and updates after a warning was closed.
     * "Also in silent mode" is done by the app playing the chime itself (AlertCenter).
     */
    const val CH_NOTICE = "quake_notice_chime"
    /** While the full-screen warning is on screen: silent, so no heads-up covers it. */
    const val CH_ALERT_SHOWN = "alert_on_screen"
    const val CH_WATCHED = "watched"
    /** Public alerts (floods, storms…) for my location and for watched places. */
    const val CH_CRISIS = "crisis"
    const val CH_WATCHED_PUBLIC = "watched_public"

    /** Channels of older versions that are no longer used. */
    private val OBSOLETE_PREFIXES = listOf("quake_info_", "crisis_local_", "quake_local_")
    private val OBSOLETE_IDS = listOf("quake_notice", "quake_updates", "quake_notice_chime_always", "quake_updates_headsup")

    const val ID_SERVICE = 1
    const val ID_STATUS = 2
    const val ID_LOCAL_QUAKE = 100

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun ensureChannels(ctx: Context) {
        val nm = nm(ctx)
        nm.createNotificationChannelGroups(listOf(
            NotificationChannelGroup(GROUP_QUAKES, ctx.getString(R.string.group_quakes)),
            NotificationChannelGroup(GROUP_PUBLIC, ctx.getString(R.string.group_public)),
            NotificationChannelGroup(GROUP_STATUS, ctx.getString(R.string.group_status)),
        ))
        nm.notificationChannels.filter { ch -> OBSOLETE_PREFIXES.any { ch.id.startsWith(it) } || ch.id in OBSOLETE_IDS }
            .forEach { nm.deleteNotificationChannel(it.id) }

        val chime = android.net.Uri.parse("android.resource://${ctx.packageName}/${R.raw.notice_chime}")
        val notifAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        fun create(id: String, group: String, name: Int, desc: Int, importance: Int, setup: NotificationChannel.() -> Unit = {}) =
            nm.createNotificationChannel(NotificationChannel(id, ctx.getString(name), importance).apply {
                description = ctx.getString(desc)
                this.group = group
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setup()
            })

        // Earthquakes
        val hasDnd = nm.isNotificationPolicyAccessGranted
        if (hasDnd || Prefs(ctx).onboardingDone) {
            create(CH_QUAKE, GROUP_QUAKES, R.string.ch_quake, R.string.ch_quake_desc, NotificationManager.IMPORTANCE_HIGH) {
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 300, 800, 300, 800)
                setBypassDnd(true) // honoured only with DND access, and only for a new channel
            }
        }
        create(CH_ALERT_SHOWN, GROUP_QUAKES, R.string.ch_alert_shown, R.string.ch_alert_shown_desc, NotificationManager.IMPORTANCE_LOW) {
            setSound(null, null)
            enableVibration(false)
        }
        create(CH_NOTICE, GROUP_QUAKES, R.string.ch_info, R.string.ch_info_desc, NotificationManager.IMPORTANCE_HIGH) {
            setSound(chime, notifAttrs)
            enableVibration(true)
        }
        create(CH_WATCHED, GROUP_QUAKES, R.string.ch_watched, R.string.ch_watched_desc, NotificationManager.IMPORTANCE_DEFAULT)

        // Other public alerts
        create(CH_CRISIS, GROUP_PUBLIC, R.string.ch_crisis, R.string.ch_crisis_desc, NotificationManager.IMPORTANCE_HIGH) {
            setSound(defaultSound, notifAttrs)
            enableVibration(true)
        }
        create(CH_WATCHED_PUBLIC, GROUP_PUBLIC, R.string.ch_watched_public, R.string.ch_watched_public_desc, NotificationManager.IMPORTANCE_DEFAULT)

        // The app itself
        create(CH_SERVICE, GROUP_STATUS, R.string.ch_service, R.string.ch_service_desc, NotificationManager.IMPORTANCE_MIN) {
            setShowBadge(false)
        }
        create(CH_STATUS, GROUP_STATUS, R.string.ch_status, R.string.ch_status_desc, NotificationManager.IMPORTANCE_DEFAULT)
    }

    private fun channel(ctx: Context, id: String): NotificationChannel? = nm(ctx).getNotificationChannel(id)

    fun quakeChannelBypassesDnd(ctx: Context): Boolean = channel(ctx, CH_QUAKE)?.canBypassDnd() == true

    /** DND access is granted, but the warning channel was created without the DND override. */
    fun quakeChannelMissesDndBypass(ctx: Context): Boolean =
        nm(ctx).isNotificationPolicyAccessGranted && channel(ctx, CH_QUAKE)?.canBypassDnd() == false

    /** The user hasn't silenced or blocked the "Earthquake nearby" channel. */
    fun noticeChannelAudible(ctx: Context): Boolean =
        nm(ctx).areNotificationsEnabled() && (channel(ctx, CH_NOTICE)?.importance ?: 0) >= NotificationManager.IMPORTANCE_DEFAULT
}
