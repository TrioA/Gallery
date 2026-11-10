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
        Thread({ run(app) }, "face-scan").start()
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
