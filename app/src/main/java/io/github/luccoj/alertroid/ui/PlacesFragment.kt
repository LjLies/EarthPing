package io.github.luccoj.alertroid.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButtonToggleGroup
import io.github.luccoj.alertroid.Place
import io.github.luccoj.alertroid.Prefs
import io.github.luccoj.alertroid.R
import io.github.luccoj.alertroid.service.ListenerService

/** Up to three extra places to be told about; they double as the protocol's privacy decoys. */
class PlacesFragment : Fragment() {
    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var editor: PlaceEditor
    private val slotIds = intArrayOf(R.id.slot1, R.id.slot2, R.id.slot3)
    /** Places being edited (null = empty slot, filled by an automatic decoy). */
    private val places = arrayOfNulls<Place>(3)
    private var selected = 0
    private var binding = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_places, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        root = view
        prefs = Prefs(requireContext())
        prefs.userDecoys.forEachIndexed { i, p -> if (i < 3) places[i] = p }
        editor = PlaceEditor(requireActivity(), view, { getString(R.string.place_n, selected + 1) }) { p ->
            places[selected] = p
            showSlot()
        }

        view.findViewById<MaterialButtonToggleGroup>(R.id.slots).apply {
            check(slotIds[0])
            addOnButtonCheckedListener { _, id, checked ->
                if (!checked || binding) return@addOnButtonCheckedListener
                readFields()
                selected = slotIds.indexOf(id)
                showSlot()
            }
        }
        view.findViewById<Button>(R.id.clear).setOnClickListener {
            places[selected] = null
            showSlot()
            save(quiet = true)
        }
        view.findViewById<Button>(R.id.save).setOnClickListener {
            if (readFields()) save(quiet = false)
        }
        showSlot()
    }

    private fun showSlot() {
        val p = places[selected]
        editor.show(p, getString(if (p == null) R.string.slot_empty_hint else R.string.slot_set_hint))
        slotIds.forEachIndexed { i, id -> root.findViewById<Button>(id).text = places[i]?.name ?: getString(R.string.place_n, i + 1) }
        editor.setMarkers(places.filterNotNull())
    }

    private fun readFields(): Boolean = when (val r = editor.read()) {
        PlaceEditor.Read.Empty -> { places[selected] = null; true }
        PlaceEditor.Read.Invalid -> {
            Toast.makeText(requireContext(), getString(R.string.invalid_place, selected + 1), Toast.LENGTH_LONG).show()
            false
        }
        is PlaceEditor.Read.Ok -> { places[selected] = r.place; true }
    }

    private fun save(quiet: Boolean) {
        prefs.userDecoys = places.filterNotNull()
        val saved = prefs.userDecoys
        for (i in 0 until 3) places[i] = saved.getOrNull(i)
        selected = selected.coerceAtMost(saved.size.coerceAtMost(2))
        binding = true
        root.findViewById<MaterialButtonToggleGroup>(R.id.slots).check(slotIds[selected])
        binding = false
        showSlot()
        ListenerService.refresh(requireContext())
        if (!quiet) Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() { super.onResume(); editor.onResume() }
    override fun onPause() { super.onPause(); editor.onPause() }
}
