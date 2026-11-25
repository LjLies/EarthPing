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
 *
 * The three buttons at the top always show what is saved. Below them the selected place can be
 * changed (search, long press, typing, Clear); nothing is kept until Save, which is only active
 * when there is a change. While there is one, Clear turns into Cancel, which brings back the
 * saved place. Switching to another place drops an unsaved change.
 */
class PlacesFragment : Fragment() {
    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var editor: PlaceEditor
    private val slotIds = intArrayOf(R.id.slot1, R.id.slot2, R.id.slot3)
    /** What is saved (null = empty slot, filled by an automatic decoy). */
    private val saved = arrayOfNulls<Place>(3)
    private var selected = 0
    /** The selected place as edited; equal to saved[selected] when there's nothing to save. */
    private var draft: Place? = null
    /** The fields hold something that isn't a valid place (e.g. a half-typed coordinate). */
    private var invalid = false
    private var binding = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_places, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        root = view
        prefs = Prefs(requireContext())
        loadSaved()
        editor = PlaceEditor(requireActivity(), view, { getString(R.string.place_n, selected + 1) }) { p ->
            draft = p
            invalid = false
            showDraft()
        }
        editor.watch {
            if (binding) return@watch
            when (val r = editor.read()) {
                PlaceEditor.Read.Empty -> { draft = null; invalid = false }
                is PlaceEditor.Read.Ok -> { draft = r.place; invalid = false }
                PlaceEditor.Read.Invalid -> invalid = true
            }
            updateState()
        }

        view.findViewById<MaterialButtonToggleGroup>(R.id.slots).apply {
            check(slotIds[0])
            addOnButtonCheckedListener { _, id, checked ->
                if (!checked || binding) return@addOnButtonCheckedListener
                select(slotIds.indexOf(id))
            }
        }
        view.findViewById<Button>(R.id.clear).setOnClickListener {
            // Cancel while there is a change, Clear otherwise.
            draft = if (changed()) saved[selected] else null
            invalid = false
            showDraft()
        }
        view.findViewById<Button>(R.id.save).setOnClickListener { save() }
        draft = saved[0]
        select(0)
    }

    private fun loadSaved() {
        val list = prefs.userDecoys
        for (i in 0 until 3) saved[i] = list.getOrNull(i)
    }

    private fun select(i: Int) {
        if (changed()) Toast.makeText(requireContext(), R.string.place_not_saved, Toast.LENGTH_SHORT).show()
        selected = i
        draft = saved[i]
        invalid = false
        showDraft()
    }

    private fun showDraft() {
        binding = true
        editor.show(draft, getString(if (draft == null) R.string.slot_empty_hint else R.string.slot_set_hint))
        binding = false
        updateState()
    }

    /** Fields show coordinates to four decimals, so compare at that precision. */
    private fun same(a: Place?, b: Place?): Boolean =
        if (a == null || b == null) a == b
        else a.name == b.name && kotlin.math.abs(a.lat - b.lat) < 1e-4 && kotlin.math.abs(a.lon - b.lon) < 1e-4

    private fun changed() = invalid || !same(draft, saved[selected])

    private fun updateState() {
        slotIds.forEachIndexed { i, id -> root.findViewById<Button>(id).text = saved[i]?.name ?: getString(R.string.place_n, i + 1) }
        val changed = changed()
        root.findViewById<Button>(R.id.save).isEnabled = changed && !invalid
        root.findViewById<Button>(R.id.clear).apply {
            setText(if (changed) android.R.string.cancel else R.string.clear)
            isEnabled = changed || saved[selected] != null
        }
        // The map shows the saved places, with the one being edited where it would go.
        editor.setMarkers((0 until 3).mapNotNull { if (it == selected) draft else saved[it] })
    }

    private fun save() {
        if (invalid) {
            Toast.makeText(requireContext(), getString(R.string.invalid_place, selected + 1), Toast.LENGTH_LONG).show()
            return
        }
        saved[selected] = draft
        prefs.userDecoys = saved.filterNotNull()
        // Saving packs the places to the front, so an emptied slot may move.
        loadSaved()
        selected = selected.coerceAtMost(saved.count { it != null }.coerceAtMost(2))
        draft = saved[selected]
        binding = true
        root.findViewById<MaterialButtonToggleGroup>(R.id.slots).check(slotIds[selected])
        binding = false
        showDraft()
        ListenerService.refresh(requireContext())
        Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() { super.onResume(); editor.onResume() }
    override fun onPause() { super.onPause(); editor.onPause() }
}
