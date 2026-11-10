package org.fossify.gallery.activities

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padTopSystem = listOf(binding.peopleAppbar))
        binding.peopleList.layoutManager = GridLayoutManager(this, 3)
        binding.peopleList.adapter = Adapter()
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

    private fun refresh() {
        val store = PeopleStore.get(this)
        people = store.listPeople()
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
            holder.itemView.setOnClickListener {
                startActivity(Intent(this@PeopleActivity, PersonActivity::class.java).putExtra(PersonActivity.EXTRA_PERSON_ID, p.id))
            }
        }
    }

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val face: ImageView = v.findViewById(R.id.person_face)
        val name: TextView = v.findViewById(R.id.person_name)
        val count: TextView = v.findViewById(R.id.person_count)
    }
}
