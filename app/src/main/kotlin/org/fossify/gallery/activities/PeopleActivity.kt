package org.fossify.gallery.activities

import android.os.Bundle
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.gallery.databinding.ActivityPeopleBinding
import org.fossify.gallery.helpers.GalleryTabs

class PeopleActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityPeopleBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padTopSystem = listOf(binding.peopleAppbar))
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.peopleAppbar, NavigationIcon.None)
        binding.peoplePlaceholder.setTextColor(getProperTextColor())
        GalleryTabs.bind(this, binding.bottomTabs, binding.peopleHolder, GalleryTabs.PEOPLE, true)
    }
}
