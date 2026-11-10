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
        binding.peopleCancelSelect.setOnClickListener { selected.clear(); refresh() }
        binding.peopleSimilar.setOnClickListener {
            if (lastSelected > 0) {
                val similar = PeopleStore.get(this).similarTo(lastSelected, SIMILAR_THRESHOLD)
                selected.addAll(similar)
                binding.peopleStatus.text = "${similar.size} possibly similar groups selected as a suggestion only. Untick any that are not the same person before merging."
                binding.peopleList.adapter?.notifyDataSetChanged()
                updateSelectBar()
            }
        }
        binding.peopleScan.setOnClickListener {
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

    private fun updateSelectBar() {
        val on = selected.isNotEmpty()
        binding.peopleSelectBar.visibility = if (on) View.VISIBLE else View.GONE
        binding.peopleMerge.text = "Merge ${selected.size} into one"
        val primary = getProperPrimaryColor()
        for (t in listOf(binding.peopleMerge, binding.peopleSimilar, binding.peopleCancelSelect)) t.setTextColor(primary)
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
        binding.peopleStatus.text = progress + err + "${people.size} people, ${store.faceCount()} faces in ${store.scannedCount()} photos. " +
            getString(R.string.people_experimental)
        binding.peoplePlaceholder.visibility = View.GONE
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_person, parent, false)
            val face = v.findViewById<ImageView>(R.id.person_face)
            face.outlineProvider = ViewOutlineProvider.BACKGROUND
            face.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22888888)
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
            } else null
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
    }
}
