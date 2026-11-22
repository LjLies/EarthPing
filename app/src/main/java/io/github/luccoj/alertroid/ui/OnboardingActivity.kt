package io.github.luccoj.alertroid.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.luccoj.alertroid.Prefs
import io.github.luccoj.alertroid.R
import io.github.luccoj.alertroid.alert.Notifications
import io.github.luccoj.alertroid.service.ListenerService

/**
 * First-run onboarding and permission review: explains each permission, shows whether it is
 * granted and opens the right system screen. Required items must be granted to continue.
 */
class OnboardingActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_REVIEW = "review"
    }

    private lateinit var prefs: Prefs
    private val review get() = intent.getBooleanExtra(EXTRA_REVIEW, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_onboarding)
        setTitle(if (review) R.string.permissions else R.string.welcome_short)
        if (review) supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<View>(R.id.intro).visibility = if (review) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.continue_btn).setOnClickListener { finishOnboarding() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val rows = findViewById<LinearLayout>(R.id.permissions)
        rows.removeAllViews()
        for (item in Perms.applicable()) {
            val ok = item.granted(this)
            val v = layoutInflater.inflate(R.layout.permission_row, rows, false)
            v.findViewById<TextView>(R.id.permission_title).setText(item.title)
            v.findViewById<TextView>(R.id.permission_why).setText(item.why)
            val (label, bg, fg) = when {
                ok -> Triple(R.string.perm_ok, R.color.chip_ok_bg, R.color.chip_ok_fg)
                item.required -> Triple(R.string.perm_required, R.color.chip_error_bg, R.color.chip_error_fg)
                else -> Triple(R.string.perm_recommended, R.color.chip_warn_bg, R.color.chip_warn_fg)
            }
            v.findViewById<TextView>(R.id.permission_status).apply {
                setText(label)
                backgroundTintList = ColorStateList.valueOf(getColor(bg))
                setTextColor(getColor(fg))
            }
            v.findViewById<Button>(R.id.permission_button).apply {
                visibility = if (ok) View.INVISIBLE else View.VISIBLE
                setOnClickListener { item.request(this@OnboardingActivity) }
            }
            rows.addView(v)
        }
        val missing = Perms.missingRequired(this)
        findViewById<Button>(R.id.continue_btn).apply {
            isEnabled = missing.isEmpty()
            setText(if (missing.isEmpty()) (if (review) R.string.done else R.string.continue_btn) else R.string.grant_required_first)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
        ListenerService.refresh(this)
    }

    private fun finishOnboarding() {
        Notifications.ensureChannels(this)
        val first = !prefs.onboardingDone
        prefs.onboardingDone = true
        if (first) prefs.serviceEnabled = true
        if (prefs.serviceEnabled) {
            ListenerService.start(this, "onboarding")
            ListenerService.refresh(this)
        }
        if (first) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
