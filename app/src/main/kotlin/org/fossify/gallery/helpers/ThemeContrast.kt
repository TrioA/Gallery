package org.fossify.gallery.helpers

import android.graphics.Color
import androidx.core.graphics.ColorUtils

/** Keep the chosen accent hue; vary only its brightness for small text on a specific surface. */
object ThemeContrast {
    fun textAccent(primary: Int, background: Int, minimum: Double = 4.5): Int {
        val bg = ColorUtils.setAlphaComponent(background, 255)
        val base = ColorUtils.setAlphaComponent(primary, 255)
        if (ColorUtils.calculateContrast(base, bg) >= minimum) return base
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(base, hsl)
        val start = hsl[2]
        var best = if (ColorUtils.calculateContrast(Color.WHITE, bg) > ColorUtils.calculateContrast(Color.BLACK, bg)) Color.WHITE else Color.BLACK
        var distance = 2f
        for (i in 0..1000) {
            hsl[2] = i / 1000f
            val candidate = ColorUtils.HSLToColor(hsl)
            val delta = kotlin.math.abs(hsl[2] - start)
            if (delta < distance && ColorUtils.calculateContrast(candidate, bg) >= minimum) {
                best = candidate
                distance = delta
            }
        }
        return best
    }
}
