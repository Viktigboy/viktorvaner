package com.viktorvaner.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class Habit(
    val id: String,
    val title: String,
    val desc: String,
    val icon: String,
    val archived: Boolean,
    val created: String,
    val auto: Boolean = false,
    val journal: Boolean = false
)

/** A journal note. Several per day are allowed. [date] is ISO (2026-11-06). */
data class Note(val id: String, val date: String, val title: String, val text: String, val created: Long)

/** The answer given when an automatic habit was un-checked. */
data class Failure(
    val id: String, val habitId: String, val habitTitle: String,
    val date: String, val text: String, val created: Long
)

/** "At least one word" = any non-blank text. */
fun hasWords(s: String) = s.trim().isNotEmpty()

/**
 * Everything is kept on the phone and also copied (encrypted) to Firebase.
 * Every piece of data is an "item" with a key and a time stamp, and the newest copy wins when
 * the phone and the cloud disagree. Keys: habit_<id>, marks_<habitId>, note_<id>, fail_<id>, order.
 */
class Store private constructor(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("viktorvaner", Context.MODE_PRIVATE)
    private val jPrefs = app.getSharedPreferences("journal", Context.MODE_PRIVATE)
    private val fPrefs = app.getSharedPreferences("failures", Context.MODE_PRIVATE)
    private val sPrefs = app.getSharedPreferences("sync", Context.MODE_PRIVATE)

    val cloud = Cloud(app)

    var habits by mutableStateOf(listOf<Habit>())
        private set
    var done by mutableStateOf(setOf<String>())
        private set
    /** For auto habits: days the user un-checked (failed). */
    var missed by mutableStateOf(setOf<String>())
        private set
    var notes by mutableStateOf(listOf<Note>())
        private set
    var failures by mutableStateOf(listOf<Failure>())
        private set

    private val stamps = HashMap<String, Long>()
    private val dead = HashMap<String, Long>()

    init {
        load()
        ensureJournalHabit()
        writePrefs()
    }

    private fun ensureJournalHabit() {
        if (habits.none { it.journal }) {
            val j = Habit(
                "journal", "Daglig journal", "Skriv minst ett ord hver dag", "📓",
                false, LocalDate.now().toString(), false, true
            )
            habits = listOf(j) + habits
        }
    }

    // ───────── Lagring på telefonen ─────────

    private fun readSet(key: String): Set<String> = try {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map { a.getString(it) }.toSet()
    } catch (e: Exception) { emptySet() }

    private fun parseHabit(o: JSONObject) = Habit(
        o.getString("id"), o.getString("title"), o.optString("desc", ""),
        o.optString("icon", "✅"), o.optBoolean("archived", false),
        o.optString("created", LocalDate.now().toString()),
        o.optBoolean("auto", false), o.optBoolean("journal", false)
    )

    private fun habitJson(h: Habit) = JSONObject().put("id", h.id).put("title", h.title).put("desc", h.desc)
        .put("icon", h.icon).put("archived", h.archived).put("created", h.created)
        .put("auto", h.auto).put("journal", h.journal)

    private fun parseNote(o: JSONObject) = Note(
        o.getString("id"), o.getString("date"), o.optString("title", ""),
        o.optString("text", ""), o.optLong("created", 0L)
    )

    private fun noteJson(n: Note) = JSONObject().put("id", n.id).put("date", n.date).put("title", n.title)
        .put("text", n.text).put("created", n.created)

    private fun parseFailure(o: JSONObject) = Failure(
        o.getString("id"), o.getString("habitId"), o.optString("habitTitle", ""),
        o.getString("date"), o.getString("text"), o.optLong("created", 0L)
    )

    private fun failureJson(f: Failure) = JSONObject().put("id", f.id).put("habitId", f.habitId)
        .put("habitTitle", f.habitTitle).put("date", f.date).put("text", f.text).put("created", f.created)

    private fun load() {
        try {
            val a = JSONArray(prefs.getString("habits", "[]"))
            habits = (0 until a.length()).map { parseHabit(a.getJSONObject(it)) }
        } catch (e: Exception) { }
        done = readSet("done")
        missed = readSet("missed")
        try {
            val a = JSONArray(jPrefs.getString("notes", "[]"))
            notes = (0 until a.length()).map { parseNote(a.getJSONObject(it)) }
        } catch (e: Exception) { }
        // Older version: one entry per day, stored as "entries".
        try {
            val old = jPrefs.getString("entries", null)
            if (old != null) {
                val a = JSONArray(old)
                val migrated = (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    Note("legacy-" + o.getString("date"), o.getString("date"), "", o.getString("text"), o.optLong("updated", 0L))
                }.filter { m -> notes.none { it.id == m.id } }
                notes = notes + migrated
                jPrefs.edit().putString("notes", notesArray().toString()).remove("entries").apply()
            }
        } catch (e: Exception) { }
        try {
            val a = JSONArray(fPrefs.getString("list", "[]"))
            failures = (0 until a.length()).map { parseFailure(a.getJSONObject(it)) }
        } catch (e: Exception) { }
        try {
            val o = JSONObject(sPrefs.getString("stamps", "{}"))
            o.keys().forEach { stamps[it] = o.getLong(it) }
            val d = JSONObject(sPrefs.getString("dead", "{}"))
            d.keys().forEach { dead[it] = d.getLong(it) }
        } catch (e: Exception) { }
    }

    private fun notesArray(): JSONArray {
        val a = JSONArray()
        notes.forEach { a.put(noteJson(it)) }
        return a
    }

    private fun writePrefs() {
        val a = JSONArray()
        habits.forEach { a.put(habitJson(it)) }
        prefs.edit().putString("habits", a.toString())
            .putString("done", JSONArray(done.toList()).toString())
            .putString("missed", JSONArray(missed.toList()).toString()).apply()
    }

    private fun writeNotes() {
        jPrefs.edit().putString("notes", notesArray().toString()).apply()
    }

    private fun writeFailures() {
        val a = JSONArray()
        failures.forEach { a.put(failureJson(it)) }
        fPrefs.edit().putString("list", a.toString()).apply()
    }

    private fun writeSync() {
        val s = JSONObject()
        stamps.forEach { (k, v) -> s.put(k, v) }
        val d = JSONObject()
        dead.forEach { (k, v) -> d.put(k, v) }
        sPrefs.edit().putString("stamps", s.toString()).putString("dead", d.toString()).apply()
    }

    private fun save() {
        writePrefs()
        CoroutineScope(Dispatchers.Default).launch {
            try { HabitWidget().updateAll(app) } catch (e: Exception) { }
        }
    }

    // ───────── Synk med skyen ─────────

    private fun datesOf(set: Set<String>, id: String): List<String> =
        set.filter { it.startsWith("$id|") }.map { it.substringAfter('|') }

    /** The current content of an item as JSON, or null if this phone does not have it. */
    private fun jsonOf(key: String): String? {
        val type = key.substringBefore('_')
        val id = key.substringAfter('_', "")
        return when (type) {
            "habit" -> habits.firstOrNull { it.id == id }?.let { habitJson(it).toString() }
            "marks" -> if (habits.any { it.id == id }) {
                JSONObject().put("done", JSONArray(datesOf(done, id))).put("missed", JSONArray(datesOf(missed, id))).toString()
            } else null
            "note" -> notes.firstOrNull { it.id == id }?.let { noteJson(it).toString() }
            "fail" -> failures.firstOrNull { it.id == id }?.let { failureJson(it).toString() }
            "order" -> JSONObject().put("ids", JSONArray(habits.map { it.id })).toString()
            else -> null
        }
    }

    /** Marks items as changed now and uploads them. */
    private fun touch(vararg keys: String) {
        val now = System.currentTimeMillis()
        keys.forEach { k ->
            stamps[k] = now
            dead.remove(k)
            jsonOf(k)?.let { cloud.push(k, it, now) }
        }
        writeSync()
    }

    /** Marks items as deleted now and uploads the deletion. */
    private fun kill(vararg keys: String) {
        val now = System.currentTimeMillis()
        keys.forEach { k ->
            stamps.remove(k)
            dead[k] = now
            cloud.push(k, null, now)
        }
        writeSync()
    }

    private class Work(
        val hs: MutableList<Habit>, val dn: MutableSet<String>, val ms: MutableSet<String>,
        val nl: MutableList<Note>, val fl: MutableList<Failure>
    )

    private fun applyRemote(w: Work, key: String, json: String?, ts: Long) {
        val type = key.substringBefore('_')
        val id = key.substringAfter('_', "")
        if (json == null) {
            when (type) {
                "habit" -> if (id != "journal") {
                    w.hs.removeAll { it.id == id }
                    w.dn.removeAll { it.startsWith("$id|") }
                    w.ms.removeAll { it.startsWith("$id|") }
                }
                "marks" -> {
                    w.dn.removeAll { it.startsWith("$id|") }
                    w.ms.removeAll { it.startsWith("$id|") }
                }
                "note" -> w.nl.removeAll { it.id == id }
                "fail" -> w.fl.removeAll { it.id == id }
            }
            stamps.remove(key)
            dead[key] = ts
            return
        }
        try {
            val o = JSONObject(json)
            when (type) {
                "habit" -> {
                    val h = parseHabit(o)
                    val i = w.hs.indexOfFirst { it.id == h.id }
                    if (i >= 0) w.hs[i] = h else w.hs.add(h)
                }
                "marks" -> {
                    w.dn.removeAll { it.startsWith("$id|") }
                    w.ms.removeAll { it.startsWith("$id|") }
                    val d = o.optJSONArray("done")
                    if (d != null) for (i in 0 until d.length()) w.dn.add(id + "|" + d.getString(i))
                    val m = o.optJSONArray("missed")
                    if (m != null) for (i in 0 until m.length()) w.ms.add(id + "|" + m.getString(i))
                }
                "note" -> {
                    val n = parseNote(o)
                    val i = w.nl.indexOfFirst { it.id == n.id }
                    if (i >= 0) w.nl[i] = n else w.nl.add(n)
                }
                "fail" -> {
                    val f = parseFailure(o)
                    val i = w.fl.indexOfFirst { it.id == f.id }
                    if (i >= 0) w.fl[i] = f else w.fl.add(f)
                }
                "order" -> {
                    val a = o.getJSONArray("ids")
                    val ids = (0 until a.length()).map { a.getString(it) }
                    w.hs.sortBy { h -> ids.indexOf(h.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                }
                else -> return
            }
            stamps[key] = ts
            dead.remove(key)
        } catch (e: Exception) { }
    }

    /**
     * Merges this phone with the cloud copy (newest wins per item) and uploads whatever the cloud
     * is missing. [onDone] gets the number of items found in the cloud and whether it worked.
     */
    fun syncCloud(onDone: (Int, Boolean) -> Unit = { _, _ -> }) {
        if (!cloud.available || cloud.code.isEmpty()) {
            onDone(0, false)
            return
        }
        cloud.fetch(
            onOk = { remote ->
                val w = Work(habits.toMutableList(), done.toMutableSet(), missed.toMutableSet(), notes.toMutableList(), failures.toMutableList())
                val keys = HashSet<String>()
                keys.addAll(remote.keys)
                keys.addAll(dead.keys)
                keys.add("order")
                habits.forEach { keys.add("habit_${it.id}"); keys.add("marks_${it.id}") }
                notes.forEach { keys.add("note_${it.id}") }
                failures.forEach { keys.add("fail_${it.id}") }
                var changed = false
                for (k in keys.sortedBy { if (it == "order") 1 else 0 }) {
                    val r = remote[k]
                    val localJson = jsonOf(k)
                    val isDead = dead.containsKey(k)
                    val lts = stamps[k] ?: dead[k] ?: 0L
                    if (r == null) {
                        if (localJson != null || isDead) cloud.push(k, localJson, lts)
                        continue
                    }
                    val missing = localJson == null && !isDead
                    if (r.updated > lts || (missing && r.json != null)) {
                        applyRemote(w, k, r.json, r.updated)
                        changed = true
                    } else if (lts > r.updated) {
                        cloud.push(k, localJson, lts)
                    }
                }
                if (changed) {
                    habits = w.hs
                    done = w.dn
                    missed = w.ms
                    notes = w.nl
                    failures = w.fl
                    ensureJournalHabit()
                    writeNotes()
                    writeFailures()
                    save()
                }
                writeSync()
                onDone(remote.size, true)
            },
            onErr = { onDone(0, false) }
        )
    }

    // ───────── Vaner ─────────

    fun add(title: String, desc: String, icon: String, auto: Boolean) {
        val h = Habit(UUID.randomUUID().toString(), title, desc, icon, false, LocalDate.now().toString(), auto)
        habits = habits + h
        save()
        touch("habit_${h.id}", "marks_${h.id}", "order")
    }

    fun update(id: String, title: String, desc: String, icon: String, auto: Boolean) {
        habits = habits.map { if (it.id == id && !it.journal) it.copy(title = title, desc = desc, icon = icon, auto = auto) else it }
        save()
        touch("habit_$id")
    }

    fun setArchived(id: String, archived: Boolean) {
        habits = habits.map { if (it.id == id && !it.journal) it.copy(archived = archived) else it }
        save()
        touch("habit_$id")
    }

    fun delete(id: String) {
        if (habits.any { it.id == id && it.journal }) return
        habits = habits.filter { it.id != id }
        done = done.filter { !it.startsWith("$id|") }.toSet()
        missed = missed.filter { !it.startsWith("$id|") }.toSet()
        save()
        kill("habit_$id", "marks_$id")
        touch("order")
    }

    /** Moves habit [fromId] to the position of [toId]. Call [commitOrder] when the drag ends. */
    fun reorder(fromId: String, toId: String) {
        val list = habits.toMutableList()
        val from = list.indexOfFirst { it.id == fromId }
        val to = list.indexOfFirst { it.id == toId }
        if (from < 0 || to < 0 || from == to) return
        val h = list.removeAt(from)
        list.add(to, h)
        habits = list
    }

    fun commitOrder() {
        save()
        touch("order")
    }

    // ───────── Avkrysning ─────────

    private fun createdOf(h: Habit): LocalDate {
        val c = LocalDate.parse(h.created)
        if (!h.journal) return c
        val first = notes.filter { hasWords(it.text) }.map { LocalDate.parse(it.date) }.minOrNull()
        return if (first != null && first.isBefore(c)) first else c
    }

    fun isDone(h: Habit, d: LocalDate): Boolean {
        if (h.journal) return hasEntry(d)
        val key = "${h.id}|$d"
        if (!h.auto) return done.contains(key)
        return !d.isBefore(createdOf(h)) && !d.isAfter(LocalDate.now()) && !missed.contains(key)
    }

    /** Plain toggle. The journal habit is checked by writing, never toggled. */
    fun toggle(h: Habit, d: LocalDate) {
        if (h.journal) return
        val key = "${h.id}|$d"
        if (h.auto) missed = if (missed.contains(key)) missed - key else missed + key
        else done = if (done.contains(key)) done - key else done + key
        save()
        touch("marks_${h.id}")
    }

    fun toggleId(id: String, d: LocalDate) {
        habits.firstOrNull { it.id == id }?.let { toggle(it, d) }
    }

    private fun doneDates(h: Habit): List<LocalDate> {
        if (h.journal) return notes.filter { hasWords(it.text) }.map { LocalDate.parse(it.date) }.distinct()
        if (!h.auto) return done.filter { it.startsWith("${h.id}|") }.map { LocalDate.parse(it.substringAfter('|')) }
        val out = ArrayList<LocalDate>()
        var d = createdOf(h)
        val t = LocalDate.now()
        while (!d.isAfter(t)) {
            if (!missed.contains("${h.id}|$d")) out.add(d)
            d = d.plusDays(1)
        }
        return out
    }

    fun count(h: Habit, from: LocalDate, to: LocalDate) =
        doneDates(h).count { !it.isBefore(from) && !it.isAfter(to) }

    /** Days in [from, to] that the habit existed and are not in the future. */
    fun possible(h: Habit, from: LocalDate, to: LocalDate): Int {
        val created = createdOf(h)
        val start = if (from.isAfter(created)) from else created
        val today = LocalDate.now()
        val end = if (to.isBefore(today)) to else today
        return if (end.isBefore(start)) 0 else (ChronoUnit.DAYS.between(start, end) + 1).toInt()
    }

    fun streak(h: Habit): Int {
        var d = LocalDate.now()
        if (!isDone(h, d)) d = d.minusDays(1)
        var n = 0
        while (isDone(h, d)) { n++; d = d.minusDays(1) }
        return n
    }

    fun best(h: Habit): Int {
        var best = 0
        var cur = 0
        var prev: LocalDate? = null
        for (d in doneDates(h).sorted()) {
            cur = if (prev != null && prev.plusDays(1) == d) cur + 1 else 1
            if (cur > best) best = cur
            prev = d
        }
        return best
    }

    // ───────── Notater ─────────

    fun hasEntry(d: LocalDate): Boolean = notes.any { it.date == d.toString() && hasWords(it.text) }

    fun noteById(id: String): Note? = notes.firstOrNull { it.id == id }

    /** Saves a note (new when [id] is null). Several notes per day are fine. */
    fun saveNote(id: String?, date: LocalDate, title: String, text: String) {
        val nid = id ?: UUID.randomUUID().toString()
        val old = notes.firstOrNull { it.id == nid }
        val n = Note(nid, old?.date ?: date.toString(), title.trim(), text.trim(), old?.created ?: System.currentTimeMillis())
        notes = if (old == null) notes + n else notes.map { if (it.id == nid) n else it }
        writeNotes()
        save()
        touch("note_$nid")
    }

    fun deleteNote(id: String) {
        notes = notes.filter { it.id != id }
        writeNotes()
        save()
        kill("note_$id")
    }

    // ───────── Feil ─────────

    fun addFailure(h: Habit, d: LocalDate, text: String) {
        val f = Failure(UUID.randomUUID().toString(), h.id, h.title, d.toString(), text.trim(), System.currentTimeMillis())
        failures = failures + f
        writeFailures()
        touch("fail_${f.id}")
    }

    fun deleteFailure(id: String) {
        failures = failures.filter { it.id != id }
        writeFailures()
        kill("fail_$id")
    }

    companion object {
        @Volatile private var inst: Store? = null
        fun get(c: Context): Store =
            inst ?: synchronized(this) { inst ?: Store(c).also { inst = it } }
    }
}
