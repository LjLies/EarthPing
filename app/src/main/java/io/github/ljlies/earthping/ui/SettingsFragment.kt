package io.github.ljlies.earthping.ui

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.ljlies.earthping.BuildConfig
import io.github.ljlies.earthping.Prefs
import io.github.ljlies.earthping.R
import io.github.ljlies.earthping.alert.AlertCenter
import io.github.ljlies.earthping.geo.LatLng
import io.github.ljlies.earthping.service.ListenerService
import java.util.Locale

/** The Settings pane. Values live in the same file as the rest of the app's state ([Prefs]). */
class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var prefs: Prefs

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = Prefs.FILE
        setPreferencesFromResource(R.xml.settings, rootKey)
        prefs = Prefs(requireContext())
        AlertCenter.init(requireContext())

        click("test_alarm") { startActivity(Intent(requireContext(), TestActivity::class.java).putExtra(TestActivity.EXTRA_SECTION, "tone")) }
        click("test_speech") { startActivity(Intent(requireContext(), TestActivity::class.java).putExtra(TestActivity.EXTRA_SECTION, "speech")) }
        findPreference<androidx.preference.ListPreference>("siren_min_mmi")?.summaryProvider =
            Preference.SummaryProvider<androidx.preference.ListPreference> { p -> "${p.entry}\n${getString(R.string.siren_threshold_note)}" }
        click("tts_settings") {
            open(Intent("com.android.settings.TTS_SETTINGS"))
        }
        click("location") { startActivity(Intent(requireContext(), LocationActivity::class.java)) }
        click("app_language") { chooseAppLanguage() }
        click("permissions") {
            startActivity(Intent(requireContext(), OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_REVIEW, true))
        }
        click("notif_categories") {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName))
        }
        click("diagnostics") { startActivity(Intent(requireContext(), LogActivity::class.java)) }
        click("source") { open(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.source_url)))) }
        findPreference<androidx.preference.ListPreference>("loud_min_mmi")?.summaryProvider =
            Preference.SummaryProvider<androidx.preference.ListPreference> { p -> "${p.entry}\n${getString(R.string.loud_threshold_note)}" }
        findPreference<Preference>("about")?.summary = getString(R.string.about_summary, BuildConfig.VERSION_NAME)
        updateSummaries()
    }

    private fun click(key: String, action: () -> Unit) {
        findPreference<Preference>(key)?.setOnPreferenceClickListener { action(); true }
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.raw.registerOnSharedPreferenceChangeListener(this)
        updateSummaries()
        // Back from the system's text-to-speech settings: use the engine chosen there.
        AlertCenter.speaker.refreshEngine()
    }

    override fun onPause() {
        prefs.raw.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences, key: String?) {
        updateSummaries()
        if (key in setOf("tts", "heartbeat_min", "manual_loc", "manual_lat", "manual_lon")) {
            ListenerService.refresh(requireContext())
        }
    }

    private fun currentAppLocaleName(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return getString(R.string.language_system)
        return locales[0]!!.let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } }
    }

    private fun updateSummaries() {
        if (!::prefs.isInitialized) return
        findPreference<Preference>("location")?.summary =
            if (prefs.manualLocationEnabled && prefs.manualLocation != null) {
                val m = prefs.manualLocation!!
                getString(R.string.location_manual_summary, prefs.manualLocationName ?: String.format(Locale.US, "%.4f, %.4f", m.lat, m.lon))
            } else getString(R.string.location_auto_summary)
        findPreference<Preference>("app_language")?.summary = currentAppLocaleName()
        val missing = Perms.missing(requireContext())
        findPreference<Preference>("permissions")?.summary =
            if (missing.isEmpty()) getString(R.string.permissions_all_granted)
            else getString(R.string.permissions_missing_count, missing.size)
    }

    /** Android 13+: the system's per-app language page. Older versions: an in-app choice. */
    private fun chooseAppLanguage() {
        if (Build.VERSION.SDK_INT >= 33) {
            open(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${requireContext().packageName}")))
            return
        }
        val tags = arrayOf("", "en", "it", "es", "tr", "el", "id", "zh-TW")
        val labels = arrayOf(getString(R.string.language_system), "English", "Italiano", "Español", "Türkçe", "Ελληνικά", "Bahasa Indonesia", "繁體中文")
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().let { t ->
            tags.firstOrNull { it.isNotEmpty() && it.equals(t, ignoreCase = true) } ?: t.substringBefore('-')
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.app_language)
            .setSingleChoiceItems(labels, tags.indexOf(current).coerceAtLeast(0)) { d, which ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[which]))
                d.dismiss()
            }
            .show()
    }
}
