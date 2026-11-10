package org.fossify.gallery.people

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.gallery.activities.PersonActivity
import java.io.File

/**
 * Swipe-up panel in the photo viewer: basic details and the people found in this photo,
 * with correction actions. Corrections change grouping on this phone only; they do not retrain the model.
 */
object PhotoDetailsSheet {

    fun show(activity: AppCompatActivity, path: String, onAllProperties: () -> Unit) {
        val dialog = BottomSheetDialog(activity)
        val dp = activity.resources.displayMetrics.density
        val text = activity.getProperTextColor()
        val primary = activity.getProperPrimaryColor()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (28 * dp).toInt())
            setBackgroundColor(activity.getProperBackgroundColor())
        }
        val scroll = ScrollView(activity).apply { addView(root) }
        dialog.setContentView(scroll)

        fun label(t: String, size: Float = 14f, alpha: Float = 1f): TextView = TextView(activity).apply {
            this.text = t
            textSize = size
            setTextColor(text)
            this.alpha = alpha
        }

        fun button(t: String, onClick: () -> Unit): TextView = TextView(activity).apply {
            this.text = t
            textSize = 13f
            setTextColor(primary.getContrastColor())
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 100f; setColor(primary) }
            setOnClickListener { onClick() }
        }

        fun render() {
            root.removeAllViews()
            val file = File(path)
            root.addView(label(file.name, 17f))
            root.addView(label("${file.parent ?: ""}", 12f, 0.6f))
            root.addView(label("${file.length() / 1024} KB", 12f, 0.6f))
            root.addView(button("All properties") { dialog.dismiss(); onAllProperties() }.also {
                (it.layoutParams as? LinearLayout.LayoutParams)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (10 * dp).toInt() })

            root.addView(label("People in this photo", 16f), LinearLayout.LayoutParams(-2, -2).apply { topMargin = (22 * dp).toInt() })
            val store = PeopleStore.get(activity)
            val faces = store.facesOfPath(path)
            if (faces.isEmpty()) {
                root.addView(label("No faces found yet. Scan photos from the People tab, or try the more sensitive search below.", 13f, 0.7f))
            }
            for (f in faces) {
                val person = store.getPerson(f.personId)
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                }
                val img = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22888888) }
                    clipToOutline = true
                    f.thumb?.let { setImageBitmap(BitmapFactory.decodeByteArray(it, 0, it.size)) }
                }
                row.addView(img, LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()))
                val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(label(person?.displayName() ?: "Unknown", 15f))
                col.addView(label(if (f.confirmed) "Confirmed by you" else "Guessed, not confirmed", 11f, 0.6f))
                val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                if (!f.confirmed) {
                    actions.addView(button("Correct") { store.confirmFace(f.id); render() })
                }
                actions.addView(button("Wrong person") { choosePerson(activity, store, f.id) { render() } },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = (6 * dp).toInt() })
                actions.addView(button("Name") {
                    dialog.dismiss()
                    activity.startActivity(Intent(activity, PersonActivity::class.java).putExtra(PersonActivity.EXTRA_PERSON_ID, f.personId))
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (6 * dp).toInt() })
                col.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * dp).toInt() })
                row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (12 * dp).toInt() })
                root.addView(row)
            }

            root.addView(label("Missed someone?", 14f), LinearLayout.LayoutParams(-2, -2).apply { topMargin = (18 * dp).toInt() })
            val status = label("", 12f, 0.7f)
            root.addView(button("Look again (more sensitive)") {
                status.text = "Looking..."
                Thread {
                    val added = try {
                        FaceScanner.rescanPhoto(activity, path)
                    } catch (t: Throwable) {
                        -1
                    }
                    activity.runOnUiThread {
                        if (added < 0) Toast.makeText(activity, "Could not scan this photo", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(activity, if (added == 0) "No new faces found" else "$added new face(s) added", Toast.LENGTH_SHORT).show()
                        render()
                    }
                }.start()
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * dp).toInt() })
            root.addView(button("Mark a missed face") {
                dialog.dismiss()
                activity.startActivity(Intent(activity, org.fossify.gallery.activities.MarkFaceActivity::class.java)
                    .putExtra(org.fossify.gallery.activities.MarkFaceActivity.EXTRA_PATH, path))
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * dp).toInt() })
            root.addView(status)
        }
        render()
        dialog.show()
    }

    private fun choosePerson(context: Context, store: PeopleStore, faceId: Long, done: () -> Unit) {
        val people = store.listPeople()
        val names = mutableListOf("New person")
        names.addAll(people.map { it.displayName() })
        AlertDialog.Builder(context)
            .setTitle("Who is this?")
            .setItems(names.toTypedArray()) { _, which ->
                store.moveFace(faceId, if (which == 0) 0L else people[which - 1].id)
                done()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
