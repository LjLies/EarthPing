package io.github.luccoj.alertroid.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.github.luccoj.alertroid.Prefs
import io.github.luccoj.alertroid.R
import io.github.luccoj.alertroid.alert.AlertCenter
import io.github.luccoj.alertroid.service.ListenerService

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
    }

    override fun onResume() {
        super.onResume()
        if (Prefs(this).serviceEnabled) ListenerService.refresh(this)
    }

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
