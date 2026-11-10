package org.fossify.gallery.people

import kotlin.math.sqrt

object FaceMath {
    fun normalized(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += (x * x).toDouble()
        val n = sqrt(s).toFloat()
        if (n < 1e-9f) return v.copyOf()
        return FloatArray(v.size) { v[it] / n }
    }

    /** Cosine similarity of two already L2-normalised vectors. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }
}
