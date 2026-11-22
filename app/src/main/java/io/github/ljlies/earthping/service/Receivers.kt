package io.github.ljlies.earthping.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import io.github.ljlies.earthping.EventLog
import io.github.ljlies.earthping.Prefs
import io.github.ljlies.earthping.Storage
import io.github.ljlies.earthping.alert.AlertCenter

/** Exact alarms: heartbeats, reconnects, service restarts and test alerts. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ConnectionController.ACTION_HEARTBEAT) EventLog.log("Alarm: ${action.substringAfterLast('.')}")
        // Hold the CPU long enough for a heartbeat round-trip or a reconnect.
        context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "earthping:alarm").acquire(30_000)
        when (action) {
            ListenerService.ACTION_TEST_FIRE -> {
                AlertCenter.init(context)
                AlertCenter.fireTest()
            }
            else -> {
                val svc = ListenerService.instance
                if (svc != null) svc.controller.onAlarm(action)
                else if (Prefs(context).serviceEnabled) ListenerService.start(context, "alarm")
            }
        }
    }
}

/**
 * Starts the service at boot (already before the first unlock, thanks to direct boot), after
 * app updates and when the app is unsuspended.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        val action = intent.action?.substringAfterLast('.') ?: "?"
        if (prefs.onboardingDone && prefs.serviceEnabled) {
            if (ListenerService.instance != null) {
                // Typically BOOT_COMPLETED after the phone was unlocked, the service having
                // started at LOCKED_BOOT_COMPLETED.
                EventLog.log("$action: service already running")
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) ListenerService.start(context, "unlocked", ListenerService.ACTION_UNLOCKED)
            } else {
                val locked = if (Storage.unlocked(context)) "" else " (phone not unlocked yet)"
                EventLog.log("Starting after $action$locked")
                ListenerService.start(context, "boot")
            }
        }
        WatchdogJob.schedule(context)
    }
}

/** Every 15 minutes: make sure the service is running (it may have been killed). */
class WatchdogJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val prefs = Prefs(this)
        if (prefs.onboardingDone && prefs.serviceEnabled) {
            val svc = ListenerService.instance
            if (svc == null) {
                EventLog.log("Watchdog: service was not running, restarting")
                ListenerService.start(this, "watchdog")
            } else {
                svc.controller.onAlarm(ConnectionController.ACTION_RECONNECT)
            }
        }
        return false
    }

    override fun onStopJob(params: JobParameters?) = false

    companion object {
        private const val JOB_ID = 4242
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, WatchdogJob::class.java))
                .setPeriodic(15 * 60_000L)
                .setPersisted(true)
                .build())
        }
    }
}
