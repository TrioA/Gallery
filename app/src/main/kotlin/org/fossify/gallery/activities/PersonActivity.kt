package org.fossify.gallery.activities

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.io.File
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.R
import org.fossify.gallery.databinding.ActivityPersonBinding
import org.fossify.commons.helpers.IS_FROM_GALLERY
import org.fossify.gallery.helpers.PATH
import org.fossify.gallery.helpers.SHOW_ALL
import org.fossify.gallery.people.PeopleStore

/** All pictures of one person, with naming and contact linking. */
class PersonActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityPersonBinding::inflate)
    private var personId = -1L
    private var paths: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padTopSystem = listOf(binding.personAppbar))
        personId = intent.getLongExtra(EXTRA_PERSON_ID, -1L)
        binding.personGrid.layoutManager = GridLayoutManager(this, 3)
        binding.personGrid.adapter = Adapter()
        binding.personRename.setOnClickListener { askName() }
        binding.personContact.setOnClickListener {
            try {
                startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI), REQ_CONTACT)
            } catch (e: Exception) {
                binding.personInfo.text = "No contacts app available."
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.personAppbar, NavigationIcon.Arrow)
        val primary = getProperPrimaryColor()
        for (b in listOf(binding.personRename, binding.personContact)) {
            b.background = GradientDrawable().apply { cornerRadius = 100f; setColor(primary) }
            b.setTextColor(primary.getContrastColor())
        }
        binding.personInfo.setTextColor(getProperTextColor())
        load()
    }

    private fun load() {
        val store = PeopleStore.get(this)
        val p = store.getPerson(personId)
        if (p == null || p.faceCount == 0) {
            finish()
            return
        }
        binding.personToolbar.title = p.displayName()
        binding.personInfo.text = "${p.faceCount} faces" +
            (if (p.contactName.isNotBlank()) ", linked to contact ${p.contactName}" else "")
        // Photos we cannot open right now (permission, storage) are hidden, not forgotten.
        paths = store.pathsOfPerson(personId).filter { File(it).exists() }
        binding.personGrid.adapter?.notifyDataSetChanged()
    }

    private fun askName() {
        val store = PeopleStore.get(this)
        val current = store.getPerson(personId)?.name ?: ""
        val input = EditText(this).apply { setText(current); setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle(R.string.person_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                personId = store.setName(personId, input.text.toString())
                load()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONTACT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.query(
                    uri, arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME), null, null, null
                )?.use {
                    if (it.moveToFirst()) {
                        PeopleStore.get(this).setContact(personId, it.getString(0) ?: "", it.getString(1) ?: "")
                    }
                }
            } catch (e: Exception) {
                binding.personInfo.text = "Could not read that contact."
            }
            load()
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_person_photo, parent, false))

        override fun getItemCount() = paths.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val path = paths[position]
            Glide.with(this@PersonActivity).load(path).into(holder.image)
            holder.itemView.setOnClickListener {
                startActivity(
                    Intent(this@PersonActivity, ViewPagerActivity::class.java)
                        .putExtra(PATH, path)
                        .putExtra(SHOW_ALL, true)
                        .putExtra(IS_FROM_GALLERY, true)
                )
            }
        }
    }

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.person_photo)
    }

    companion object {
        const val EXTRA_PERSON_ID = "person_id"
        private const val REQ_CONTACT = 4711
    }
}
