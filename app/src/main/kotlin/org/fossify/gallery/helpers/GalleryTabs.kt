package org.fossify.gallery.helpers

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
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
        val bg = activity.getProperBackgroundColor()
        val pillColor = ColorUtils.blendARGB(bg, text, 0.10f)
        val indicator = (primary and 0x00FFFFFF) or 0x33000000
        val readableAccent = ThemeContrast.textAccent(primary, ColorUtils.compositeColors(indicator, pillColor))
        val tint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(readableAccent, text)
        )
        val dp = activity.resources.displayMetrics.density
        // Floating pill: background nudged toward the text color so it reads on any theme.
        val pill = GradientDrawable().apply {
            cornerRadius = 32 * dp
            setColor(pillColor)
        }
        nav.background = pill
        nav.elevation = 10 * dp
        (nav.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            val side = (16 * dp).toInt()
            it.setMargins(side, 0, side, (8 * dp).toInt())
            nav.layoutParams = it
        }
        nav.itemIconTintList = tint
        nav.itemTextColor = tint
        nav.itemActiveIndicatorColor = ColorStateList.valueOf(indicator)

        nav.setOnItemSelectedListener { item ->
            if (item.itemId != selected) {
                open(activity, item.itemId)
            }
            true
        }

        nav.post { setBottomMargin(content, nav.height + (8 * dp).toInt()) }
    }

    private fun open(activity: Activity, tab: Int) {
        val intent = when (tab) {
            R.id.tab_pictures -> {
                activity.config.showAll = true
                Intent(activity, MediaActivity::class.java).putExtra(DIRECTORY, "")
            }

            R.id.tab_albums -> {
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
