package io.github.ljlies.earthping.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButtonToggleGroup
import io.github.ljlies.earthping.Place
import io.github.ljlies.earthping.Prefs
import io.github.ljlies.earthping.R
import io.github.ljlies.earthping.service.ListenerService

/**
 * Up to three extra places to be told about; they double as the protocol's privacy decoys.
 * Edits (search, long press, typing, Clear) are drafts until Save: unsaved places are marked
 * with a dot, and the button reads "Saved" (disabled) when there is nothing to save.
 */
class PlacesFragment : Fragment() {
    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var editor: PlaceEditor
    private val slotIds = intArrayOf(R.id.slot1, R.id.slot2, R.id.slot3)
    /** Places being edited (null = empty slot, filled by an automatic decoy). */
    private val places = arrayOfNulls<Place>(3)
    /** What is saved, to tell which slots have unsaved changes. */
    private val saved = arrayOfNulls<Place>(3)
    private var selected = 0
    private var binding = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_places, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        root = view
        prefs = Prefs(requireContext())
        prefs.userDecoys.forEachIndexed { i, p -> if (i < 3) { places[i] = p; saved[i] = p } }
        editor = PlaceEditor(requireActivity(), view, { getString(R.string.place_n, selected + 1) }) { p ->
            places[selected] = p
            showSlot()
        }
        editor.watch {
            if (binding) return@watch
            // Typing: keep the draft in step when the fields hold a valid place.
            when (val r = editor.read()) {
                PlaceEditor.Read.Empty -> places[selected] = null
                is PlaceEditor.Read.Ok -> places[selected] = r.place
                PlaceEditor.Read.Invalid -> Unit
            }
            updateState()
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
        }
        view.findViewById<Button>(R.id.save).setOnClickListener {
            if (readFields()) save()
        }
        showSlot()
    }

    private fun showSlot() {
        val p = places[selected]
        binding = true
        editor.show(p, getString(if (p == null) R.string.slot_empty_hint else R.string.slot_set_hint))
        binding = false
        updateState()
    }

    /** Fields show coordinates to four decimals, so compare at that precision. */
    private fun same(a: Place?, b: Place?): Boolean =
        if (a == null || b == null) a == b
        else a.name == b.name && kotlin.math.abs(a.lat - b.lat) < 1e-4 && kotlin.math.abs(a.lon - b.lon) < 1e-4

    private fun updateState() {
        slotIds.forEachIndexed { i, id ->
            val name = places[i]?.name ?: getString(R.string.place_n, i + 1)
            root.findViewById<Button>(id).text = if (same(places[i], saved[i])) name else "$name •"
        }
        val dirty = (0 until 3).any { !same(places[it], saved[it]) }
        root.findViewById<Button>(R.id.save).apply {
            isEnabled = dirty
            setText(if (dirty) R.string.save else R.string.saved)
        }
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

    private fun save() {
        prefs.userDecoys = places.filterNotNull()
        val stored = prefs.userDecoys
        for (i in 0 until 3) { places[i] = stored.getOrNull(i); saved[i] = places[i] }
        selected = selected.coerceAtMost(stored.size.coerceAtMost(2))
        binding = true
        root.findViewById<MaterialButtonToggleGroup>(R.id.slots).check(slotIds[selected])
        binding = false
        showSlot()
        ListenerService.refresh(requireContext())
    }

    override fun onResume() { super.onResume(); editor.onResume() }
    override fun onPause() { super.onPause(); editor.onPause() }
}
