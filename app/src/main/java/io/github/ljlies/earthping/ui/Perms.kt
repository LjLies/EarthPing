package io.github.ljlies.earthping.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import io.github.ljlies.earthping.R
import io.github.ljlies.earthping.alert.Notifications

/** Every permission or system setting the app depends on, with how to obtain it. */
class PermItem(
    val key: String,
    val title: Int,
    val why: Int,
    val required: Boolean,
    val applies: (Context) -> Boolean = { true },
    val granted: (Context) -> Boolean,
    val request: (Activity) -> Unit,
)

object Perms {
    const val REQ_NOTIF = 1
    const val REQ_LOC = 2
    const val REQ_BG_LOC = 3

    private fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun open(a: Activity, intent: Intent) {
        try {
            a.startActivity(intent)
        } catch (e: Exception) {
            try {
                a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("BatteryLife")
    val items: List<PermItem> = listOf(
        PermItem("notifications", R.string.perm_notif, R.string.perm_notif_why, required = true,
            applies = { _ -> Build.VERSION.SDK_INT >= 33 },
            granted = { c -> Build.VERSION.SDK_INT < 33 || has(c, Manifest.permission.POST_NOTIFICATIONS) },
            request = { a -> if (Build.VERSION.SDK_INT >= 33) a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF) }),
        PermItem("location", R.string.perm_loc, R.string.perm_loc_why, required = true,
            granted = { c -> has(c, Manifest.permission.ACCESS_COARSE_LOCATION) && has(c, Manifest.permission.ACCESS_FINE_LOCATION) },
            request = { a -> a.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOC) }),
        PermItem("bg_location", R.string.perm_bgloc, R.string.perm_bgloc_why, required = false,
            applies = { _ -> Build.VERSION.SDK_INT >= 29 },
            granted = { c -> Build.VERSION.SDK_INT < 29 || has(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
            request = { a ->
                if (Build.VERSION.SDK_INT >= 29) {
                    if (!has(a, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                        a.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOC)
                    } else {
                        a.requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG_LOC)
                    }
                }
            }),
        PermItem("battery", R.string.perm_battery, R.string.perm_battery_why, required = true,
            granted = { c -> c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName) },
            request = { a -> open(a, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))) }),
        PermItem("background_data", R.string.perm_data, R.string.perm_data_why, required = true,
            granted = { c -> c.getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED },
            request = { a -> open(a, Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, Uri.parse("package:${a.packageName}"))) }),
        PermItem("dnd", R.string.perm_dnd, R.string.perm_dnd_why, required = false,
            granted = { c -> c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted },
            request = { a -> open(a, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }),
        // The warning channel can only be told to override DND when it is created. If DND access
        // came later, the user switches it on in the channel's settings (the app can't).
        PermItem("dnd_channel", R.string.perm_dnd_channel, R.string.perm_dnd_channel_why, required = false,
            applies = { c -> Notifications.quakeChannelMissesDndBypass(c) },
            granted = { c -> !Notifications.quakeChannelMissesDndBypass(c) },
            request = { a ->
                com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                    .setTitle(R.string.perm_dnd_channel)
                    .setMessage(R.string.perm_dnd_channel_steps)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        open(a, Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.quakeChannel(a)))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }),
        PermItem("fullscreen", R.string.perm_fullscreen, R.string.perm_fullscreen_why, required = false,
            applies = { _ -> Build.VERSION.SDK_INT >= 34 },
            granted = { c -> Build.VERSION.SDK_INT < 34 || c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() },
            request = { a -> if (Build.VERSION.SDK_INT >= 34) open(a, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${a.packageName}"))) }),
        PermItem("overlay", R.string.perm_overlay, R.string.perm_overlay_why, required = false,
            granted = { c -> Settings.canDrawOverlays(c) },
            request = { a -> open(a, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}"))) }),
        PermItem("exact_alarms", R.string.perm_alarms, R.string.perm_alarms_why, required = false,
            applies = { _ -> Build.VERSION.SDK_INT >= 31 },
            granted = { c -> Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() },
            request = { a -> if (Build.VERSION.SDK_INT >= 31) open(a, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${a.packageName}"))) }),
    )

    fun applicable(c: Context) = items.filter { it.applies(c) }
    fun missing(c: Context) = applicable(c).filter { !it.granted(c) }
    fun missingRequired(c: Context) = missing(c).filter { it.required }
}
