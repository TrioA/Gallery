package org.fossify.gallery.helpers

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.gallery.R
import org.fossify.gallery.activities.MainActivity
import org.fossify.gallery.activities.MediaActivity
import org.fossify.gallery.activities.PeopleActivity
import org.fossify.gallery.extensions.config

/**
 * Bottom tabs shared by the Pictures, People and Albums screens.
 * Pictures = existing "show all media" mode, Albums = existing folder list.
 */
object GalleryTabs {
    const val PICTURES = R.id.tab_pictures
    const val PEOPLE = R.id.tab_people
    const val ALBUMS = R.id.tab_albums

    fun bind(activity: Activity, nav: BottomNavigationView, content: View, selected: Int, visible: Boolean) {
        if (!visible) {
            nav.visibility = View.GONE
            setBottomMargin(content, 0)
            return
        }

        nav.visibility = View.VISIBLE
        nav.setOnItemSelectedListener(null)
        nav.selectedItemId = selected

        val primary = activity.getProperPrimaryColor()
        val text = activity.getProperTextColor()
        val tint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(primary, text)
        )
        nav.setBackgroundColor(activity.getProperBackgroundColor())
        nav.itemIconTintList = tint
        nav.itemTextColor = tint
        nav.itemActiveIndicatorColor = ColorStateList.valueOf((primary and 0x00FFFFFF) or 0x33000000)

        nav.setOnItemSelectedListener { item ->
            if (item.itemId != selected) {
                open(activity, item.itemId)
            }
            true
        }

        nav.post { setBottomMargin(content, nav.height) }
    }

    private fun open(activity: Activity, tab: Int) {
        val intent = when (tab) {
            PICTURES -> {
                activity.config.showAll = true
                Intent(activity, MediaActivity::class.java).putExtra(DIRECTORY, "")
            }

            ALBUMS -> {
                activity.config.showAll = false
                Intent(activity, MainActivity::class.java)
            }

            else -> Intent(activity, PeopleActivity::class.java)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        activity.startActivity(intent)
        activity.finish()
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    private fun setBottomMargin(content: View, margin: Int) {
        (content.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            if (it.bottomMargin != margin) {
                it.bottomMargin = margin
                content.layoutParams = it
            }
        }
    }
}
