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
    val auto: Boolean = false
)

class Store private constructor(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("viktorvaner", Context.MODE_PRIVATE)

    var habits by mutableStateOf(listOf<Habit>())
        private set
    var done by mutableStateOf(setOf<String>())
        private set
    /** For auto habits: days the user un-checked (failed). */
    var missed by mutableStateOf(setOf<String>())
        private set

    init { load() }

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
                    o.optString("created", LocalDate.now().toString()), o.optBoolean("auto", false)
                )
            }
        } catch (e: Exception) { }
        done = readSet("done")
        missed = readSet("missed")
    }

    private fun save() {
        val a = JSONArray()
        habits.forEach {
            a.put(
                JSONObject().put("id", it.id).put("title", it.title).put("desc", it.desc)
                    .put("icon", it.icon).put("archived", it.archived).put("created", it.created)
                    .put("auto", it.auto)
            )
        }
        prefs.edit().putString("habits", a.toString())
            .putString("done", JSONArray(done.toList()).toString())
            .putString("missed", JSONArray(missed.toList()).toString()).apply()
        CoroutineScope(Dispatchers.Default).launch {
            try { HabitWidget().updateAll(app) } catch (e: Exception) { }
        }
    }

    fun add(title: String, desc: String, icon: String, auto: Boolean) {
        habits = habits + Habit(UUID.randomUUID().toString(), title, desc, icon, false, LocalDate.now().toString(), auto)
        save()
    }

    fun update(id: String, title: String, desc: String, icon: String, auto: Boolean) {
        habits = habits.map { if (it.id == id) it.copy(title = title, desc = desc, icon = icon, auto = auto) else it }
        save()
    }

    fun setArchived(id: String, archived: Boolean) {
        habits = habits.map { if (it.id == id) it.copy(archived = archived) else it }
        save()
    }

    fun delete(id: String) {
        habits = habits.filter { it.id != id }
        done = done.filter { !it.startsWith("$id|") }.toSet()
        missed = missed.filter { !it.startsWith("$id|") }.toSet()
        save()
    }

    private fun createdOf(h: Habit): LocalDate = LocalDate.parse(h.created)

    fun isDone(h: Habit, d: LocalDate): Boolean {
        val key = "${h.id}|$d"
        if (!h.auto) return done.contains(key)
        return !d.isBefore(createdOf(h)) && !d.isAfter(LocalDate.now()) && !missed.contains(key)
    }

    fun toggle(h: Habit, d: LocalDate) {
        val key = "${h.id}|$d"
        if (h.auto) missed = if (missed.contains(key)) missed - key else missed + key
        else done = if (done.contains(key)) done - key else done + key
        save()
    }

    fun toggleId(id: String, d: LocalDate) {
        habits.firstOrNull { it.id == id }?.let { toggle(it, d) }
    }

    private fun doneDates(h: Habit): List<LocalDate> {
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

    companion object {
        @Volatile private var inst: Store? = null
        fun get(c: Context): Store =
            inst ?: synchronized(this) { inst ?: Store(c).also { inst = it } }
    }
}
