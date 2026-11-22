package io.github.luccoj.alertroid.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import io.github.luccoj.alertroid.EventLog
import io.github.luccoj.alertroid.Prefs
import io.github.luccoj.alertroid.gcm.Checkin
import io.github.luccoj.alertroid.gcm.CheckinException
import io.github.luccoj.alertroid.gcm.Credentials
import io.github.luccoj.alertroid.gcm.DataMessage
import io.github.luccoj.alertroid.gcm.DeviceProfile
import io.github.luccoj.alertroid.gcm.LoginRejectedException
import io.github.luccoj.alertroid.gcm.McsClient
import java.io.IOException
import java.util.ArrayDeque
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Connection state shown in the UI and the foreground notification. */
object ServiceStatus {
    enum class State { STOPPED, NO_NETWORK, CONNECTING, CONNECTED, WAITING }

    @Volatile var state = State.STOPPED
    @Volatile var lastError: String? = null
    @Volatile var connectedSinceMs = 0L
    @Volatile var nextAttemptMs = 0L
    @Volatile var lastRxMs = 0L
    @Volatile var topics: List<String> = emptyList()
    @Volatile var androidId: Long = 0
    @Volatile var port = 0
    @Volatile var messages = 0
    @Volatile var reconnects = 0
    @Volatile var locationInfo: String? = null
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun changed() = listeners.forEach { try { it() } catch (_: Exception) {} }
}

/**
 * Keeps one MCS connection alive: connects, heartbeats via exact alarms (so it works in Doze),
 * detects dead links, and reconnects with exponential backoff plus a global hourly cap, so a
 * broken network or a rejecting server is never hammered.
 */
class ConnectionController(private val ctx: Context, private val onMessage: (DataMessage) -> Unit) : McsClient.Listener {

    companion object {
        const val ACTION_HEARTBEAT = "io.github.luccoj.alertroid.HEARTBEAT"
        const val ACTION_RECONNECT = "io.github.luccoj.alertroid.RECONNECT"
        private val BACKOFF_S = longArrayOf(2, 5, 10, 20, 40, 80, 160, 300, 600)
        private const val STABLE_SESSION_MS = 3 * 60_000L
        private const val MAX_ATTEMPTS_PER_HOUR = 30
        /** Cellular radios can take seconds to wake up from deep sleep: be lenient. */
        private const val HEARTBEAT_ACK_TIMEOUT_MS = 30_000L
        /** Learned heartbeat intervals never go below this. */
        private const val MIN_HEARTBEAT_MS = 2 * 60_000L
        private const val MAX_REGISTRATIONS_PER_DAY = 3
        private const val MIN_REGISTRATION_GAP_MS = 15 * 60_000L
    }

    private val prefs = Prefs(ctx)
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "mcs-control").apply { isDaemon = true } }
    private val alarms = ctx.getSystemService(AlarmManager::class.java)
    private val power = ctx.getSystemService(PowerManager::class.java)

    private var client: McsClient? = null
    private var connecting = false
    private var stopped = true
    private var networkAvailable = true
    private var failures = 0
    private var loginRejections = 0
    private var forceCheckin = false
    private var nextAttemptElapsed = 0L
    private val attempts = ArrayDeque<Long>()
    private var sessionStartElapsed = 0L
    private var expectedClose = false
    private var topics: List<String> = emptyList()
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var checkFuture: ScheduledFuture<*>? = null
    private val recentPids = ArrayList<String>()

    private fun now() = SystemClock.elapsedRealtime()

    private fun post(block: () -> Unit) {
        exec.execute {
            try { block() } catch (t: Throwable) { EventLog.log("Controller error: $t") }
        }
    }

    // ------------------------------------------------------------------ public API

    fun start(initialTopics: List<String>) = post {
        topics = initialTopics
        ServiceStatus.topics = topics
        stopped = false
        ensure("start")
    }

    fun stop() = post {
        stopped = true
        expectedClose = true
        client?.close()
        client = null
        cancelAlarm(ACTION_HEARTBEAT)
        cancelAlarm(ACTION_RECONNECT)
        setState(ServiceStatus.State.STOPPED)
    }

    fun setTopics(newTopics: List<String>) = post {
        if (newTopics == topics) return@post
        EventLog.log("Subscriptions changed: ${newTopics.joinToString()}")
        topics = newTopics
        ServiceStatus.topics = topics
        val c = client
        if (c != null && c.isOpen) {
            // Topics are only sent at login: reconnect, without counting it as a failure.
            expectedClose = true
            c.close()
        } else {
            ensure("topics")
        }
    }

    /** Transport of the current network ("wifi", "cellular", "other"): heartbeats are learned per kind. */
    private var networkKind = "other"

    fun onNetwork(available: Boolean, changed: Boolean, kind: String = networkKind) = post {
        networkAvailable = available
        if (available && kind != networkKind) {
            EventLog.log("Network: $kind (heartbeat every ${heartbeatIntervalMsFor(kind) / 1000} s)")
            networkKind = kind
        }
        if (!available) {
            EventLog.log("Network lost")
            client?.let { expectedClose = true; it.close() }
            setState(ServiceStatus.State.NO_NETWORK)
            return@post
        }
        if (changed && client?.isOpen == true) {
            EventLog.log("Default network changed: reconnecting")
            expectedClose = true
            client?.close()
            return@post
        }
        // A network coming back is not a failure: retry soon.
        if (client == null) {
            nextAttemptElapsed = minOf(nextAttemptElapsed, now() + 1000)
            failures = minOf(failures, 1)
        }
        ensure("network")
    }

    /** Called from the exact alarms (with a wake lock held by the receiver). */
    fun onAlarm(action: String) = post {
        when (action) {
            ACTION_HEARTBEAT -> heartbeat()
            else -> ensure("alarm")
        }
    }

    // ------------------------------------------------------------------ internals

    private fun setState(s: ServiceStatus.State) {
        ServiceStatus.state = s
        ServiceStatus.changed()
    }

    private fun heartbeat() {
        val c = client
        if (c == null || !c.isOpen) {
            ensure("heartbeat")
            return
        }
        val sentAt = now()
        val idleBefore = sentAt - c.lastRxElapsed
        try {
            c.sendHeartbeat()
        } catch (e: IOException) {
            EventLog.log("Heartbeat send failed: ${e.message}")
            c.close()
            return
        }
        // Keep the CPU up until the answer arrives (or the check below gives up).
        try {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alertroid:heartbeat").acquire(HEARTBEAT_ACK_TIMEOUT_MS + 5_000)
        } catch (_: Exception) {}
        val interval = heartbeatIntervalMs()
        checkFuture?.cancel(false)
        checkFuture = exec.schedule({
            if (client !== c || !c.isOpen) return@schedule
            if (c.lastRxElapsed < sentAt) {
                // Like microG: a missed answer means the network dropped an idle connection
                // sooner than we pinged, so ping more often on this kind of network.
                learn(shorter = true, interval)
                EventLog.log("No answer to heartbeat within ${HEARTBEAT_ACK_TIMEOUT_MS / 1000} s on $networkKind: connection is dead")
                c.close()
            } else {
                val rtt = c.lastRxElapsed - sentAt
                if (rtt > 5_000) EventLog.log("Slow heartbeat answer: ${rtt} ms")
                // The link survived a long idle period: try a slightly longer interval next time.
                if (idleBefore >= interval * 3 / 4) learn(shorter = false, interval)
            }
        }, HEARTBEAT_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        scheduleHeartbeat()
    }

    private fun learnedKey(kind: String) = "hb_learned_$kind"

    private fun heartbeatIntervalMsFor(kind: String): Long {
        val user = prefs.heartbeatMinutes * 60_000L
        val learned = prefs.raw.getLong(learnedKey(kind), user)
        return learned.coerceIn(minOf(MIN_HEARTBEAT_MS, user), user)
    }

    private fun learn(shorter: Boolean, current: Long) {
        val user = prefs.heartbeatMinutes * 60_000L
        val next = (if (shorter) current * 0.8 else current * 1.05).toLong().coerceIn(minOf(MIN_HEARTBEAT_MS, user), user)
        if (next != prefs.raw.getLong(learnedKey(networkKind), user)) {
            prefs.raw.edit().putLong(learnedKey(networkKind), next).apply()
            if (shorter || next == user) EventLog.log("Heartbeat on $networkKind now every ${next / 1000} s")
        }
    }

    private fun heartbeatIntervalMs(): Long {
        val user = heartbeatIntervalMsFor(networkKind)
        val server = client?.serverHeartbeatMs?.takeIf { it >= 60_000 }
        return if (server != null) minOf(user, server) else user
    }

    private fun scheduleHeartbeat() = setAlarm(ACTION_HEARTBEAT, now() + heartbeatIntervalMs())

    private fun ensure(reason: String) {
        if (stopped || connecting) return
        val c = client
        if (c != null && c.isOpen) return
        if (!networkAvailable) {
            setState(ServiceStatus.State.NO_NETWORK)
            return
        }
        val t = now()
        while (attempts.isNotEmpty() && t - attempts.first() > 3600_000L) attempts.removeFirst()
        if (attempts.size >= MAX_ATTEMPTS_PER_HOUR) {
            nextAttemptElapsed = maxOf(nextAttemptElapsed, attempts.first() + 3600_000L)
        }
        if (t < nextAttemptElapsed) {
            scheduleReconnect(nextAttemptElapsed - t)
            return
        }
        connect(reason)
    }

    private fun scheduleReconnect(delayMs: Long) {
        reconnectFuture?.cancel(false)
        reconnectFuture = exec.schedule({ ensure("backoff") }, delayMs, TimeUnit.MILLISECONDS)
        // Executor timers stop while the CPU sleeps: an alarm guarantees the retry happens.
        setAlarm(ACTION_RECONNECT, now() + delayMs)
        if (delayMs <= 30_000) {
            // Keep the CPU awake for short waits so a quick retry is not stretched by suspend.
            try {
                power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alertroid:reconnect").acquire(delayMs + 20_000)
            } catch (_: Exception) {}
        }
        ServiceStatus.nextAttemptMs = System.currentTimeMillis() + delayMs
        setState(ServiceStatus.State.WAITING)
    }

    private fun backoffMs(): Long {
        val base = BACKOFF_S[(failures - 1).coerceIn(0, BACKOFF_S.size - 1)] * 1000
        return (base * (0.8 + 0.4 * Random.nextDouble())).toLong()
    }

    private fun profile(): DeviceProfile {
        val locale = java.util.Locale.getDefault().toString()
        val tz = TimeZone.getDefault().id
        if (prefs.useGenericProfile) return DeviceProfile.generic(locale, tz)
        return DeviceProfile(
            fingerprint = Build.FINGERPRINT, product = Build.PRODUCT, brand = Build.BRAND,
            radio = Build.getRadioVersion() ?: "", bootloader = Build.BOOTLOADER, device = Build.DEVICE,
            sdkInt = Build.VERSION.SDK_INT, model = Build.MODEL, manufacturer = Build.MANUFACTURER,
            buildTimeS = Build.TIME / 1000, locale = locale, timeZone = tz,
        )
    }

    private fun credentials(): Credentials {
        var creds = prefs.credentials
        val wall = System.currentTimeMillis()
        if (creds == null) {
            val regs = prefs.registrationTimes.filter { wall - it < 86400_000L }
            if (regs.size >= MAX_REGISTRATIONS_PER_DAY || (regs.isNotEmpty() && wall - regs.last() < MIN_REGISTRATION_GAP_MS)) {
                throw IOException("Identity registration rate-limited; will retry later")
            }
            prefs.registrationTimes = regs + wall
            EventLog.log("Registering a new anonymous push identity (checkin)")
            creds = try {
                Checkin.run(profile(), null)
            } catch (e: CheckinException) {
                if (e.httpCode in 400..499 && !prefs.useGenericProfile) {
                    EventLog.log("Checkin with this device's profile refused (${e.httpCode}); using a generic profile")
                    prefs.useGenericProfile = true
                    Checkin.run(profile(), null)
                } else throw e
            }
            prefs.credentials = creds
            EventLog.log("Registered identity ${java.lang.Long.toHexString(creds.androidId)}")
        } else if (forceCheckin || wall - creds.lastCheckinMs > creds.checkinIntervalMs) {
            try {
                creds = Checkin.run(profile(), creds)
                prefs.credentials = creds
                forceCheckin = false
                EventLog.log("Periodic checkin done")
            } catch (e: Exception) {
                EventLog.log("Checkin refresh failed (${e.message}); keeping identity")
            }
        }
        Checkin.lastServerTimeOffsetMs?.let { updateClock(it) }
        return creds
    }

    private fun updateClock(offsetMs: Long) {
        if (kotlin.math.abs(offsetMs) < 3600_000L) prefs.clockOffsetMs = offsetMs
    }

    private fun connect(reason: String) {
        connecting = true
        attempts.addLast(now())
        setState(ServiceStatus.State.CONNECTING)
        var c: McsClient? = null
        try {
            val creds = credentials()
            ServiceStatus.androidId = creds.androidId
            c = McsClient(creds, topics, heartbeatIntervalMs(), profile().sdkInt, prefs.pendingPersistentIds, this)
            client = c
            c.connect()
            if (client !== c) throw IOException("Superseded")
            prefs.pendingPersistentIds = emptyList()
            sessionStartElapsed = now()
            loginRejections = 0
            c.serverTimeOffsetMs?.let { updateClock(it) }
            ServiceStatus.connectedSinceMs = System.currentTimeMillis()
            ServiceStatus.lastRxMs = System.currentTimeMillis()
            ServiceStatus.port = c.port
            ServiceStatus.lastError = null
            EventLog.log("Connected to ${McsClient.HOST}:${c.port} ($reason), topics: ${topics.joinToString()}")
            setState(ServiceStatus.State.CONNECTED)
            scheduleHeartbeat()
            cancelAlarm(ACTION_RECONNECT)
        } catch (e: Exception) {
            c?.close()
            if (client === c) client = null
            failures++
            ServiceStatus.lastError = e.message ?: e.javaClass.simpleName
            if (e is LoginRejectedException) {
                loginRejections++
                if (loginRejections == 1) {
                    forceCheckin = true
                } else {
                    EventLog.log("Login rejected again: a new identity will be registered")
                    prefs.credentials = null
                    loginRejections = 0
                }
            }
            val delay = backoffMs()
            nextAttemptElapsed = now() + delay
            EventLog.log("Connection failed (${ServiceStatus.lastError}); retry in ${delay / 1000} s")
            scheduleReconnect(delay)
        } finally {
            connecting = false
        }
    }

    // ------------------------------------------------------------------ McsClient.Listener

    override fun onFrame(client: McsClient) {
        ServiceStatus.lastRxMs = System.currentTimeMillis()
    }

    override fun onDataMessage(client: McsClient, msg: DataMessage) {
        ServiceStatus.messages++
        msg.persistentId?.let { pid ->
            synchronized(recentPids) {
                recentPids += pid
                while (recentPids.size > 20) recentPids.removeAt(0)
            }
        }
        onMessage(msg)
    }

    override fun onDisconnected(client: McsClient, error: Throwable?) = post {
        if (this.client !== client) return@post
        this.client = null
        checkFuture?.cancel(false)
        ServiceStatus.reconnects++
        // Ids received in this session go back in the next LoginRequest, so nothing is re-sent.
        synchronized(recentPids) { prefs.pendingPersistentIds = recentPids.toList() }
        if (stopped) return@post
        val lived = now() - sessionStartElapsed
        if (expectedClose) {
            expectedClose = false
            nextAttemptElapsed = maxOf(nextAttemptElapsed, now() + 1000)
        } else {
            if (lived >= STABLE_SESSION_MS) failures = 0
            failures++
            val delay = backoffMs()
            nextAttemptElapsed = now() + delay
            ServiceStatus.lastError = error?.message ?: "Disconnected"
            EventLog.log("Disconnected (${ServiceStatus.lastError}) after ${lived / 1000} s; retry in ${delay / 1000} s")
        }
        ensure("reconnect")
    }

    // ------------------------------------------------------------------ alarms

    private fun pending(action: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(ctx, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setAlarm(action: String, atElapsed: Long) {
        val pi = pending(action)
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, pi)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, pi)
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, pi)
        }
    }

    private fun cancelAlarm(action: String) = alarms.cancel(pending(action))
}
