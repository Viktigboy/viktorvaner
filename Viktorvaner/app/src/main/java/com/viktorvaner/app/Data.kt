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
    val created: String
)

class Store private constructor(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("viktorvaner", Context.MODE_PRIVATE)

    var habits by mutableStateOf(listOf<Habit>())
        private set
    var done by mutableStateOf(setOf<String>())
        private set

    init { load() }

    private fun load() {
        try {
            val a = JSONArray(prefs.getString("habits", "[]"))
            habits = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Habit(
                    o.getString("id"), o.getString("title"), o.optString("desc", ""),
                    o.optString("icon", "✅"), o.optBoolean("archived", false),
                    o.optString("created", LocalDate.now().toString())
                )
            }
        } catch (e: Exception) { }
        try {
            val a = JSONArray(prefs.getString("done", "[]"))
            done = (0 until a.length()).map { a.getString(it) }.toSet()
        } catch (e: Exception) { }
    }

    private fun save() {
        val a = JSONArray()
        habits.forEach {
            a.put(
                JSONObject().put("id", it.id).put("title", it.title).put("desc", it.desc)
                    .put("icon", it.icon).put("archived", it.archived).put("created", it.created)
            )
        }
        prefs.edit().putString("habits", a.toString())
            .putString("done", JSONArray(done.toList()).toString()).apply()
        CoroutineScope(Dispatchers.Default).launch {
            try { HabitWidget().updateAll(app) } catch (e: Exception) { }
        }
    }

    fun add(title: String, desc: String, icon: String) {
        habits = habits + Habit(UUID.randomUUID().toString(), title, desc, icon, false, LocalDate.now().toString())
        save()
    }

    fun update(id: String, title: String, desc: String, icon: String) {
        habits = habits.map { if (it.id == id) it.copy(title = title, desc = desc, icon = icon) else it }
        save()
    }

    fun setArchived(id: String, archived: Boolean) {
        habits = habits.map { if (it.id == id) it.copy(archived = archived) else it }
        save()
    }

    fun delete(id: String) {
        habits = habits.filter { it.id != id }
        done = done.filter { !it.startsWith("$id|") }.toSet()
        save()
    }

    fun isDone(id: String, d: LocalDate) = done.contains("$id|$d")

    fun toggle(id: String, d: LocalDate) {
        val k = "$id|$d"
        done = if (done.contains(k)) done - k else done + k
        save()
    }

    private fun dates(id: String): List<LocalDate> =
        done.filter { it.startsWith("$id|") }.map { LocalDate.parse(it.substringAfter('|')) }

    fun count(id: String, from: LocalDate, to: LocalDate) =
        dates(id).count { !it.isBefore(from) && !it.isAfter(to) }

    /** Days in [from, to] that the habit existed and are not in the future. */
    fun possible(h: Habit, from: LocalDate, to: LocalDate): Int {
        val created = LocalDate.parse(h.created)
        val start = if (from.isAfter(created)) from else created
        val today = LocalDate.now()
        val end = if (to.isBefore(today)) to else today
        return if (end.isBefore(start)) 0 else (ChronoUnit.DAYS.between(start, end) + 1).toInt()
    }

    fun streak(id: String): Int {
        var d = LocalDate.now()
        if (!isDone(id, d)) d = d.minusDays(1)
        var n = 0
        while (isDone(id, d)) { n++; d = d.minusDays(1) }
        return n
    }

    fun best(id: String): Int {
        var best = 0
        var cur = 0
        var prev: LocalDate? = null
        for (d in dates(id).sorted()) {
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
