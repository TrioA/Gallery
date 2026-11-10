package org.fossify.gallery.people

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.util.concurrent.CopyOnWriteArraySet

/** Incremental on-device scan of the photo library. Resumable: finished photos are remembered. */
object FaceScanner {
    const val MATCH_THRESHOLD = 0.42f
    private const val MAX_SIDE = 1280

    @Volatile var running = false
        private set
    @Volatile private var stopRequested = false
    @Volatile var done = 0
        private set
    @Volatile var total = 0
        private set
    @Volatile var lastError: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArraySet<() -> Unit>()

    private fun notifyChanged() = main.post { listeners.forEach { it() } }

    fun stop() {
        stopRequested = true
    }

    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        running = true
        stopRequested = false
        lastError = null
        done = 0
        total = 0
        notifyChanged()
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            run(app)
        }, "face-scan").start()
    }

    private class Item(val id: Long, val path: String, val mtime: Long)

    private fun run(context: Context) {
        var engine: FaceEngine? = null
        try {
            val store = PeopleStore.get(context)
            val pending = mutableListOf<Item>()
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_MODIFIED)
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                MediaStore.Images.Media.DATE_MODIFIED + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(1) ?: continue
                    val item = Item(c.getLong(0), path, c.getLong(2))
                    if (!store.isScanned(item.path, item.mtime)) pending.add(item)
                }
            }
            total = pending.size
            notifyChanged()
            if (pending.isEmpty()) return

            engine = FaceEngine(context)
            val centroids = store.loadCentroids()
            for (item in pending) {
                if (stopRequested) break
                var found = 0
                try {
                    val bmp = decode(context, item.id)
                    if (bmp != null) {
                        for (f in engine.process(bmp)) {
                            assign(store, centroids, item.path, f)
                            found++
                        }
                        bmp.recycle()
                    }
                } catch (e: Exception) {
                    // unreadable photo: remember it so we do not retry on every scan
                }
                store.markScanned(item.path, item.mtime, found)
                done++
                if (done % 3 == 0) notifyChanged()
            }
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName + ": " + (t.message ?: "")
        } finally {
            try { engine?.close() } catch (_: Exception) {}
            running = false
            notifyChanged()
        }
    }

    /** Re-examine one photo with a more sensitive detector and add faces not already known. Blocking. */
    fun rescanPhoto(context: Context, path: String): Int {
        val app = context.applicationContext
        val store = PeopleStore.get(app)
        val existing = store.facesOfPath(path)
        val bmp = decodeFile(path) ?: return 0
        var added = 0
        FaceEngine(app, 0.3f).use { engine ->
            val centroids = store.loadCentroids()
            for (f in engine.process(bmp)) {
                val cx = (f.left + f.right) / 2
                val cy = (f.top + f.bottom) / 2
                if (existing.any { cx in it.left..it.right && cy in it.top..it.bottom }) continue
                assign(store, centroids, path, f)
                added++
            }
        }
        bmp.recycle()
        return added
    }

    /**
     * The user pointed at a face the detector missed. Try crops of several sizes around the tap
     * (normalized 0..1 coordinates of the upright photo) with a very permissive detector.
     * Returns 1 if a face was added, 2 if that face was already known, 0 if none could be found there, -1 on error.
     */
    fun addFaceAt(context: Context, path: String, nx: Float, ny: Float): Int {
        val app = context.applicationContext
        val store = PeopleStore.get(app)
        val bmp = decodeFile(path) ?: return -1
        try {
            val cx = nx * bmp.width
            val cy = ny * bmp.height
            val m = minOf(bmp.width, bmp.height).toFloat()
            FaceEngine(app, 0.1f).use { engine ->
                val centroids = store.loadCentroids()
                for (frac in floatArrayOf(0.35f, 0.6f, 0.9f)) {
                    val side = (m * frac).toInt().coerceAtLeast(64)
                    val x0 = (cx - side / 2f).toInt().coerceIn(0, maxOf(0, bmp.width - side))
                    val y0 = (cy - side / 2f).toInt().coerceIn(0, maxOf(0, bmp.height - side))
                    val w = minOf(side, bmp.width - x0)
                    val h = minOf(side, bmp.height - y0)
                    val crop = Bitmap.createBitmap(bmp, x0, y0, w, h)
                    val found = engine.process(crop)
                    crop.recycle()
                    val bw = bmp.width.toFloat()
                    val bh = bmp.height.toFloat()
                    val hit = found.minByOrNull {
                        val fx = (x0 + (it.left + it.right) / 2 * w) / bw
                        val fy = (y0 + (it.top + it.bottom) / 2 * h) / bh
                        (fx - nx) * (fx - nx) + (fy - ny) * (fy - ny)
                    } ?: continue
                    val hcx = (x0 + (hit.left + hit.right) / 2 * w) / bw
                    val hcy = (y0 + (hit.top + hit.bottom) / 2 * h) / bh
                    if (store.facesOfPath(path).any { hcx in it.left..it.right && hcy in it.top..it.bottom }) return 2
                    val moved = FaceEngine.Found(
                        (x0 + hit.left * w) / bw, (y0 + hit.top * h) / bh,
                        (x0 + hit.right * w) / bw, (y0 + hit.bottom * h) / bh,
                        hit.embedding, hit.thumb
                    )
                    assign(store, centroids, path, moved)
                    return 1
                }
            }
            return 0
        } finally {
            bmp.recycle()
        }
    }

    fun decodeFile(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        val orientation = try {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            else -> return bmp
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated != bmp) bmp.recycle()
        return rotated
    }

    private fun assign(store: PeopleStore, centroids: MutableList<PeopleStore.Centroid>, path: String, f: FaceEngine.Found) {
        var best: PeopleStore.Centroid? = null
        var bestSim = -1f
        for (c in centroids) {
            val s = FaceMath.dot(c.vec, f.embedding)
            if (s > bestSim) { bestSim = s; best = c }
        }
        val target: PeopleStore.Centroid
        if (best != null && bestSim >= MATCH_THRESHOLD) {
            target = best
            val sum = FloatArray(target.vec.size) { target.vec[it] * target.count + f.embedding[it] }
            target.vec = FaceMath.normalized(sum)
            target.count += 1
        } else {
            val id = store.createPerson(f.embedding)
            target = PeopleStore.Centroid(id, f.embedding.copyOf(), 1)
            centroids.add(target)
        }
        store.addFace(path, f.left, f.top, f.right, f.bottom, f.embedding, target.id, f.thumb)
        store.updateCentroid(target.id, target.vec, target.count)
    }

    private fun decode(context: Context, id: Long): Bitmap? {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            else -> return bmp
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated != bmp) bmp.recycle()
        return rotated
    }
}
