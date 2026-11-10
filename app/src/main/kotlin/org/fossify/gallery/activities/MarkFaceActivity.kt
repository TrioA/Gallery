package org.fossify.gallery.activities

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.gallery.people.FaceScanner

/** Tap the face the detector missed. The photo is shown upright; the tap is turned into a search area. */
class MarkFaceActivity : SimpleActivity() {

    private var bmp: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val root = FrameLayout(this)
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val hint = TextView(this).apply {
            text = "Tap the face that was missed"
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(40, 30, 40, 30)
        }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        root.addView(hint, FrameLayout.LayoutParams(-1, -2))
        setContentView(root)
        setupEdgeToEdge(padTopSystem = listOf(hint))
        Thread {
            val b = FaceScanner.decodeFile(path)
            runOnUiThread {
                bmp = b
                if (b == null) { hint.text = "Could not open this photo"; return@runOnUiThread }
                image.setImageBitmap(b)
            }
        }.start()
        var busy = false
        image.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP && !busy) {
                val b = bmp ?: return@setOnTouchListener true
                val inv = Matrix()
                (v as ImageView).imageMatrix.invert(inv)
                val pt = floatArrayOf(ev.x, ev.y)
                inv.mapPoints(pt)
                val nx = pt[0] / b.width
                val ny = pt[1] / b.height
                if (nx in 0f..1f && ny in 0f..1f) {
                    busy = true
                    hint.text = "Looking for a face there..."
                    Thread {
                        val r = try { FaceScanner.addFaceAt(this, path, nx, ny) } catch (t: Throwable) { -1 }
                        runOnUiThread {
                            when (r) {
                                1 -> { setResult(RESULT_OK); finish() }
                                2 -> { hint.text = "That face is already found in this photo."; busy = false }
                                0 -> { hint.text = "No face found there. Tap closer to the middle of the face."; busy = false }
                                else -> { hint.text = "Something went wrong. Try again."; busy = false }
                            }
                        }
                    }.start()
                }
            }
            true
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
