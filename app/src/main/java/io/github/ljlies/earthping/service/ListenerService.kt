package io.github.ljlies.earthping.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import io.github.ljlies.earthping.EventLog
import io.github.ljlies.earthping.Places
import io.github.ljlies.earthping.Prefs
import io.github.ljlies.earthping.R
import io.github.ljlies.earthping.alert.AlertCenter
import io.github.ljlies.earthping.alert.Notifications
import io.github.ljlies.earthping.location.LocationTracker
import io.github.ljlies.earthping.ui.MainActivity

/**
 * The always-on foreground service: owns the MCS connection, follows location and network
 * changes, and keeps the alerting machinery (TTS, siren) warm.
 */
class ListenerService : Service() {

    companion object {
        @Volatile var instance: ListenerService? = null
            private set

        const val ACTION_TEST_SCHEDULE = "io.github.ljlies.earthping.TEST_SCHEDULE"
        const val ACTION_TEST_FIRE = "io.github.ljlies.earthping.TEST_FIRE"
        const val ACTION_REFRESH = "io.github.ljlies.earthping.REFRESH"
        const val ACTION_RESTART = "io.github.ljlies.earthping.RESTART"
        const val ACTION_UNLOCKED = "io.github.ljlies.earthping.UNLOCKED"

        fun start(ctx: Context, why: String, action: String? = null) {
            val i = Intent(ctx, ListenerService::class.java).setAction(action).putExtra("why", why)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                EventLog.log("Cannot start service ($why): $e")
            }
        }

        /**
         * Brings the service back after it was stopped, its task swiped away or the process
         * crashed. Exact alarms are not deferred by Doze, and they may start a foreground service.
         */
        fun scheduleRestart(ctx: Context, delayMs: Long) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(ctx, 78, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_RESTART),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val at = SystemClock.elapsedRealtime() + delayMs
            try {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        }

        /** Settings, places or permissions changed. */
        fun refresh(ctx: Context) {
            if (Prefs(ctx).serviceEnabled) start(ctx, "refresh", ACTION_REFRESH)
        }

        /** Test alerts work whether or not the service runs (alerts may be turned off). */
        fun scheduleTest(ctx: Context) {
            val prefs = Prefs(ctx)
            val delayMs = prefs.testDelayS.coerceIn(0, 3600) * 1000L
            EventLog.log("Test alert scheduled in ${delayMs / 1000} s")
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(ctx, 77, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_TEST_FIRE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val at = SystemClock.elapsedRealtime() + delayMs
            try {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } catch (e: SecurityException) {
                EventLog.log("Exact alarm refused ($e); using a timer")
                AlertCenter.init(ctx)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ AlertCenter.fireTest() }, delayMs)
            }
        }
    }

    lateinit var controller: ConnectionController
        private set
    private lateinit var prefs: Prefs
    private lateinit var location: LocationTracker
    private val main = Handler(Looper.getMainLooper())
    private var currentNetwork: Network? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var lastTopicChangeElapsed = 0L
    private var locationGranted = false

    override fun onBind(intent: Intent?): IBinder? = null

    private var running = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Notifications.ensureChannels(this)
        goForeground()
        if (!prefs.serviceEnabled) {
            // Started while alerts are off (e.g. by an old alarm): leave without connecting.
            EventLog.log("Service start ignored: alerts are turned off")
            stopSelf()
            return
        }
        running = true
        instance = this
        EventLog.log("Service started${if (io.github.ljlies.earthping.Storage.unlocked(this)) "" else " before the first unlock"} (Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, app ${io.github.ljlies.earthping.BuildConfig.VERSION_NAME})")
        logPermissions()
        checkFullScreenPermission()
        AlertCenter.init(this)
        controller = ConnectionController(this) { AlertCenter.onDataMessage(it) }
        location = LocationTracker(this) { onLocation(it) }
        location.start()
        locationGranted = location.hasCoarse
        registerNetwork()
        controller.start(Places.subscriptions(this).map { it.topic })
        WatchdogJob.schedule(this)
        ServiceStatus.addListener(statusListener)
        updateNotification()
    }

    private val statusListener: () -> Unit = { main.post { updateNotification() } }

    /**
     * Android 14+ can withdraw "full-screen notifications" from apps that aren't calling or
     * alarm-clock apps, e.g. when they are updated. Say so, with a direct way to re-allow it.
     */
    private fun checkFullScreenPermission() {
        if (Build.VERSION.SDK_INT < 34) return
        val nm = getSystemService(NotificationManager::class.java)
        main.removeCallbacks(fsiRecheck)
        if (nm.canUseFullScreenIntent()) {
            unregisterScreenOn()
            if (fsiNoticeShown) EventLog.log("Full-screen notifications allowed again")
            fsiNoticeShown = false
            nm.cancel(Notifications.ID_STATUS + 1)
            return
        }
        // Android sends no broadcast when this is granted. Look again a few times soon after the
        // notice (the user may be granting it right now), then only when the screen comes on.
        if (fsiChecksLeft > 0) { fsiChecksLeft--; main.postDelayed(fsiRecheck, 60_000) }
        registerScreenOn()
        if (fsiNoticeShown) return
        fsiChecksLeft = 10
        fsiNoticeShown = true
        EventLog.log("Full-screen notifications are not allowed: asking the user to re-allow them")
        val fix = PendingIntent.getActivity(this, 20,
            Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:$packageName")),
            PendingIntent.FLAG_IMMUTABLE)
        nm.notify(Notifications.ID_STATUS + 1, Notification.Builder(this, Notifications.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_quake)
            .setContentTitle(getString(R.string.fsi_revoked_title))
            .setContentText(getString(R.string.fsi_revoked_text))
            .setStyle(Notification.BigTextStyle().bigText(getString(R.string.fsi_revoked_text)))
            .setContentIntent(fix)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build())
    }

    private var fsiNoticeShown = false
    private var fsiChecksLeft = 0
    private val fsiRecheck = Runnable { checkFullScreenPermission() }
    private var screenOnReceiver: android.content.BroadcastReceiver? = null

    private fun registerScreenOn() {
        if (screenOnReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = checkFullScreenPermission()
        }
        registerReceiver(r, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        })
        screenOnReceiver = r
    }

    private fun unregisterScreenOn() {
        screenOnReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        screenOnReceiver = null
    }

    /** One line with the state of everything the app depends on, for the diagnostic log. */
    private fun logPermissions() {
        val states = io.github.ljlies.earthping.ui.Perms.applicable(this).joinToString(", ") { "${it.key}=${if (it.granted(this)) "yes" else "NO"}" }
        val am = getSystemService(android.media.AudioManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        EventLog.log("Permissions: $states; ringer=${am.ringerMode}, alarm volume=${am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)}/" +
            "${am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM)}, DND filter=${nm.currentInterruptionFilter}, " +
            "quake channel bypasses DND=${Notifications.quakeChannelBypassesDnd(this)}, turn screen on=${turnScreenOnState()}")
    }

    /** Android 14+ "Turn screen on" special access, which waking the screen for a warning needs. */
    private fun turnScreenOnState(): String = try {
        if (Build.VERSION.SDK_INT < 34) "n/a" else when (getSystemService(android.app.AppOpsManager::class.java)
            .unsafeCheckOpNoThrow("android:turn_screen_on", android.os.Process.myUid(), packageName)) {
            android.app.AppOpsManager.MODE_ALLOWED, android.app.AppOpsManager.MODE_DEFAULT -> "yes"
            else -> "NO"
        }
    } catch (e: Exception) { "?" }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (!running || !prefs.serviceEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra("why")?.takeIf { it != "refresh" }?.let { EventLog.log("Service command: ${intent.action ?: "start"} ($it)") }
        when (intent?.action) {
            ACTION_TEST_SCHEDULE -> scheduleTest(this)
            ACTION_UNLOCKED -> {
                // Started before the first unlock: the TTS engine and the network location
                // provider may only have become available now.
                if (!AlertCenter.speaker.available) AlertCenter.speaker.warmUp()
                location.restart()
                controller.onAlarm(ConnectionController.ACTION_RECONNECT)
            }
            ACTION_REFRESH -> {
                Notifications.ensureChannels(this)
                checkFullScreenPermission()
                if (!AlertCenter.speaker.available) AlertCenter.speaker.warmUp()
                if (location.hasCoarse != locationGranted) { locationGranted = location.hasCoarse; location.restart() }
                refreshTopics(force = true)
                goForeground()
            }
            else -> controller.onAlarm(ConnectionController.ACTION_RECONNECT)
        }
        return START_STICKY
    }

    private fun foregroundTypes(): Int {
        if (Build.VERSION.SDK_INT < 34) return ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (coarse && (bg || io.github.ljlies.earthping.ui.AppVisibility.visible)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return types
    }

    private fun goForeground() {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(Notifications.ID_SERVICE, n, foregroundTypes())
            else startForeground(Notifications.ID_SERVICE, n)
        } catch (e: Exception) {
            EventLog.log("startForeground with location type failed ($e); retrying without it")
            try {
                if (Build.VERSION.SDK_INT >= 34) startForeground(Notifications.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(Notifications.ID_SERVICE, n)
            } catch (e2: Exception) {
                EventLog.log("startForeground failed: $e2")
            }
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = when (ServiceStatus.state) {
            ServiceStatus.State.CONNECTED -> getString(R.string.fg_connected, ServiceStatus.topics.size)
            ServiceStatus.State.CONNECTING -> getString(R.string.fg_connecting)
            ServiceStatus.State.NO_NETWORK -> getString(R.string.fg_no_network)
            ServiceStatus.State.WAITING -> getString(R.string.fg_waiting)
            ServiceStatus.State.STOPPED -> getString(R.string.fg_starting)
        }
        return Notification.Builder(this, Notifications.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_quake)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private var lastNotifiedState: ServiceStatus.State? = null
    private var disconnectedSince = 0L

    private fun updateNotification() {
        val s = ServiceStatus.state
        if (s == lastNotifiedState) return
        lastNotifiedState = s
        getSystemService(NotificationManager::class.java).notify(Notifications.ID_SERVICE, buildNotification())
        // Tell the user (once) if alerts have been unreachable for a long time.
        if (s == ServiceStatus.State.CONNECTED) {
            disconnectedSince = 0
            getSystemService(NotificationManager::class.java).cancel(Notifications.ID_STATUS)
        } else if (disconnectedSince == 0L) {
            disconnectedSince = SystemClock.elapsedRealtime()
            main.postDelayed(disconnectedCheck, 15 * 60_000L)
        }
    }

    private val disconnectedCheck = Runnable {
        if (ServiceStatus.state != ServiceStatus.State.CONNECTED && disconnectedSince != 0L) {
            val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            getSystemService(NotificationManager::class.java).notify(Notifications.ID_STATUS,
                Notification.Builder(this, Notifications.CH_STATUS)
                    .setSmallIcon(R.drawable.ic_stat_quake)
                    .setContentTitle(getString(R.string.status_disconnected_title))
                    .setContentText(ServiceStatus.lastError ?: getString(R.string.fg_waiting))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build())
        }
    }

    private fun onLocation(l: Location) {
        val src = l.provider ?: "?"
        prefs.setLastLocation(l.latitude, l.longitude, src, System.currentTimeMillis())
        ServiceStatus.locationInfo = String.format(java.util.Locale.US, "%.3f, %.3f (%s)", l.latitude, l.longitude, src)
        refreshTopics(force = false)
    }

    private fun refreshTopics(force: Boolean) {
        val topics = Places.subscriptions(this).map { it.topic }
        if (topics == ServiceStatus.topics) return
        // Moving between cells: don't churn the connection more than once a minute.
        val t = SystemClock.elapsedRealtime()
        val wait = 60_000L - (t - lastTopicChangeElapsed)
        if (!force && wait > 0 && lastTopicChangeElapsed != 0L) {
            main.removeCallbacks(deferredRefresh)
            main.postDelayed(deferredRefresh, wait)
            return
        }
        lastTopicChangeElapsed = t
        controller.setTopics(topics)
    }

    private val deferredRefresh = Runnable { refreshTopics(force = true) }

    private fun registerNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = currentNetwork
                currentNetwork = network
                val caps = try { getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network) } catch (e: Exception) { null }
                val kind = when {
                    caps == null -> "other"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
                controller.onNetwork(available = true, changed = previous != null && previous != network, kind = kind)
            }

            override fun onLost(network: Network) {
                if (network == currentNetwork) {
                    currentNetwork = null
                    controller.onNetwork(available = false, changed = false)
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            EventLog.log("Network callback failed: $e")
        }
    }



    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!running) return
        super.onTaskRemoved(rootIntent)
        if (prefs.serviceEnabled) scheduleRestart(this, 2_000)
    }

    override fun onDestroy() {
        if (!running) { super.onDestroy(); return }
        running = false
        instance = null
        unregisterScreenOn()
        ServiceStatus.removeListener(statusListener)
        main.removeCallbacksAndMessages(null)
        location.stop()
        netCallback?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        controller.stop()
        EventLog.log("Service stopped")
        if (prefs.serviceEnabled) scheduleRestart(this, 5_000)
        super.onDestroy()
    }
}
