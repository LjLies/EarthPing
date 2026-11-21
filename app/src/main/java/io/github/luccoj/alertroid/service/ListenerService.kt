package io.github.luccoj.alertroid.service

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
import io.github.luccoj.alertroid.EventLog
import io.github.luccoj.alertroid.Places
import io.github.luccoj.alertroid.Prefs
import io.github.luccoj.alertroid.R
import io.github.luccoj.alertroid.alert.AlertCenter
import io.github.luccoj.alertroid.alert.Notifications
import io.github.luccoj.alertroid.location.LocationTracker
import io.github.luccoj.alertroid.ui.MainActivity

/**
 * The always-on foreground service: owns the MCS connection, follows location and network
 * changes, and keeps the alerting machinery (TTS, siren) warm.
 */
class ListenerService : Service() {

    companion object {
        @Volatile var instance: ListenerService? = null
            private set

        const val ACTION_TEST_SCHEDULE = "io.github.luccoj.alertroid.TEST_SCHEDULE"
        const val ACTION_TEST_FIRE = "io.github.luccoj.alertroid.TEST_FIRE"
        const val ACTION_REFRESH = "io.github.luccoj.alertroid.REFRESH"
        const val ACTION_RESTART = "io.github.luccoj.alertroid.RESTART"

        fun start(ctx: Context, why: String, action: String? = null) {
            val i = Intent(ctx, ListenerService::class.java).setAction(action).putExtra("why", why)
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                EventLog.log("Cannot start service ($why): $e")
            }
        }

        /** Settings, places or permissions changed. */
        fun refresh(ctx: Context) {
            if (Prefs(ctx).serviceEnabled) start(ctx, "refresh", ACTION_REFRESH)
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

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Notifications.ensureChannels(this)
        goForeground()
        instance = this
        EventLog.log("Service started")
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (!prefs.serviceEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_TEST_SCHEDULE -> scheduleTest()
            ACTION_REFRESH -> {
                Notifications.ensureChannels(this)
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
        if (coarse && (bg || io.github.luccoj.alertroid.ui.AppVisibility.visible)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
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
                controller.onNetwork(available = true, changed = previous != null && previous != network)
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

    private fun scheduleTest() {
        val delayMs = prefs.testDelayS.coerceIn(0, 3600) * 1000L
        EventLog.log("Test alert scheduled in ${delayMs / 1000} s")
        val am = getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(this, 77, Intent(this, AlarmReceiver::class.java).setAction(ACTION_TEST_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = SystemClock.elapsedRealtime() + delayMs
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            main.postDelayed({ AlertCenter.fireTest() }, delayMs)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        scheduleRestart(2_000)
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!prefs.serviceEnabled) return
        val am = getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(this, 78, Intent(this, AlarmReceiver::class.java).setAction(ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi)
    }

    override fun onDestroy() {
        instance = null
        ServiceStatus.removeListener(statusListener)
        main.removeCallbacksAndMessages(null)
        location.stop()
        netCallback?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        controller.stop()
        EventLog.log("Service stopped")
        scheduleRestart(5_000)
        super.onDestroy()
    }
}
