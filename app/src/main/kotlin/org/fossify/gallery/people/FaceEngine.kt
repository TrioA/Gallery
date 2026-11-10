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
class FaceEngine(context: Context) : Closeable {

    class Found(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val embedding: FloatArray, val thumb: ByteArray,
    )

    private val detector: FaceDetector
    private val interpreter: Interpreter
    private val embDim: Int
    private val input: ByteBuffer = ByteBuffer.allocateDirect(INPUT * INPUT * 3 * 4).order(ByteOrder.nativeOrder())

    init {
        val options = FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(DETECTOR_ASSET).build())
            .setMinDetectionConfidence(0.6f)
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

    fun process(bitmap: Bitmap): List<Found> {
        val t0 = SystemClock.elapsedRealtime()
        val result = detector.detect(BitmapImageBuilder(bitmap).build())
        val detectMs = SystemClock.elapsedRealtime() - t0
        var embedMs = 0L
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val out = mutableListOf<Found>()
        for (det in result.detections()) {
            val box = det.boundingBox()
            if (box.width() < MIN_FACE_PX || box.height() < MIN_FACE_PX) continue
            val kps = det.keypoints()
            if (!kps.isPresent || kps.get().size < 4) continue
            val k = kps.get()
            // BlazeFace order: right eye, left eye, nose tip, mouth centre (image coords, normalised)
            val src = floatArrayOf(
                k[0].x() * w, k[0].y() * h,
                k[1].x() * w, k[1].y() * h,
                k[3].x() * w, k[3].y() * h,
            )
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
            out.add(Found(box.left / w, box.top / h, box.right / w, box.bottom / h, emb, baos.toByteArray()))
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
        try { interpreter.close() } catch (_: Exception) {}
    }

    companion object {
        const val DETECTOR_ASSET = "blaze_face_short_range.tflite"
        const val EMBEDDER_ASSET = "auraface_glintr100.tflite"
        private const val INPUT = 112
        private const val MIN_FACE_PX = 48f
    }
}
