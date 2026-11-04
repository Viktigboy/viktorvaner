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

/** One journal entry per day. [date] is ISO (2026-11-04). */
data class JEntry(val date: String, val text: String, val updated: Long)

/** The answer given when an automatic habit was un-checked. Stored on this phone only. */
data class Failure(
    val id: String, val habitId: String, val habitTitle: String,
    val date: String, val text: String, val created: Long
)

/** "At least one word" = any non-blank text. */
fun hasWords(s: String) = s.trim().isNotEmpty()

class Store private constructor(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("viktorvaner", Context.MODE_PRIVATE)
    private val jPrefs = app.getSharedPreferences("journal", Context.MODE_PRIVATE)
    // "failures" is excluded from Google's automatic backup, see res/xml/backup_rules.xml
    private val fPrefs = app.getSharedPreferences("failures", Context.MODE_PRIVATE)

    val cloud = Cloud(app)

    var habits by mutableStateOf(listOf<Habit>())
        private set
    var done by mutableStateOf(setOf<String>())
        private set
    /** For auto habits: days the user un-checked (failed). */
    var missed by mutableStateOf(setOf<String>())
        private set
    var journal by mutableStateOf(mapOf<String, JEntry>())
        private set
    var failures by mutableStateOf(listOf<Failure>())
        private set

    init {
        load()
        if (habits.none { it.journal }) {
            val j = Habit(
                "journal", "Daglig journal", "Skriv minst ett ord hver dag", "📓",
                false, LocalDate.now().toString(), false, true
            )
            habits = listOf(j) + habits
            writePrefs()
        }
    }

    private fun readSet(key: String): Set<String> = try {
        val a = JSONArray(prefs.getString(key, "[]"))
        (0 until a.length()).map { a.getString(it) }.toSet()
    } catch (e: Exception) { emptySet() }

    private fun load() {
        try {
            val a = JSONArray(prefs.getString("habits", "[]"))
            habits = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Habit(
                    o.getString("id"), o.getString("title"), o.optString("desc", ""),
                    o.optString("icon", "✅"), o.optBoolean("archived", false),
                    o.optString("created", LocalDate.now().toString()),
                    o.optBoolean("auto", false), o.optBoolean("journal", false)
                )
            }
        } catch (e: Exception) { }
        done = readSet("done")
        missed = readSet("missed")
        try {
            val a = JSONArray(jPrefs.getString("entries", "[]"))
            val m = HashMap<String, JEntry>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                m[o.getString("date")] = JEntry(o.getString("date"), o.getString("text"), o.optLong("updated", 0L))
            }
            journal = m
        } catch (e: Exception) { }
        try {
            val a = JSONArray(fPrefs.getString("list", "[]"))
            failures = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Failure(
                    o.getString("id"), o.getString("habitId"), o.optString("habitTitle", ""),
                    o.getString("date"), o.getString("text"), o.optLong("created", 0L)
                )
            }
        } catch (e: Exception) { }
    }

    private fun writePrefs() {
        val a = JSONArray()
        habits.forEach {
            a.put(
                JSONObject().put("id", it.id).put("title", it.title).put("desc", it.desc)
                    .put("icon", it.icon).put("archived", it.archived).put("created", it.created)
                    .put("auto", it.auto).put("journal", it.journal)
            )
        }
        prefs.edit().putString("habits", a.toString())
            .putString("done", JSONArray(done.toList()).toString())
            .putString("missed", JSONArray(missed.toList()).toString()).apply()
    }

    private fun writeJournal() {
        val a = JSONArray()
        journal.values.forEach { a.put(JSONObject().put("date", it.date).put("text", it.text).put("updated", it.updated)) }
        jPrefs.edit().putString("entries", a.toString()).apply()
    }

    private fun writeFailures() {
        val a = JSONArray()
        failures.forEach {
            a.put(
                JSONObject().put("id", it.id).put("habitId", it.habitId).put("habitTitle", it.habitTitle)
                    .put("date", it.date).put("text", it.text).put("created", it.created)
            )
        }
        fPrefs.edit().putString("list", a.toString()).apply()
    }

    private fun save() {
        writePrefs()
        CoroutineScope(Dispatchers.Default).launch {
            try { HabitWidget().updateAll(app) } catch (e: Exception) { }
        }
    }

    // ───────── Vaner ─────────

    fun add(title: String, desc: String, icon: String, auto: Boolean) {
        habits = habits + Habit(UUID.randomUUID().toString(), title, desc, icon, false, LocalDate.now().toString(), auto)
        save()
    }

    fun update(id: String, title: String, desc: String, icon: String, auto: Boolean) {
        habits = habits.map { if (it.id == id && !it.journal) it.copy(title = title, desc = desc, icon = icon, auto = auto) else it }
        save()
    }

    fun setArchived(id: String, archived: Boolean) {
        habits = habits.map { if (it.id == id && !it.journal) it.copy(archived = archived) else it }
        save()
    }

    fun delete(id: String) {
        if (habits.any { it.id == id && it.journal }) return
        habits = habits.filter { it.id != id }
        done = done.filter { !it.startsWith("$id|") }.toSet()
        missed = missed.filter { !it.startsWith("$id|") }.toSet()
        save()
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

    fun commitOrder() = save()

    // ───────── Avkrysning ─────────

    private fun createdOf(h: Habit): LocalDate {
        val c = LocalDate.parse(h.created)
        if (!h.journal) return c
        val first = journal.values.filter { hasWords(it.text) }.map { LocalDate.parse(it.date) }.minOrNull()
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
    }

    fun toggleId(id: String, d: LocalDate) {
        habits.firstOrNull { it.id == id }?.let { toggle(it, d) }
    }

    private fun doneDates(h: Habit): List<LocalDate> {
        if (h.journal) return journal.values.filter { hasWords(it.text) }.map { LocalDate.parse(it.date) }
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

    // ───────── Journal ─────────

    fun hasEntry(d: LocalDate): Boolean = journal[d.toString()]?.let { hasWords(it.text) } == true

    fun entryText(d: LocalDate): String = journal[d.toString()]?.text ?: ""

    fun saveEntry(d: LocalDate, text: String) {
        val e = JEntry(d.toString(), text.trim(), System.currentTimeMillis())
        journal = journal + (e.date to e)
        writeJournal()
        cloud.push(e)
        save()
    }

    fun deleteEntry(d: LocalDate) {
        journal = journal - d.toString()
        writeJournal()
        cloud.remove(d.toString())
        save()
    }

    /** Merges the cloud copy with this phone (newest wins) and uploads what the cloud is missing. */
    fun syncCloud(onDone: (Int, Boolean) -> Unit = { _, _ -> }) {
        if (!cloud.available || cloud.code.isEmpty()) {
            onDone(0, false)
            return
        }
        cloud.fetch(
            onOk = { remote ->
                val merged = HashMap(journal)
                remote.forEach { (k, r) ->
                    val l = merged[k]
                    if (l == null || r.updated > l.updated) merged[k] = r
                }
                journal.forEach { (k, l) ->
                    val r = remote[k]
                    if (r == null || l.updated > r.updated) cloud.push(l)
                }
                journal = merged
                writeJournal()
                save()
                onDone(remote.size, true)
            },
            onErr = { onDone(0, false) }
        )
    }

    // ───────── Feil (kun lokalt) ─────────

    fun addFailure(h: Habit, d: LocalDate, text: String) {
        failures = failures + Failure(UUID.randomUUID().toString(), h.id, h.title, d.toString(), text.trim(), System.currentTimeMillis())
        writeFailures()
    }

    fun deleteFailure(id: String) {
        failures = failures.filter { it.id != id }
        writeFailures()
    }

    companion object {
        @Volatile private var inst: Store? = null
        fun get(c: Context): Store =
            inst ?: synchronized(this) { inst ?: Store(c).also { inst = it } }
    }
}
