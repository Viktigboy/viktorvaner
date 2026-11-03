package com.johnsrud.lister

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

data class ShopList(val id: String, val name: String, val icon: String, val created: Long)
data class Item(val id: String, val listId: String, val name: String, val done: Boolean, val created: Long)

/** Same screens work for shared (cloud) and private (this phone only) lists. */
interface Backend {
    val lists: List<ShopList>
    val items: List<Item>
    val loaded: Boolean
    val error: String?
    fun addList(name: String, icon: String)
    fun updateList(id: String, name: String, icon: String)
    fun deleteList(id: String)
    fun addItem(listId: String, name: String)
    fun toggle(item: Item)
    fun deleteItem(item: Item)
    fun clearDone(listId: String)
}

// ───────────── Johnsrudlister: shared with everyone who has the app (Firestore) ─────────────

class Repo private constructor(ctx: Context) : Backend {
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private var regs: List<ListenerRegistration> = emptyList()

    private var _lists by mutableStateOf(listOf<ShopList>())
    private var _items by mutableStateOf(listOf<Item>())
    private var _loaded by mutableStateOf(false)
    private var _error by mutableStateOf<String?>(null)
    private var bgStamp = -1L

    var background by mutableStateOf<ImageBitmap?>(null)
        private set

    override val lists: List<ShopList> get() = _lists
    override val items: List<Item> get() = _items
    override val loaded: Boolean get() = _loaded
    override val error: String? get() = _error

    init { attach() }

    private fun col(name: String) = db.collection("families").document(SPACE).collection(name)

    private fun decode(b64: String): ImageBitmap? = try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (e: Exception) { null }

    private fun attach() {
        val l = col("lists").addSnapshotListener { snap, e ->
            if (e != null) { _error = e.message; return@addSnapshotListener }
            if (snap != null) {
                _error = null
                _lists = snap.documents.map { d ->
                    ShopList(d.id, d.getString("name") ?: "", d.getString("icon") ?: "🛒", d.getLong("created") ?: 0L)
                }.sortedBy { it.created }
                _loaded = true
            }
        }
        val i = col("items").addSnapshotListener { snap, e ->
            if (e != null) { _error = e.message; return@addSnapshotListener }
            if (snap != null) {
                _items = snap.documents.map { d ->
                    Item(d.id, d.getString("listId") ?: "", d.getString("name") ?: "",
                        d.getBoolean("done") ?: false, d.getLong("created") ?: 0L)
                }
            }
        }
        val b = col("settings").document("background").addSnapshotListener { snap, e ->
            if (e != null || snap == null) return@addSnapshotListener
            val data = snap.getString("data")
            if (data.isNullOrEmpty()) {
                background = null
                bgStamp = -1L
            } else {
                val st = snap.getLong("updated") ?: 0L
                if (st != bgStamp || background == null) {
                    background = decode(data)
                    bgStamp = st
                }
            }
        }
        regs = listOf(l, i, b)
    }

    /** Saves a new background that everyone sees. */
    fun setBackground(b64: String) {
        col("settings").document("background")
            .set(mapOf("data" to b64, "updated" to System.currentTimeMillis()))
    }

    fun removeBackground() { col("settings").document("background").delete() }

    override fun addList(name: String, icon: String) {
        col("lists").document(UUID.randomUUID().toString())
            .set(mapOf("name" to name, "icon" to icon, "created" to System.currentTimeMillis()))
    }

    override fun updateList(id: String, name: String, icon: String) {
        col("lists").document(id).update(mapOf("name" to name, "icon" to icon))
    }

    override fun deleteList(id: String) {
        val b = db.batch()
        _items.filter { it.listId == id }.forEach { b.delete(col("items").document(it.id)) }
        b.delete(col("lists").document(id))
        b.commit()
    }

    override fun addItem(listId: String, name: String) {
        col("items").document(UUID.randomUUID().toString()).set(
            mapOf("listId" to listId, "name" to name, "done" to false, "created" to System.currentTimeMillis())
        )
    }

    override fun toggle(item: Item) { col("items").document(item.id).update("done", !item.done) }

    override fun deleteItem(item: Item) { col("items").document(item.id).delete() }

    override fun clearDone(listId: String) {
        val b = db.batch()
        _items.filter { it.listId == listId && it.done }.forEach { b.delete(col("items").document(it.id)) }
        b.commit()
    }

    companion object {
        const val SPACE = "johnsrud"
        @Volatile private var inst: Repo? = null
        fun get(c: Context): Repo = inst ?: synchronized(this) { inst ?: Repo(c).also { inst = it } }
    }
}

// ───────────── Private lister: only stored on this phone ─────────────

class LocalRepo private constructor(ctx: Context) : Backend {
    private val prefs = ctx.applicationContext.getSharedPreferences("private_lists", Context.MODE_PRIVATE)
    private var _lists by mutableStateOf(listOf<ShopList>())
    private var _items by mutableStateOf(listOf<Item>())

    override val lists: List<ShopList> get() = _lists
    override val items: List<Item> get() = _items
    override val loaded: Boolean get() = true
    override val error: String? get() = null

    init { load() }

    private fun load() {
        try {
            val a = JSONArray(prefs.getString("lists", "[]"))
            _lists = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                ShopList(o.getString("id"), o.getString("name"), o.optString("icon", "🛒"), o.optLong("created", 0L))
            }
        } catch (e: Exception) { }
        try {
            val a = JSONArray(prefs.getString("items", "[]"))
            _items = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Item(o.getString("id"), o.getString("listId"), o.getString("name"), o.optBoolean("done", false), o.optLong("created", 0L))
            }
        } catch (e: Exception) { }
    }

    private fun save() {
        val l = JSONArray()
        _lists.forEach { l.put(JSONObject().put("id", it.id).put("name", it.name).put("icon", it.icon).put("created", it.created)) }
        val i = JSONArray()
        _items.forEach { i.put(JSONObject().put("id", it.id).put("listId", it.listId).put("name", it.name).put("done", it.done).put("created", it.created)) }
        prefs.edit().putString("lists", l.toString()).putString("items", i.toString()).apply()
    }

    override fun addList(name: String, icon: String) {
        _lists = _lists + ShopList(UUID.randomUUID().toString(), name, icon, System.currentTimeMillis())
        save()
    }

    override fun updateList(id: String, name: String, icon: String) {
        _lists = _lists.map { if (it.id == id) it.copy(name = name, icon = icon) else it }
        save()
    }

    override fun deleteList(id: String) {
        _lists = _lists.filter { it.id != id }
        _items = _items.filter { it.listId != id }
        save()
    }

    override fun addItem(listId: String, name: String) {
        _items = _items + Item(UUID.randomUUID().toString(), listId, name, false, System.currentTimeMillis())
        save()
    }

    override fun toggle(item: Item) {
        _items = _items.map { if (it.id == item.id) it.copy(done = !it.done) else it }
        save()
    }

    override fun deleteItem(item: Item) {
        _items = _items.filter { it.id != item.id }
        save()
    }

    override fun clearDone(listId: String) {
        _items = _items.filter { !(it.listId == listId && it.done) }
        save()
    }

    companion object {
        @Volatile private var inst: LocalRepo? = null
        fun get(c: Context): LocalRepo = inst ?: synchronized(this) { inst ?: LocalRepo(c).also { inst = it } }
    }
}

// ───────────── Makes the chosen photo portrait (9:19) and small enough for one Firestore document ─────────────

fun prepareBackground(ctx: Context, uri: Uri): String? {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3000) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp: Bitmap = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        // 1. Respect the camera's rotation info, so photos are the right way up.
        val orientation = try {
            ctx.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) {
            val m = Matrix()
            m.postRotate(degrees)
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }

        // 2. Crop the centre to a portrait shape (9:19), no matter what the original shape was.
        val ratio = 9f / 19f
        var cw = bmp.width
        var ch = bmp.height
        if (cw.toFloat() / ch > ratio) cw = (ch * ratio).toInt() else ch = (cw / ratio).toInt()
        cw = cw.coerceAtLeast(1)
        ch = ch.coerceAtLeast(1)
        bmp = Bitmap.createBitmap(bmp, (bmp.width - cw) / 2, (bmp.height - ch) / 2, cw, ch)

        // 3. Shrink to at most 720 x 1520.
        if (bmp.height > 1520) {
            bmp = Bitmap.createScaledBitmap(bmp, 720, 1520, true)
        }

        // 4. Compress until it is small enough to store.
        var q = 80
        var bytes: ByteArray
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
            bytes = out.toByteArray()
            if (bytes.size <= 550_000) break
            if (q > 45) q -= 10
            else bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * 0.8f).toInt(), (bmp.height * 0.8f).toInt(), true)
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (e: Exception) {
        return null
    }
}
