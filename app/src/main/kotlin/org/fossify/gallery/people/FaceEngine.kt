package org.fossify.gallery.people

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device face detection (MediaPipe BlazeFace short range) and embedding
 * (AuraFace glintr100 TFLite). Everything runs locally; nothing is uploaded.
 * The embedder is isolated here so a different model can be swapped in.
 */
class FaceEngine(context: Context, private val minConfidence: Float = 0.6f) : Closeable {
    /** Test-only: a file in the app's private dir (writable only through run-as on debug builds) slows every stage, so tests can hit a photo mid-way deterministically. */
    private val debugStageDelayMs: Long = try {
        java.io.File(context.applicationContext.filesDir, "dbg_stage_delay_ms").readText().trim().toLong()
    } catch (_: Exception) { 0L }

    /** Checked between tiles so Stop is honoured mid-photo. */
    @Volatile var shouldStop: () -> Boolean = { false }
    /** True if the last photo was cut short by Stop, so the scanner must not record it as fully scanned. */
    @Volatile var interrupted = false


    class Found(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val embedding: FloatArray, val thumb: ByteArray,
    )

    private val appContext = context.applicationContext
    private val detector: FaceDetector
    private var fullRangeDetector: FaceDetector? = null
    // Debug regression control only. Normal installs never have this private file.
    private val fullRangeEnabled = !java.io.File(appContext.filesDir, "dbg_disable_fullrange").exists()

    private fun fullRange(): FaceDetector = fullRangeDetector ?: FaceDetector.createFromOptions(appContext,
        FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(FULL_RANGE_ASSET).build())
            .setMinDetectionConfidence(TILE_MIN_SCORE)
            .setRunningMode(RunningMode.IMAGE)
            .build()).also { fullRangeDetector = it }

    private val interpreter: Interpreter
    private val embDim: Int
    private val input: ByteBuffer = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder())

    init {
        val options = FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(DETECTOR_ASSET).build())
            .setMinDetectionConfidence(minConfidence)
            .setRunningMode(RunningMode.IMAGE)
            .build()
        detector = FaceDetector.createFromOptions(context.applicationContext, options)

        val bytes = context.assets.open(EMBEDDER_ASSET).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4))
        embDim = interpreter.getOutputTensor(0).shape().last()
    }

    /** A detected face in full-bitmap pixel coordinates, with the BlazeFace landmarks we align by. */
    private class Cand(
        val score: Float, val left: Float, val top: Float, val right: Float, val bottom: Float,
        val rEyeX: Float, val rEyeY: Float, val lEyeX: Float, val lEyeY: Float, val mouthX: Float, val mouthY: Float,
        val fromFullRange: Boolean = false,
    ) {
        fun cx() = (left + right) / 2
        fun cy() = (top + bottom) / 2
        fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
        private fun area() = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        /** Same face seen twice: high overlap, or the smaller box mostly inside a box of similar size. Different-size nested boxes stay distinct. */
        fun duplicateOf(o: Cand): Boolean {
            val iw = minOf(right, o.right) - maxOf(left, o.left)
            val ih = minOf(bottom, o.bottom) - maxOf(top, o.top)
            if (iw <= 0 || ih <= 0) return false
            val inter = iw * ih
            val a = area(); val b = o.area()
            val small = minOf(a, b); val big = maxOf(a, b)
            val iou = inter / (a + b - inter)
            return iou >= 0.4f || (inter / small >= 0.7f && small / big >= 0.5f)
        }
    }

    private fun detectIn(src: Bitmap, ox: Int, oy: Int, minScore: Float, out: MutableList<Cand>, source: FaceDetector = detector) {
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        for (det in source.detect(BitmapImageBuilder(src).build()).detections()) {
            val box = det.boundingBox()
            val kps = det.keypoints()
            if (!kps.isPresent || kps.get().size < 4) continue
            val k = kps.get()
            // BlazeFace order: right eye, left eye, nose tip, mouth centre (normalised to the image given)
            val score = if (det.categories().isNotEmpty()) det.categories()[0].score() else 0f
            if (score < minScore) continue
            out.add(Cand(
                score, ox + box.left, oy + box.top, ox + box.right, oy + box.bottom,
                ox + k[0].x() * w, oy + k[0].y() * h, ox + k[1].x() * w, oy + k[1].y() * h,
                ox + k[3].x() * w, oy + k[3].y() * h,
                source === fullRangeDetector,
            ))
        }
    }

    /**
     * Whole-frame detection plus overlapping half-size tiles, so small faces in wide or group photos
     * are not missed (the short-range detector only sees faces that fill a good part of its frame).
     * Duplicates between passes are merged. Tiling is skipped for small images and capped in count.
     */
    private fun detectAll(bitmap: Bitmap, source: FaceDetector = detector, allowFallback: Boolean = true): List<Cand> {
        val all = mutableListOf<Cand>()
        interrupted = false
        detectIn(bitmap, 0, 0, 0f, all, source)
        if (debugStageDelayMs > 0) { Thread.sleep(debugStageDelayMs); if (shouldStop()) interrupted = true }
        val m = minOf(bitmap.width, bitmap.height)
        var tiles = 0
        val t0 = SystemClock.elapsedRealtime()
        if (m >= TILE_MIN_SIDE) {
            val intensive = minConfidence < 0.6f
            val s = (m * (if (intensive) TILE_FRACTION else BUDGET_TILE_FRACTION)).toInt()
            // Automatic scans use a fixed budget of 6 tiles (3 along the long side, 2 along the short side);
            // "Look again" and manual mark use the full overlapping grid.
            val pts = mutableListOf<Pair<Int, Int>>()
            if (intensive) {
                val xs = tileStarts(bitmap.width, s, maxOf(1, s / 2)); val ys = tileStarts(bitmap.height, s, maxOf(1, s / 2))
                if (xs.size * ys.size <= MAX_TILES) for (y in ys) for (x in xs) pts.add(x to y)
            } else {
                val landscape = bitmap.width >= bitmap.height
                val xs = spread(bitmap.width, s, if (landscape) 3 else 2)
                val ys = spread(bitmap.height, s, if (landscape) 2 else 3)
                for (y in ys) for (x in xs) pts.add(x to y)
            }
            // Tile-only detections must be confident: weak ones on tiles are mostly face-like patches.
            val tileMinScore = if (intensive) minConfidence else TILE_MIN_SCORE
            for ((x, y) in pts.distinct()) {
                if (interrupted || shouldStop()) { interrupted = true; break }
                if (debugStageDelayMs > 0) Thread.sleep(debugStageDelayMs)
                val tile = Bitmap.createBitmap(bitmap, x, y, s, s)
                detectIn(tile, x, y, tileMinScore, all, source)
                tile.recycle()
                tiles++
            }
        }
        val keep = mutableListOf<Cand>()
        for (c in all.sortedByDescending { it.score }) {
            if (keep.any { it.duplicateOf(c) }) continue
            keep.add(c)
        }
        Log.i("FaceScan", "tiles=$tiles tile_ms=${SystemClock.elapsedRealtime() - t0} raw=${all.size} kept=${keep.size}")
        // Preserve every primary result and its landmarks. Automatic scans only fall back for
        // empty photos; explicit Look again is allowed to search for smaller companions too.
        // Old zero-face scan records stay intact; use Look again for those, or the existing developer reset.
        if ((keep.isEmpty() || minConfidence < 0.6f) && allowFallback && fullRangeEnabled && !interrupted && !shouldStop()) {
            val start = SystemClock.elapsedRealtime()
            Log.i("FaceScan", "full_range_fallback_start")
            val rescued = detectAll(bitmap, fullRange(), false)
            Log.i("FaceScan", "full_range_fallback faces=${rescued.size} ms=${SystemClock.elapsedRealtime() - start}")
            // Explicit Look again may add small companions even if the primary found a large face.
            // Keep all primary coordinates/landmarks; only append non-overlapping full-range detections.
            for (c in rescued) if (keep.none { it.duplicateOf(c) }) keep.add(c)
        }
        return keep
    }

    private fun spread(total: Int, size: Int, n: Int): List<Int> =
        if (total <= size) listOf(0) else (0 until n).map { it * (total - size) / (n - 1) }.distinct()

    private fun tileStarts(total: Int, size: Int, step: Int): List<Int> {
        val out = mutableListOf<Int>()
        var p = 0
        while (p + size < total) { out.add(p); p += step }
        out.add(maxOf(0, total - size))
        return out
    }

    fun process(bitmap: Bitmap): List<Found> {
        val t0 = SystemClock.elapsedRealtime()
        val cands = detectAll(bitmap)
        val detectMs = SystemClock.elapsedRealtime() - t0
        var embedMs = 0L
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val out = mutableListOf<Found>()
        for (c in cands) {
            // Full-range is trained to localise smaller faces; keep the old primary gate unchanged.
            // Small fallback faces are still guesses, not user-confirmed identities.
            val minSize = if (c.fromFullRange) MIN_FULL_RANGE_FACE_PX else MIN_FACE_PX
            if (c.right - c.left < minSize || c.bottom - c.top < minSize) continue
            val src = floatArrayOf(c.rEyeX, c.rEyeY, c.lEyeX, c.lEyeY, c.mouthX, c.mouthY)
            val dst = floatArrayOf(38.29f, 51.70f, 73.53f, 51.50f, 56.14f, 92.28f)
            val m = Matrix()
            if (!m.setPolyToPoly(src, 0, dst, 0, 3)) continue
            val aligned = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
            Canvas(aligned).drawBitmap(bitmap, m, Paint(Paint.FILTER_BITMAP_FLAG))
            val t1 = SystemClock.elapsedRealtime()
            val emb = embed(aligned)
            embedMs += SystemClock.elapsedRealtime() - t1
            val baos = ByteArrayOutputStream()
            aligned.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            aligned.recycle()
            out.add(Found(c.left / w, c.top / h, c.right / w, c.bottom / h, emb, baos.toByteArray()))
        }
        Log.i("FaceScan", "bitmap ${bitmap.width}x${bitmap.height} detect=${detectMs}ms faces=${out.size} embed_total=${embedMs}ms")
        return out
    }

    private fun embed(face: Bitmap): FloatArray {
        val px = IntArray(INPUT * INPUT)
        face.getPixels(px, 0, INPUT, 0, 0, INPUT, INPUT)
        input.rewind()
        for (p in px) {
            input.putFloat((((p shr 16) and 0xFF) - 127.5f) / 127.5f)
            input.putFloat((((p shr 8) and 0xFF) - 127.5f) / 127.5f)
            input.putFloat(((p and 0xFF) - 127.5f) / 127.5f)
        }
        input.rewind()
        val output = Array(1) { FloatArray(embDim) }
        interpreter.run(input, output)
        return FaceMath.normalized(output[0])
    }

    override fun close() {
        try { detector.close() } catch (_: Exception) {}
        try { fullRangeDetector?.close() } catch (_: Exception) {}
        try { interpreter.close() } catch (_: Exception) {}
    }

    companion object {
        const val FULL_RANGE_ASSET = "blaze_face_full_range.tflite"
        const val DETECTOR_ASSET = "blaze_face_short_range.tflite"
        const val EMBEDDER_ASSET = "auraface_glintr100.tflite"
        private const val INPUT = 112
        private const val MIN_FACE_PX = 48f
        private const val MIN_FULL_RANGE_FACE_PX = 24f
        private const val TILE_FRACTION = 0.5f
        private const val BUDGET_TILE_FRACTION = 0.6f
        private const val TILE_MIN_SIDE = 400
        private const val MAX_TILES = 24
        private const val TILE_MIN_SCORE = 0.85f
    }
}
