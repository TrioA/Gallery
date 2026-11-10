package org.fossify.gallery.activities

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityPeopleBinding
import org.fossify.gallery.helpers.GalleryTabs
import org.fossify.gallery.helpers.ThemeContrast
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.gallery.people.FaceScanner
import org.fossify.gallery.people.PeopleStore
import org.fossify.gallery.people.Person

class PeopleActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityPeopleBinding::inflate)
    private val listener: () -> Unit = { refresh() }
    private var people: List<Person> = emptyList()
    private val selected = LinkedHashSet<Long>()
    private var lastSelected = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padTopSystem = listOf(binding.peopleAppbar))
        binding.peopleList.layoutManager = GridLayoutManager(this, 3)
        binding.peopleList.adapter = Adapter()
        binding.peopleMerge.setOnClickListener { confirmMerge() }
        binding.peopleCancelSelect.setOnClickListener { selected.clear(); suggestionStatus = null; refresh() }
        binding.peopleSimilar.setOnClickListener {
            if (lastSelected > 0) {
                val similar = PeopleStore.get(this).similarTo(lastSelected, SIMILAR_THRESHOLD)
                selected.addAll(similar)
                binding.peopleStatus.text = "${similar.size} possibly similar groups selected as a suggestion only. Untick any that are not the same person before merging."
                binding.peopleList.adapter?.notifyDataSetChanged()
                updateSelectBar()
            }
        }
        binding.peopleSuggest.setOnClickListener { suggestMerges() }
        // Hidden developer entry: tap the status line 7 times quickly.
        binding.peopleStatus.setOnClickListener {
            val now = System.currentTimeMillis()
            devTaps = if (now - lastDevTap > 1500) 1 else devTaps + 1
            lastDevTap = now
            if (devTaps >= 7) { devTaps = 0; confirmReset() }
        }
        binding.peopleScan.setOnClickListener {
            suggestionStatus = null
            if (FaceScanner.running) FaceScanner.stop() else FaceScanner.start(this)
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.peopleAppbar, NavigationIcon.None)
        val primary = getProperPrimaryColor()
        binding.peopleScan.background = GradientDrawable().apply {
            cornerRadius = 100f
            setColor(primary)
        }
        binding.peopleScan.setTextColor(primary.getContrastColor())
        binding.peopleSuggest.background = GradientDrawable().apply { cornerRadius = 100f; setStroke(3, primary); setColor(0) }
        binding.peopleSuggest.setTextColor(ThemeContrast.textAccent(primary, getProperBackgroundColor()))
        binding.peopleStatus.setTextColor(getProperTextColor())
        binding.peoplePlaceholder.setTextColor(getProperTextColor())
        GalleryTabs.bind(this, binding.bottomTabs, binding.peopleHolder, R.id.tab_people, true)
        FaceScanner.listeners.add(listener)
        FaceScanner.autoStart(this)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        FaceScanner.listeners.remove(listener)
    }

    private var suggestions: List<List<Long>> = emptyList()
    private var suggestIdx = 0

    private var suggestionStatus: String? = null
    private var computingSuggestions = false

    /** Review-only. Run the all-pairs calculation off the UI thread; never make a merge here. */
    private fun suggestMerges() {
        if (computingSuggestions) return
        if (suggestIdx == 0 || suggestIdx >= suggestions.size) {
            computingSuggestions = true
            binding.peopleSuggest.isEnabled = false
            suggestionStatus = "Looking for possible duplicate groups..."
            refresh()
            Thread {
                val store = PeopleStore.get(this)
                val found = try { store.suggestClusters(SUGGEST_THRESHOLD) } catch (_: Exception) { null }
                runOnUiThread {
                    computingSuggestions = false
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.peopleSuggest.isEnabled = true
                    if (found == null) {
                        suggestionStatus = "Could not check suggestions. No groups were changed."
                        refresh()
                    } else {
                        suggestions = found
                        suggestIdx = 0
                        showSuggestion()
                    }
                }
            }.start()
        } else showSuggestion()
    }

    private fun showSuggestion() {
        val store = PeopleStore.get(this)
        val limited = if (store.suggestionEligibleCount > PeopleStore.MAX_SUGGEST_GROUPS)
            " Checked the first ${PeopleStore.MAX_SUGGEST_GROUPS} of ${store.suggestionEligibleCount} unnamed groups; the rest were not checked." else ""
        selected.clear()
        if (suggestions.isEmpty()) {
            suggestionStatus = "No likely duplicate groups found. Named and contact-linked groups are never suggested.$limited"
        } else {
            val cluster = suggestions[suggestIdx]
            selected.addAll(cluster)
            suggestIdx = (suggestIdx + 1) % suggestions.size
            suggestionStatus = "Suggestion ${if (suggestIdx == 0) suggestions.size else suggestIdx} of ${suggestions.size}: ${cluster.size} groups may be the same person. " +
                "Review every selected group and untick any wrong ones before merging. Tap Suggest merges for the next set (up to ${PeopleStore.MAX_REVIEW_GROUPS} groups per set).$limited"
        }
        refresh()
    }

    private fun updateSelectBar() {
        val on = selected.isNotEmpty()
        binding.peopleSelectBar.visibility = if (on) View.VISIBLE else View.GONE
        binding.peopleMerge.text = "Merge ${selected.size} into one"
        val primary = getProperPrimaryColor()
        for (t in listOf(binding.peopleSimilar, binding.peopleCancelSelect)) t.setTextColor(ThemeContrast.textAccent(primary, getProperBackgroundColor()))
        binding.peopleMerge.background = GradientDrawable().apply { cornerRadius = 100f; setColor(primary) }
        binding.peopleMerge.setTextColor(primary.getContrastColor())
    }

    private fun confirmMerge() {
        if (selected.size < 2) {
            binding.peopleStatus.text = "Select at least two groups to merge."
            return
        }
        val store = PeopleStore.get(this)
        val plan = store.mergePlan(selected.toList())
        if (plan.conflictingContacts.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setMessage(
                    "These groups are linked to different contacts (${plan.conflictingContacts.joinToString(", ")}), so they are treated as different people " +
                        "and were not merged. Deselect one, or link both to the same contact first."
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val survivor = plan.survivor?.displayName() ?: "the largest group"
        val aliasNote = if (plan.aliasNames.isNotEmpty()) " Other names (${plan.aliasNames.joinToString(", ")}) are kept as 'also named'." else ""
        AlertDialog.Builder(this)
            .setMessage("Merge ${selected.size} groups into \"$survivor\"? All their photos and your corrections are kept.$aliasNote")
            .setPositiveButton("Merge") { _, _ ->
                store.mergeMany(selected.toList())
                selected.clear()
                suggestions = emptyList()
                suggestIdx = 0
                suggestionStatus = null
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        val store = PeopleStore.get(this)
        store.consolidateContacts()
        people = store.listPeople()
        selected.retainAll(people.map { it.id }.toSet())
        updateSelectBar()
        binding.peopleList.adapter?.notifyDataSetChanged()
        binding.peopleScan.text = getString(if (FaceScanner.running) R.string.people_scanning else R.string.people_scan)
        val progress = if (FaceScanner.running) {
            "Scanning ${FaceScanner.done} of ${FaceScanner.total} new photos. "
        } else {
            ""
        }
        val err = FaceScanner.lastError?.let { "Scan stopped: $it. " } ?: ""
        binding.peopleStatus.text = (suggestionStatus?.let { "$it\n" } ?: "") + progress + err + "${people.size} people, ${store.faceCount()} faces in ${store.scannedCount()} photos. " +
            getString(R.string.people_experimental)
        binding.peoplePlaceholder.visibility = View.GONE
    }

    private var devTaps = 0
    private var lastDevTap = 0L

    private fun confirmReset() {
        val input = android.widget.EditText(this).apply { hint = "DELETE ALL PEOPLE DATA"; setSingleLine() }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(
                "DEVELOPER: ERASE ALL PEOPLE DATA?\n\nThis permanently erases every People group, name, alias, contact link, face, correction and scan record, " +
                    "and cannot be undone. Your photos and phone contacts are NOT touched.\n\n" +
                    "Type DELETE ALL PEOPLE DATA to confirm."
            )
            .setView(input)
            .setPositiveButton("ERASE", null)
            .setNegativeButton("CANCEL", null)
            .create()
        dlg.setOnShowListener {
            val surface = android.util.TypedValue()
            dlg.context.theme.resolveAttribute(android.R.attr.colorBackground, surface, true)
            val accent = ThemeContrast.textAccent(getProperPrimaryColor(), surface.data)
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(accent)
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(accent)
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString().trim() != "DELETE ALL PEOPLE DATA") {
                    input.error = "Type the exact phrase"
                    return@setOnClickListener
                }
                dlg.dismiss()
                selected.clear()
                binding.peopleStatus.text = "Stopping scan and erasing People data..."
                Thread {
                    val stopped = FaceScanner.stopAndWait(20000)
                    if (stopped) PeopleStore.get(this).resetAll()
                    runOnUiThread {
                        refresh()
                        binding.peopleStatus.text = if (stopped) "People data erased. Press Scan photos for a fresh scan." else "Could not stop the scan in time. Nothing was erased."
                    }
                }.start()
            }
        }
        dlg.show()
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_person, parent, false)
            val face = v.findViewById<ImageView>(R.id.person_face)
            face.outlineProvider = ViewOutlineProvider.BACKGROUND
            // Ring in the user's chosen theme colour (adjustable in Fossify settings, nothing hardcoded).
            face.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22888888)
            }
            face.foreground = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0)
                setStroke(6, getProperPrimaryColor())
            }
            face.clipToOutline = true
            return Holder(v)
        }

        override fun getItemCount() = people.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val p = people[position]
            val store = PeopleStore.get(this@PeopleActivity)
            val bytes = store.coverThumb(p.id)
            if (bytes != null) {
                holder.face.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            } else {
                holder.face.setImageDrawable(null)
            }
            holder.name.text = p.displayName()
            holder.name.setTextColor(getProperTextColor())
            holder.count.text = "${p.faceCount}"
            holder.count.setTextColor(getProperTextColor())
            val isSel = selected.contains(p.id)
            holder.itemView.background = if (isSel) GradientDrawable().apply {
                cornerRadius = 24f
                setStroke(6, getProperPrimaryColor())
                setColor(0x22888888)
            } else GradientDrawable().apply {
                cornerRadius = 24f
                setColor(0x14888888)
            }
            holder.itemView.setOnLongClickListener {
                selected.add(p.id); lastSelected = p.id
                updateSelectBar(); notifyDataSetChanged(); true
            }
            holder.itemView.setOnClickListener {
                if (selected.isNotEmpty()) {
                    if (!selected.remove(p.id)) { selected.add(p.id); lastSelected = p.id }
                    updateSelectBar(); notifyDataSetChanged()
                } else {
                    startActivity(Intent(this@PeopleActivity, PersonActivity::class.java).putExtra(PersonActivity.EXTRA_PERSON_ID, p.id))
                }
            }
        }
    }

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val face: ImageView = v.findViewById(R.id.person_face)
        val name: TextView = v.findViewById(R.id.person_name)
        val count: TextView = v.findViewById(R.id.person_count)
    }

    companion object {
        // Average-face similarity above which "Select similar" suggests a group. Untuned suggestion only.
        private const val SIMILAR_THRESHOLD = 0.30f
        private const val SUGGEST_THRESHOLD = 0.30f
    }
}
