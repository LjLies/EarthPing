package io.github.ljlies.earthping.ui

import android.app.Activity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.ljlies.earthping.EventLog
import io.github.ljlies.earthping.Prefs
import io.github.ljlies.earthping.R

/** What EarthPing is and isn't, shown once before alerts are first turned on. */
object Disclaimer {
    fun needed(a: Activity) = !Prefs(a).disclaimerShown

    /** Runs [onUnderstood] right away if the notice was already acknowledged. */
    fun ensure(a: Activity, onUnderstood: () -> Unit, onCancel: () -> Unit = {}) {
        if (!needed(a)) { onUnderstood(); return }
        MaterialAlertDialogBuilder(a)
            .setIcon(R.drawable.ic_pref_warning)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_full)
            .setCancelable(false)
            .setPositiveButton(R.string.disclaimer_understood) { _, _ ->
                Prefs(a).disclaimerShown = true
                EventLog.log("Disclaimer acknowledged")
                onUnderstood()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .show()
    }
}
