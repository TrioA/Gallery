package org.fossify.gallery.people

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Person(
    val id: Long,
    val name: String,
    val contactKey: String,
    val contactName: String,
    val faceCount: Int,
) {
    fun displayName(): String = when {
        name.isNotBlank() -> name
        contactName.isNotBlank() -> contactName
        else -> "Person $id"
    }
}

class PeopleStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "people.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE people (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL DEFAULT ''," +
                " contact_key TEXT NOT NULL DEFAULT '', contact_name TEXT NOT NULL DEFAULT ''," +
                " centroid BLOB, face_count INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE faces (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL," +
                " box_l REAL, box_t REAL, box_r REAL, box_b REAL, emb BLOB NOT NULL," +
                " person_id INTEGER NOT NULL, confirmed INTEGER NOT NULL DEFAULT 0, thumb BLOB)"
        )
        db.execSQL("CREATE INDEX faces_person ON faces(person_id)")
        db.execSQL("CREATE INDEX faces_path ON faces(path)")
        db.execSQL("CREATE TABLE scanned (path TEXT PRIMARY KEY, mtime INTEGER NOT NULL, faces INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---- scanning state ----
    fun isScanned(path: String, mtime: Long): Boolean {
        readableDatabase.rawQuery("SELECT mtime FROM scanned WHERE path=?", arrayOf(path)).use {
            return it.moveToFirst() && it.getLong(0) == mtime
        }
    }

    fun markScanned(path: String, mtime: Long, faces: Int) {
        val v = ContentValues().apply {
            put("path", path); put("mtime", mtime); put("faces", faces)
        }
        writableDatabase.insertWithOnConflict("scanned", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun scannedCount(): Int = count("SELECT COUNT(*) FROM scanned")
    fun faceCount(): Int = count("SELECT COUNT(*) FROM faces")
    fun personCount(): Int = count("SELECT COUNT(*) FROM people WHERE face_count > 0")

    private fun count(sql: String): Int = readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---- people ----
    class Centroid(val id: Long, var vec: FloatArray, var count: Int)

    fun loadCentroids(): MutableList<Centroid> {
        val out = mutableListOf<Centroid>()
        readableDatabase.rawQuery("SELECT id, centroid, face_count FROM people WHERE centroid IS NOT NULL AND face_count > 0", null).use {
            while (it.moveToNext()) out.add(Centroid(it.getLong(0), bytesToFloats(it.getBlob(1)), it.getInt(2)))
        }
        return out
    }

    fun createPerson(centroid: FloatArray): Long {
        val v = ContentValues().apply {
            put("centroid", floatsToBytes(centroid)); put("face_count", 0)
        }
        return writableDatabase.insert("people", null, v)
    }

    fun updateCentroid(id: Long, centroid: FloatArray, faceCount: Int) {
        val v = ContentValues().apply {
            put("centroid", floatsToBytes(centroid)); put("face_count", faceCount)
        }
        writableDatabase.update("people", v, "id=?", arrayOf(id.toString()))
    }

    fun addFace(path: String, l: Float, t: Float, r: Float, b: Float, emb: FloatArray, personId: Long, thumb: ByteArray) {
        val v = ContentValues().apply {
            put("path", path); put("box_l", l); put("box_t", t); put("box_r", r); put("box_b", b)
            put("emb", floatsToBytes(emb)); put("person_id", personId); put("thumb", thumb)
        }
        writableDatabase.insert("faces", null, v)
    }

    fun listPeople(): List<Person> {
        val out = mutableListOf<Person>()
        readableDatabase.rawQuery(
            "SELECT id, name, contact_key, contact_name, face_count FROM people WHERE face_count > 0 ORDER BY face_count DESC",
            null
        ).use {
            while (it.moveToNext()) out.add(Person(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getInt(4)))
        }
        return out
    }

    fun getPerson(id: Long): Person? {
        readableDatabase.rawQuery(
            "SELECT id, name, contact_key, contact_name, face_count FROM people WHERE id=?", arrayOf(id.toString())
        ).use {
            return if (it.moveToFirst()) Person(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getInt(4)) else null
        }
    }

    fun coverThumb(personId: Long): ByteArray? {
        readableDatabase.rawQuery("SELECT thumb FROM faces WHERE person_id=? ORDER BY id LIMIT 1", arrayOf(personId.toString())).use {
            return if (it.moveToFirst()) it.getBlob(0) else null
        }
    }

    fun pathsOfPerson(personId: Long): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT DISTINCT path FROM faces WHERE person_id=? ORDER BY path DESC", arrayOf(personId.toString())).use {
            while (it.moveToNext()) out.add(it.getString(0))
        }
        return out
    }

    /** Name a person. If another person already has this exact name, the two are merged. */
    fun setName(personId: Long, name: String): Long {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) {
            readableDatabase.rawQuery(
                "SELECT id FROM people WHERE id<>? AND name=? COLLATE NOCASE AND face_count>0 LIMIT 1",
                arrayOf(personId.toString(), trimmed)
            ).use {
                if (it.moveToFirst()) {
                    val other = it.getLong(0)
                    mergePeople(personId, other)
                    return other
                }
            }
        }
        val v = ContentValues().apply { put("name", trimmed) }
        writableDatabase.update("people", v, "id=?", arrayOf(personId.toString()))
        return personId
    }

    fun setContact(personId: Long, lookupKey: String, displayName: String) {
        val v = ContentValues().apply { put("contact_key", lookupKey); put("contact_name", displayName) }
        writableDatabase.update("people", v, "id=?", arrayOf(personId.toString()))
    }

    /** Move all faces of [from] into [into]; centroid becomes the weighted mean. */
    fun mergePeople(from: Long, into: Long) {
        if (from == into) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val a = loadCentroidOf(from)
            val b = loadCentroidOf(into)
            db.execSQL("UPDATE faces SET person_id=? WHERE person_id=?", arrayOf<Any>(into, from))
            if (a != null && b != null && a.vec.size == b.vec.size) {
                val sum = FloatArray(a.vec.size) { a.vec[it] * a.count + b.vec[it] * b.count }
                updateCentroid(into, FaceMath.normalized(sum), a.count + b.count)
            }
            val v = ContentValues().apply { put("face_count", 0) }
            db.update("people", v, "id=?", arrayOf(from.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun loadCentroidOf(id: Long): Centroid? {
        readableDatabase.rawQuery("SELECT id, centroid, face_count FROM people WHERE id=? AND centroid IS NOT NULL", arrayOf(id.toString())).use {
            return if (it.moveToFirst()) Centroid(it.getLong(0), bytesToFloats(it.getBlob(1)), it.getInt(2)) else null
        }
    }

    // ---- per-photo faces and corrections ----
    class FaceRow(val id: Long, val personId: Long, val thumb: ByteArray?, val confirmed: Boolean,
                  val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun facesOfPath(path: String): List<FaceRow> {
        val out = mutableListOf<FaceRow>()
        readableDatabase.rawQuery(
            "SELECT id, person_id, thumb, confirmed, box_l, box_t, box_r, box_b FROM faces WHERE path=? ORDER BY box_l",
            arrayOf(path)
        ).use {
            while (it.moveToNext()) {
                out.add(FaceRow(it.getLong(0), it.getLong(1), it.getBlob(2), it.getInt(3) == 1,
                    it.getFloat(4), it.getFloat(5), it.getFloat(6), it.getFloat(7)))
            }
        }
        return out
    }

    fun confirmFace(faceId: Long) {
        val v = ContentValues().apply { put("confirmed", 1) }
        writableDatabase.update("faces", v, "id=?", arrayOf(faceId.toString()))
    }

    /** Recompute a person's centroid and face count from its faces. */
    fun recomputeCentroid(personId: Long) {
        val embs = mutableListOf<FloatArray>()
        readableDatabase.rawQuery("SELECT emb FROM faces WHERE person_id=?", arrayOf(personId.toString())).use {
            while (it.moveToNext()) embs.add(bytesToFloats(it.getBlob(0)))
        }
        if (embs.isEmpty()) {
            val v = ContentValues().apply { put("face_count", 0) }
            writableDatabase.update("people", v, "id=?", arrayOf(personId.toString()))
            return
        }
        val sum = FloatArray(embs[0].size)
        for (e in embs) for (i in sum.indices) sum[i] += e[i]
        updateCentroid(personId, FaceMath.normalized(sum), embs.size)
    }

    /** User says this face belongs to another person (or a new one when [toPersonId] is 0). Returns the target id. */
    fun moveFace(faceId: Long, toPersonId: Long): Long {
        var fromId = -1L
        var emb: FloatArray? = null
        readableDatabase.rawQuery("SELECT person_id, emb FROM faces WHERE id=?", arrayOf(faceId.toString())).use {
            if (it.moveToFirst()) { fromId = it.getLong(0); emb = bytesToFloats(it.getBlob(1)) }
        }
        val e = emb ?: return toPersonId
        val target = if (toPersonId == 0L) createPerson(e) else toPersonId
        val v = ContentValues().apply { put("person_id", target); put("confirmed", 1) }
        writableDatabase.update("faces", v, "id=?", arrayOf(faceId.toString()))
        recomputeCentroid(target)
        if (fromId > 0) recomputeCentroid(fromId)
        return target
    }

    companion object {
        @Volatile
        private var instance: PeopleStore? = null

        fun get(context: Context): PeopleStore =
            instance ?: synchronized(this) { instance ?: PeopleStore(context).also { instance = it } }

        fun floatsToBytes(f: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(f)
            return bb.array()
        }

        fun bytesToFloats(b: ByteArray): FloatArray {
            val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val out = FloatArray(fb.remaining())
            fb.get(out)
            return out
        }
    }
}
