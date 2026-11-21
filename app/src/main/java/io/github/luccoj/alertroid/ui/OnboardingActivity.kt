package io.github.luccoj.alertroid.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import io.github.luccoj.alertroid.Prefs
import io.github.luccoj.alertroid.R
import io.github.luccoj.alertroid.alert.Notifications
import io.github.luccoj.alertroid.service.ListenerService

/**
 * First-run onboarding and permission review: explains each permission, shows whether it is
 * granted and opens the right system screen. Required items must be granted to continue.
 */
class OnboardingActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private lateinit var cont: android.widget.Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = getString(R.string.permissions)
        val col = Ui.scrollColumn(this)
        if (!intent.getBooleanExtra("review", false)) {
            val intro = Ui.card(col)
            Ui.title(intro, getString(R.string.welcome_title), 22f)
            Ui.text(intro, getString(R.string.welcome_body))
            Ui.text(intro, getString(R.string.disclaimer), 12f)
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        val vendor = Ui.card(col)
        Ui.title(vendor, getString(R.string.vendor_title), 16f)
        Ui.text(vendor, getString(R.string.vendor_body), 13f)
        cont = Ui.button(col, getString(R.string.continue_btn)) { finishOnboarding() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        for (item in Perms.applicable()) {
            val ok = item.granted(this)
            val card = Ui.card(list)
            Ui.title(card, (if (ok) "✓ " else if (item.required) "✗ " else "○ ") + getString(item.title), 16f)
                .setTextColor(if (ok) Ui.OK else if (item.required) Ui.BAD else Ui.WARN)
            Ui.text(card, getString(item.why), 13f)
            if (!item.required) Ui.text(card, getString(R.string.recommended), 12f)
            if (!ok) Ui.button(card, getString(R.string.grant)) { item.request(this) }
        }
        val missing = Perms.missingRequired(this)
        cont.isEnabled = missing.isEmpty()
        cont.text = if (missing.isEmpty()) getString(R.string.continue_btn) else getString(R.string.grant_required_first)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
        ListenerService.refresh(this)
    }

    private fun finishOnboarding() {
        Notifications.ensureChannels(this)
        val first = !prefs.onboardingDone
        prefs.onboardingDone = true
        prefs.serviceEnabled = true
        ListenerService.start(this, "onboarding")
        ListenerService.refresh(this)
        if (first) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
