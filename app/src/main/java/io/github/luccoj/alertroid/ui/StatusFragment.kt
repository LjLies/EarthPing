package io.github.luccoj.alertroid.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.luccoj.alertroid.Places
import io.github.luccoj.alertroid.Prefs
import io.github.luccoj.alertroid.R
import io.github.luccoj.alertroid.alert.AlertHistory
import io.github.luccoj.alertroid.service.ListenerService
import io.github.luccoj.alertroid.service.ServiceStatus
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** The main pane: connection state, areas listened to, test alert and recent alerts. */
class StatusFragment : Fragment() {
    private lateinit var prefs: Prefs
    private lateinit var root: View
    private val handler = Handler(Looper.getMainLooper())
    private var binding = false
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_status, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        root = view
        prefs = Prefs(requireContext())

        view.findViewById<MaterialSwitch>(R.id.service_switch).setOnCheckedChangeListener { sw, on ->
            if (binding) return@setOnCheckedChangeListener
            if (on) {
                setAlerts(true)
            } else {
                // Turning warnings off by accident would be dangerous: ask first.
                binding = true
                sw.isChecked = true
                binding = false
                MaterialAlertDialogBuilder(requireContext())
                    .setIcon(R.drawable.ic_pref_warning)
                    .setTitle(R.string.turn_off_title)
                    .setMessage(R.string.turn_off_message)
                    .setPositiveButton(R.string.turn_off_confirm) { _, _ -> setAlerts(false) }
                    .setNegativeButton(R.string.keep_on, null)
                    .show()
            }
        }
        view.findViewById<View>(R.id.warning_button).setOnClickListener {
            startActivity(Intent(requireContext(), OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_REVIEW, true))
        }
        view.findViewById<View>(R.id.clear_history).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.clear_history_title)
                .setMessage(R.string.clear_history_message)
                .setPositiveButton(R.string.clear_history) { _, _ -> AlertHistory.clear(requireContext()); refresh() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        view.findViewById<View>(R.id.edit_places).setOnClickListener { (activity as? MainActivity)?.selectTab(R.id.tab_places) }

    }

    private fun setAlerts(on: Boolean) {
        prefs.serviceEnabled = on
        io.github.luccoj.alertroid.EventLog.log("Alerts turned ${if (on) "on" else "off"} by the user")
        if (on) ListenerService.start(requireContext(), "user")
        else requireContext().stopService(Intent(requireContext(), ListenerService::class.java))
        refresh()
    }

    override fun onResume() {
        super.onResume()
        ticker.run()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) handler.removeCallbacks(ticker) else ticker.run()
    }

    private fun ago(ms: Long): String =
        if (ms <= 0) "—" else DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString()

    private fun chip(view: TextView, text: String, bg: Int, fg: Int) {
        val c = requireContext()
        view.text = text
        view.backgroundTintList = ColorStateList.valueOf(c.getColor(bg))
        view.setTextColor(c.getColor(fg))
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(view, ColorStateList.valueOf(c.getColor(fg)))
    }

    private fun row(parent: LinearLayout, icon: Int, title: String, detail: String?, chipText: String? = null) {
        val v = layoutInflater.inflate(R.layout.row_two_line, parent, false)
        v.findViewById<ImageView>(R.id.row_icon).setImageResource(icon)
        v.findViewById<TextView>(R.id.row_title).text = title
        v.findViewById<TextView>(R.id.row_detail).apply { text = detail; visibility = if (detail.isNullOrEmpty()) View.GONE else View.VISIBLE }
        if (chipText != null) {
            val c = v.findViewById<TextView>(R.id.row_chip)
            c.visibility = View.VISIBLE
            chip(c, chipText, R.color.chip_neutral_bg, R.color.chip_neutral_fg)
        }
        parent.addView(v)
    }

    /** History notes are stored in English; show them in the app's language. */
    private fun noteLabel(note: String) = when (note) {
        "Test" -> getString(R.string.note_test)
        "Revision" -> getString(R.string.note_revision)
        "Updated" -> getString(R.string.note_updated)
        "Google test alert (not shown)" -> getString(R.string.note_google_test)
        else -> note
    }

    private fun kindLabel(kind: String) = when (kind) {
        "earthquake" -> getString(R.string.kind_earthquake)
        "public alert" -> getString(R.string.kind_public_alert)
        "SOS alert" -> getString(R.string.kind_sos_alert)
        else -> kind
    }

    private fun refresh() {
        if (!isAdded) return
        val ctx = requireContext()
        val s = ServiceStatus
        val enabled = prefs.serviceEnabled
        val connected = enabled && s.state == ServiceStatus.State.CONNECTED

        // Chip and status card
        val chipView = root.findViewById<TextView>(R.id.chip)
        when {
            !enabled -> chip(chipView, getString(R.string.chip_off), R.color.chip_error_bg, R.color.chip_error_fg)
            connected -> chip(chipView, getString(R.string.chip_listening), R.color.chip_ok_bg, R.color.chip_ok_fg)
            s.state == ServiceStatus.State.CONNECTING || s.state == ServiceStatus.State.STOPPED ->
                chip(chipView, getString(R.string.chip_connecting), R.color.chip_neutral_bg, R.color.chip_neutral_fg)
            else -> chip(chipView, getString(R.string.chip_offline), R.color.chip_warn_bg, R.color.chip_warn_fg)
        }
        root.findViewById<TextView>(R.id.status).text = when {
            !enabled -> getString(R.string.state_disabled)
            connected -> getString(R.string.state_connected_title)
            s.state == ServiceStatus.State.NO_NETWORK -> getString(R.string.state_no_network)
            s.state == ServiceStatus.State.WAITING -> getString(R.string.state_waiting_title)
            else -> getString(R.string.state_connecting)
        }
        val details = when {
            !enabled -> getString(R.string.state_disabled_detail)
            connected -> getString(R.string.state_connected_detail, ago(s.connectedSinceMs), ago(s.lastRxMs))
            s.state == ServiceStatus.State.WAITING -> listOfNotNull(
                getString(R.string.state_waiting, ago(s.nextAttemptMs)),
                s.lastError?.let { getString(R.string.last_error, it) }).joinToString("\n")
            else -> s.lastError?.let { getString(R.string.last_error, it) } ?: ""
        }
        root.findViewById<TextView>(R.id.status_details).apply {
            text = details
            visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
        }
        binding = true
        root.findViewById<MaterialSwitch>(R.id.service_switch).isChecked = enabled
        binding = false
        // Off: the card turns bright red so it cannot be missed.
        val off = !enabled
        root.findViewById<MaterialCardView>(R.id.status_card).setCardBackgroundColor(ctx.getColor(if (off) R.color.off_bg else R.color.card))
        val fg = ctx.getColor(if (off) R.color.off_fg else R.color.on_surface)
        val fgSoft = ctx.getColor(if (off) R.color.off_fg else R.color.on_surface_soft)
        root.findViewById<TextView>(R.id.status).apply {
            setTextColor(fg)
            setCompoundDrawablesRelativeWithIntrinsicBounds(if (off) R.drawable.ic_alert_off else 0, 0, 0, 0)
            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(fg))
        }
        root.findViewById<TextView>(R.id.status_details).setTextColor(fgSoft)
        root.findViewById<TextView>(R.id.switch_label).setTextColor(fg)

        // Warnings
        val missing = Perms.missing(ctx)
        val noLocation = prefs.effectiveLocation() == null
        val warnCard = root.findViewById<MaterialCardView>(R.id.warning_card)
        root.findViewById<View>(R.id.warning_button).visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        warnCard.visibility = if (noLocation || missing.isNotEmpty() || !enabled) View.VISIBLE else View.GONE
        val serious = noLocation || missing.any { it.required } || !enabled
        warnCard.setCardBackgroundColor(ctx.getColor(if (serious) R.color.chip_error_bg else R.color.chip_warn_bg))
        root.findViewById<TextView>(R.id.warning_text).text = buildList {
            if (!enabled) add("• " + getString(R.string.alerts_off_item))
            if (noLocation) add(getString(R.string.no_location_yet))
            missing.forEach { add("• " + getString(it.title) + if (it.required) " " + getString(R.string.required_tag) else "") }
        }.joinToString("\n")

        // Areas
        val areas = root.findViewById<LinearLayout>(R.id.areas)
        areas.removeAllViews()
        val subs = Places.subscriptions(ctx)
        val me = prefs.effectiveLocation()
        val loc = prefs.lastLocation()
        if (me != null) {
            val source = if (prefs.manualLocationEnabled) getString(R.string.manual) else listOfNotNull(loc?.first?.name?.ifEmpty { null }, loc?.second?.let { ago(it) }).joinToString(", ")
            row(areas, R.drawable.ic_pref_location, getString(R.string.my_location),
                String.format(Locale.US, "%.3f, %.3f", me.lat, me.lon) + " · " + source)
        } else {
            row(areas, R.drawable.ic_pref_location, getString(R.string.my_location), getString(R.string.location_unknown))
        }
        // Automatic decoys are only there for privacy: not shown (they are listed in Diagnostics).
        subs.filter { !it.primary && !Places.isAutoDecoy(it.place) }.forEach {
            row(areas, R.drawable.ic_pref_places, it.place.name, getString(R.string.watched_detail))
        }

        // History
        val history = root.findViewById<LinearLayout>(R.id.history)
        history.removeAllViews()
        val entries = AlertHistory.list()
        root.findViewById<View>(R.id.clear_history).visibility = if (entries.isEmpty()) View.INVISIBLE else View.VISIBLE
        if (entries.isEmpty()) {
            history.addView(TextView(ctx).apply {
                text = getString(R.string.no_alerts_yet)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(ctx.getColor(R.color.on_surface_soft))
                setPadding(0, 12, 0, 4)
            })
        }
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        entries.take(15).forEach { e ->
            val icon = if (e.kind == "earthquake") R.drawable.ic_tab_alerts else R.drawable.ic_pref_storm
            val where = if (e.where == "my location") getString(R.string.my_location) else e.where
            val title = listOf(where, if (e.kind == "earthquake") e.summary else kindLabel(e.kind) + ": " + e.summary).joinToString(" · ")
            val detail = buildList {
                add(df.format(Date(e.timeMs)))
                e.distanceKm?.let { add(getString(R.string.km_away, Math.round(it).toString())) }
                e.note?.let { add(noteLabel(it)) }
            }.joinToString(" · ")
            row(history, icon, title, detail)
        }
    }
}
