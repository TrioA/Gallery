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
    val aliases: String = "",
) {
    fun displayName(): String = when {
        name.isNotBlank() -> name
        contactName.isNotBlank() -> contactName
        else -> "Person $id"
    }
}

class PeopleStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "people.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE people (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL DEFAULT ''," +
                " contact_key TEXT NOT NULL DEFAULT '', contact_name TEXT NOT NULL DEFAULT ''," +
                " centroid BLOB, face_count INTEGER NOT NULL DEFAULT 0, aliases TEXT NOT NULL DEFAULT ''," +
                " merged_into INTEGER NOT NULL DEFAULT 0)"
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive only: existing people, faces and corrections are untouched.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE people ADD COLUMN aliases TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE people ADD COLUMN merged_into INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Developer reset: clears ONLY the People recognition data (groups, names, aliases, contact links,
     * faces, corrections, embeddings, scan memory). Never touches photos or phone contacts.
     * The epoch is bumped first so any in-flight scan work sees it and writes nothing more.
     */
    fun resetAll() {
        epoch++
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM faces")
            db.execSQL("DELETE FROM people")
            db.execSQL("DELETE FROM scanned")
            db.execSQL("DELETE FROM sqlite_sequence WHERE name IN ('people','faces')")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

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
    /** A person as the matcher sees it: average face plus a few real example faces (exemplars). */
    class Centroid(val id: Long, var vec: FloatArray, var count: Int, val exemplars: MutableList<FloatArray> = mutableListOf())

    fun loadCentroids(): MutableList<Centroid> {
        val out = mutableListOf<Centroid>()
        readableDatabase.rawQuery("SELECT id, centroid, face_count FROM people WHERE centroid IS NOT NULL AND face_count > 0", null).use {
            while (it.moveToNext()) out.add(Centroid(it.getLong(0), bytesToFloats(it.getBlob(1)), it.getInt(2)))
        }
        // Exemplars: every face the user confirmed or corrected (capped) plus the latest guesses.
        val byId = out.associateBy { it.id }
        val confirmedLeft = HashMap<Long, Int>()
        val guessLeft = HashMap<Long, Int>()
        readableDatabase.rawQuery("SELECT person_id, emb, confirmed FROM faces ORDER BY confirmed DESC, id DESC", null).use {
            while (it.moveToNext()) {
                val c = byId[it.getLong(0)] ?: continue
                val confirmed = it.getInt(2) == 1
                val left = if (confirmed) confirmedLeft else guessLeft
                val n = left.getOrDefault(c.id, 0)
                if (n >= (if (confirmed) MAX_CONFIRMED_EXEMPLARS else MAX_GUESS_EXEMPLARS)) continue
                left[c.id] = n + 1
                c.exemplars.add(bytesToFloats(it.getBlob(1)))
            }
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

    /** Follow merge redirects so a scanner holding an old group id never writes into a merged-away group. */
    fun resolve(id: Long): Long {
        var cur = id
        for (i in 0 until 20) {
            var next = 0L
            readableDatabase.rawQuery("SELECT merged_into FROM people WHERE id=?", arrayOf(cur.toString())).use {
                if (it.moveToFirst()) next = it.getLong(0)
            }
            if (next <= 0L) return cur
            cur = next
        }
        return cur
    }

    /** Returns the person id the face was really stored under (differs from [personId] if that group was merged meanwhile). */
    fun addFace(path: String, l: Float, t: Float, r: Float, b: Float, emb: FloatArray, personId: Long, thumb: ByteArray): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val target = resolve(personId)
            val v = ContentValues().apply {
                put("path", path); put("box_l", l); put("box_t", t); put("box_r", r); put("box_b", b)
                put("emb", floatsToBytes(emb)); put("person_id", target); put("thumb", thumb)
            }
            db.insert("faces", null, v)
            db.setTransactionSuccessful()
            return target
        } finally {
            db.endTransaction()
        }
    }

    fun listPeople(): List<Person> {
        val out = mutableListOf<Person>()
        readableDatabase.rawQuery(
            "SELECT id, name, contact_key, contact_name, face_count, aliases FROM people WHERE face_count > 0 ORDER BY face_count DESC",
            null
        ).use {
            while (it.moveToNext()) out.add(Person(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getInt(4), it.getString(5)))
        }
        return out
    }

    fun getPerson(id: Long): Person? {
        readableDatabase.rawQuery(
            "SELECT id, name, contact_key, contact_name, face_count, aliases FROM people WHERE id=?", arrayOf(id.toString())
        ).use {
            return if (it.moveToFirst()) Person(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getInt(4), it.getString(5)) else null
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

    /** Name a person. Equal names do NOT merge groups: several groups may share a name. */
    fun setName(personId: Long, name: String): Long {
        val v = ContentValues().apply { put("name", name.trim()) }
        writableDatabase.update("people", v, "id=?", arrayOf(personId.toString()))
        return personId
    }

    /**
     * Link a phone contact. One contact owns at most one group: if another group already has this
     * contact, the two are merged (faces, confirmations and names are kept). Returns the surviving person id.
     */
    fun setContact(personId: Long, lookupKey: String, displayName: String): Long {
        val v = ContentValues().apply { put("contact_key", lookupKey); put("contact_name", displayName) }
        writableDatabase.update("people", v, "id=?", arrayOf(personId.toString()))
        if (lookupKey.isBlank()) return personId
        readableDatabase.rawQuery(
            "SELECT id FROM people WHERE id<>? AND contact_key=? AND face_count>0 ORDER BY face_count DESC LIMIT 1",
            arrayOf(personId.toString(), lookupKey)
        ).use {
            if (it.moveToFirst()) {
                val other = it.getLong(0)
                return if (mergePeople(personId, other)) other else personId
            }
        }
        return personId
    }

    /**
     * Merge several groups into one: all faces move to the survivor and are marked confirmed (the user
     * said they are the same person). Refuses (returns -1) when the groups are linked to different contacts.
     */
    fun mergeMany(ids: List<Long>): Long {
        val people = ids.distinct().mapNotNull { getPerson(it) }.filter { it.faceCount > 0 }
        if (people.size < 2) return people.firstOrNull()?.id ?: -1L
        if (people.map { it.contactKey }.filter { it.isNotBlank() }.distinct().size > 1) return -1L
        val target = people.firstOrNull { it.contactKey.isNotBlank() } ?: people.maxByOrNull { it.faceCount }!!
        for (p in people) if (p.id != target.id) mergePeople(p.id, target.id)
        return target.id
    }

    class MergePlan(val survivor: Person?, val others: List<Person>, val aliasNames: List<String>, val conflictingContacts: List<String>)

    /** What merging these groups would do, for the review dialog. */
    fun mergePlan(ids: List<Long>): MergePlan {
        val people = ids.distinct().mapNotNull { getPerson(it) }.filter { it.faceCount > 0 }
        val contacts = people.filter { it.contactKey.isNotBlank() }.distinctBy { it.contactKey }.map { it.contactName.ifBlank { "a contact" } }
        val target = people.firstOrNull { it.contactKey.isNotBlank() } ?: people.maxByOrNull { it.faceCount }
        val others = people.filter { it.id != target?.id }
        val aliasNames = others.map { it.name }.filter { it.isNotBlank() && !it.equals(target?.name, true) }
        return MergePlan(target, others, aliasNames, if (contacts.size > 1) contacts else emptyList())
    }

    /** Groups that already share a contact (from older versions): merge each set into one. Returns how many merges happened. */
    fun consolidateContacts(): Int {
        val groups = HashMap<String, MutableList<Long>>()
        readableDatabase.rawQuery("SELECT contact_key, id FROM people WHERE contact_key<>'' AND face_count>0", null).use {
            while (it.moveToNext()) groups.getOrPut(it.getString(0)) { mutableListOf() }.add(it.getLong(1))
        }
        var merged = 0
        for ((_, ids) in groups) if (ids.size > 1) { mergeMany(ids); merged += ids.size - 1 }
        return merged
    }

    /** Groups whose average face is close to this one: only a suggestion for the user to review. */
    fun similarTo(id: Long, threshold: Float): List<Long> {
        val all = loadCentroids()
        val me = all.firstOrNull { it.id == id } ?: return emptyList()
        return all.filter { it.id != id && FaceMath.dot(it.vec, me.vec) >= threshold }.map { it.id }
    }

    /** Embeddings of faces in this photo that the user confirmed or corrected. */
    fun confirmedEmbeddings(path: String): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        readableDatabase.rawQuery("SELECT emb FROM faces WHERE path=? AND confirmed=1", arrayOf(path)).use {
            while (it.moveToNext()) out.add(bytesToFloats(it.getBlob(0)))
        }
        return out
    }

    /**
     * A photo changed on disk and is being scanned again. Drop only the app's own unconfirmed
     * guesses for it; faces the user confirmed or corrected stay. Returns true if anything changed.
     */
    fun dropGuessesOfPath(path: String): Boolean {
        val affected = mutableSetOf<Long>()
        readableDatabase.rawQuery("SELECT DISTINCT person_id FROM faces WHERE path=? AND confirmed=0", arrayOf(path)).use {
            while (it.moveToNext()) affected.add(it.getLong(0))
        }
        if (affected.isEmpty()) return false
        writableDatabase.delete("faces", "path=? AND confirmed=0", arrayOf(path))
        for (id in affected) recomputeCentroid(id)
        return true
    }

    /**
     * Move all faces of [from] into [into] (marked confirmed); the centroid becomes the weighted mean.
     * [into] keeps its own name and contact; if it has none it takes [from]'s. Returns false if the two
     * are linked to different contacts (nothing changes).
     */
    fun mergePeople(from: Long, into: Long): Boolean {
        if (from == into) return true
        val pf = getPerson(from) ?: return false
        val pi = getPerson(into) ?: return false
        if (pf.contactKey.isNotBlank() && pi.contactKey.isNotBlank() && pf.contactKey != pi.contactKey) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val a = loadCentroidOf(from)
            val b = loadCentroidOf(into)
            db.execSQL("UPDATE faces SET person_id=?, confirmed=1 WHERE person_id=?", arrayOf<Any>(into, from))
            if (a != null && b != null && a.vec.size == b.vec.size) {
                val sum = FloatArray(a.vec.size) { a.vec[it] * a.count + b.vec[it] * b.count }
                updateCentroid(into, FaceMath.normalized(sum), a.count + b.count)
            } else {
                recomputeCentroid(into)
            }
            val keep = ContentValues().apply {
                if (pi.name.isBlank() && pf.name.isNotBlank()) put("name", pf.name)
                // Names are never dropped: a different name on the merged group becomes an alias of the survivor.
                val names = (listOf(pi.aliases, pf.aliases).flatMap { it.split("|") } +
                    (if (pi.name.isNotBlank() && pf.name.isNotBlank() && !pi.name.equals(pf.name, true)) listOf(pf.name) else emptyList()))
                    .map { it.trim() }.filter { it.isNotEmpty() && !it.equals(if (pi.name.isNotBlank()) pi.name else pf.name, true) }
                    .distinctBy { it.lowercase() }
                put("aliases", names.joinToString("|"))
                if (pi.contactKey.isBlank() && pf.contactKey.isNotBlank()) { put("contact_key", pf.contactKey); put("contact_name", pf.contactName) }
            }
            if (keep.size() > 0) db.update("people", keep, "id=?", arrayOf(into.toString()))
            val v = ContentValues().apply { put("face_count", 0); put("contact_key", ""); put("contact_name", ""); put("name", ""); put("aliases", ""); put("merged_into", into) }
            db.update("people", v, "id=?", arrayOf(from.toString()))
            db.setTransactionSuccessful()
            return true
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
        val target = if (toPersonId == 0L) createPerson(e) else resolve(toPersonId)
        val v = ContentValues().apply { put("person_id", target); put("confirmed", 1) }
        writableDatabase.update("faces", v, "id=?", arrayOf(faceId.toString()))
        recomputeCentroid(target)
        if (fromId > 0) recomputeCentroid(fromId)
        return target
    }

    companion object {
        @Volatile var epoch = 0

        const val MAX_CONFIRMED_EXEMPLARS = 40
        const val MAX_GUESS_EXEMPLARS = 12
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
