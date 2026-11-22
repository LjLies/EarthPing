package io.github.luccoj.alertroid.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import io.github.luccoj.alertroid.EventLog
import io.github.luccoj.alertroid.Prefs
import io.github.luccoj.alertroid.alert.AlertCenter

/** Exact alarms: heartbeats, reconnects, service restarts and test alerts. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ConnectionController.ACTION_HEARTBEAT) EventLog.log("Alarm: ${action.substringAfterLast('.')}")
        // Hold the CPU long enough for a heartbeat round-trip or a reconnect.
        context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alertroid:alarm").acquire(30_000)
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

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (prefs.onboardingDone && prefs.serviceEnabled) {
            EventLog.log("Starting after ${intent.action}")
            ListenerService.start(context, "boot")
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
