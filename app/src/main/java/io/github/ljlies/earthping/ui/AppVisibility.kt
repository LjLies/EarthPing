package io.github.ljlies.earthping.ui

import android.app.Activity
import android.os.Bundle

/** Tracks whether any of our activities is on screen (lets the service start the alert UI). */
object AppVisibility {
    @Volatile var visible = false
        private set
    private var started = 0

    fun register(app: android.app.Application) {
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++; visible = started > 0 }
            override fun onActivityStopped(a: Activity) { started--; visible = started > 0 }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
