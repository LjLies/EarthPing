package io.github.ljlies.earthping

import android.app.Application
import io.github.ljlies.earthping.service.ListenerService
import io.github.ljlies.earthping.service.WatchdogJob
import io.github.ljlies.earthping.ui.AppVisibility
import org.osmdroid.config.Configuration
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val moved = Storage.migrate(this)
        EventLog.init(this)
        if (moved.isNotEmpty()) EventLog.log("Moved to device storage, for alerts before the first unlock: ${moved.joinToString()}")
        installCrashHandler()
        AppVisibility.register(this)
        val prefs = Prefs(this)
        prefs.migrate()
        // osmdroid: identify ourselves to the OSM tile servers and cache tiles in private storage.
        val storage = Storage.of(this)
        Configuration.getInstance().apply {
            userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = File(storage.filesDir, "osmdroid")
            osmdroidTileCache = File(storage.cacheDir, "osmdroid-tiles")
            tileFileSystemCacheMaxBytes = 80L * 1024 * 1024
        }
        if (prefs.onboardingDone && prefs.serviceEnabled) {
            try { WatchdogJob.schedule(this) } catch (_: Exception) {}
        }
    }

    /**
     * After an uncaught exception the process dies. Log it, and set an alarm that brings the
     * service back: soon after an isolated crash, more slowly if it keeps crashing.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val trace = e.stackTraceToString().lineSequence().take(12).joinToString("\n    ")
                EventLog.logNow("CRASH in thread ${thread.name}: $trace")
                val prefs = Prefs(this)
                if (prefs.onboardingDone && prefs.serviceEnabled) {
                    val delayMs = prefs.noteCrash(System.currentTimeMillis())
                    ListenerService.scheduleRestart(this, delayMs)
                    EventLog.logNow("Restarting in ${delayMs / 1000} s")
                }
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }
    }
}
