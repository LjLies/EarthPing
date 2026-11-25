package io.github.ljlies.earthping.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.github.ljlies.earthping.Prefs
import io.github.ljlies.earthping.R
import io.github.ljlies.earthping.alert.AlertCenter
import io.github.ljlies.earthping.service.ListenerService

/** Alerts, Places and Settings panes. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        AlertCenter.init(this)
        if (!prefs.onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }
        if (savedInstanceState == null) nav.selectedItemId = R.id.tab_alerts
        openSettingsFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openSettingsFrom(intent)
    }

    /** "Settings" links on the Tests page: show the Settings tab at that preference. */
    private fun openSettingsFrom(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_SETTINGS_KEY) ?: return
        intent.removeExtra(EXTRA_SETTINGS_KEY)
        selectTab(R.id.tab_settings)
        (supportFragmentManager.findFragmentByTag("tab${R.id.tab_settings}") as? SettingsFragment)?.let { f ->
            f.view?.post { f.scrollToPreference(key) }
        }
    }

    companion object {
        const val EXTRA_SETTINGS_KEY = "settings_key"
    }

    override fun onResume() {
        super.onResume()
        val prefs = Prefs(this)
        if (prefs.serviceEnabled) {
            ListenerService.refresh(this)
            // Installs from before the notice existed: show it once; declining turns alerts off.
            if (Disclaimer.needed(this) && !disclaimerShowing) {
                disclaimerShowing = true
                Disclaimer.ensure(this, { disclaimerShowing = false }, {
                    disclaimerShowing = false
                    prefs.serviceEnabled = false
                    io.github.ljlies.earthping.EventLog.log("Disclaimer declined: alerts turned off")
                    stopService(Intent(this, ListenerService::class.java))
                    supportFragmentManager.fragments.filterIsInstance<StatusFragment>().forEach { it.refresh() }
                })
            }
        }
    }

    private var disclaimerShowing = false

    fun selectTab(id: Int) {
        findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = id
    }

    private fun show(id: Int) {
        val tag = "tab$id"
        val fragments = supportFragmentManager
        val transaction = fragments.beginTransaction()
        fragments.fragments.forEach { if (it.tag != tag) transaction.hide(it) }
        val existing = fragments.findFragmentByTag(tag)
        if (existing != null) {
            transaction.show(existing)
        } else {
            val fragment: Fragment = when (id) {
                R.id.tab_places -> PlacesFragment()
                R.id.tab_settings -> SettingsFragment()
                else -> StatusFragment()
            }
            transaction.add(R.id.content, fragment, tag)
        }
        transaction.commitNow()
    }
}
