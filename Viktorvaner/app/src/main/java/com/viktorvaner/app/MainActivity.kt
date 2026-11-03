package com.viktorvaner.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E6BFF), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF), onPrimaryContainer = Color(0xFF001A41),
    background = Color(0xFFF4F8FF), surface = Color.White, surfaceVariant = Color(0xFFE3EBFA)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FB0FF), onPrimary = Color(0xFF002D6E),
    primaryContainer = Color(0xFF123A82), onPrimaryContainer = Color(0xFFD6E4FF),
    background = Color(0xFF08122B), surface = Color(0xFF0F1D3F), surfaceVariant = Color(0xFF1A2B55)
)

val ICONS = listOf(
    "✅", "💧", "🏃", "📚", "🧘", "💪", "😴", "🥗", "🚭", "🎸", "✍️", "🧹", "💊", "🚴", "🏊",
    "🧠", "☀️", "🌙", "🍎", "📝", "💻", "🎨", "🐕", "🙏", "💰", "📵", "🦷", "🚶", "☕", "❤️"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store.get(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
                App(store)
            }
        }
    }
}

@Composable
fun App(s: Store) {
    var tab by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Habit?>(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { Header() },
        bottomBar = {
            NavigationBar {
                listOf("Today" to "✅", "Stats" to "📊", "Archive" to "📦").forEachIndexed { i, (label, emoji) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Text(emoji, fontSize = 20.sp) }, label = { Text(label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 0) FloatingActionButton(onClick = { showAdd = true }, containerColor = MaterialTheme.colorScheme.primary) {
                Text("+", fontSize = 28.sp, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TodayScreen(s) { editing = it }
                1 -> StatsScreen(s)
                else -> ArchiveScreen(s)
            }
        }
    }
    if (showAdd) HabitDialog(
        h = null,
        onDismiss = { showAdd = false },
        onSave = { t, d, i -> s.add(t, d, i); showAdd = false }
    )
    editing?.let { h ->
        HabitDialog(
            h = h,
            onDismiss = { editing = null },
            onSave = { t, d, i -> s.update(h.id, t, d, i); editing = null }
        )
    }
}

@Composable
fun Header() {
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Color(0xFF0B3FBF), Color(0xFF1E6BFF), Color(0xFF00A3FF))))
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Column {
            Text("Viktorvaner", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Build habits. Keep the streak.", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        }
    }
}

@Composable
fun TodayScreen(s: Store, onEdit: (Habit) -> Unit) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    val list = s.habits.filter { !it.archived }
    val doneCount = list.count { s.isDone(it.id, date) }
    val isToday = date == LocalDate.now()
    Column(Modifier.fillMaxSize()) {
        PeriodNav(
            label = (if (isToday) "Today · " else date.format(DateTimeFormatter.ofPattern("EEEE · ")))
                    + date.format(DateTimeFormatter.ofPattern("d MMM yyyy")),
            onPrev = { date = date.minusDays(1) },
            onNext = { date = date.plusDays(1) },
            canNext = date.isBefore(LocalDate.now())
        )
        LinearProgressIndicator(
            progress = { if (list.isEmpty()) 0f else doneCount / list.size.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(8.dp).clip(RoundedCornerShape(4.dp))
        )
        Text(
            "$doneCount of ${list.size} done", fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No habits yet — tap + to add one", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(list, key = { it.id }) { h -> HabitRow(s, h, date, onEdit) }
            }
        }
    }
}

@Composable
fun HabitRow(s: Store, h: Habit, date: LocalDate, onEdit: (Habit) -> Unit) {
    val done = s.isDone(h.id, date)
    var menu by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth().clickable { s.toggle(h.id, date) }
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(h.icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    h.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None
                )
                if (h.desc.isNotBlank()) Text(
                    h.desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
                Text("🔥 ${s.streak(h.id)} day streak", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            Checkbox(checked = done, onCheckedChange = { s.toggle(h.id, date) })
            Box {
                IconButton(onClick = { menu = true }) { Text("⋮", fontSize = 20.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit(h) })
                    DropdownMenuItem(text = { Text("Archive") }, onClick = { menu = false; s.setArchived(h.id, true) })
                }
            }
        }
    }
}

@Composable
fun IconBubble(icon: String) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) { Text(icon, fontSize = 24.sp) }
}

@Composable
fun PeriodNav(label: String, onPrev: () -> Unit, onNext: () -> Unit, canNext: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrev) { Text("‹", fontSize = 26.sp) }
        Text(label, Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        TextButton(onClick = onNext, enabled = canNext) { Text("›", fontSize = 26.sp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitDialog(h: Habit?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var title by remember { mutableStateOf(h?.title ?: "") }
    var desc by remember { mutableStateOf(h?.desc ?: "") }
    var icon by remember { mutableStateOf(h?.icon ?: ICONS[0]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (h == null) "New habit" else "Edit habit") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, label = { Text("Title") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it }, label = { Text("Description (optional)") },
                    minLines = 2, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Icon", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ICONS.forEach { ic ->
                        Box(
                            Modifier.size(44.dp).clip(CircleShape)
                                .background(if (ic == icon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { icon = ic },
                            contentAlignment = Alignment.Center
                        ) { Text(ic, fontSize = 22.sp) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onSave(title.trim(), desc.trim(), icon) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

fun pct(a: Int, b: Int) = if (b == 0) 0 else a * 100 / b

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(s: Store) {
    var mode by remember { mutableIntStateOf(0) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var year by remember { mutableIntStateOf(LocalDate.now().year) }
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = mode) {
            listOf("Month", "Year", "All time").forEachIndexed { i, t ->
                Tab(selected = mode == i, onClick = { mode = i }, text = { Text(t) })
            }
        }
        when (mode) {
            0 -> PeriodNav(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                { month = month.minusMonths(1) }, { month = month.plusMonths(1) }, month.isBefore(YearMonth.now()))
            1 -> PeriodNav("$year", { year-- }, { year++ }, year < LocalDate.now().year)
            else -> Spacer(Modifier.height(8.dp))
        }
        if (s.habits.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing to show yet") }
            return@Column
        }
        // Range for the summary
        val from: LocalDate; val to: LocalDate
        when (mode) {
            0 -> { from = month.atDay(1); to = month.atEndOfMonth() }
            1 -> { from = LocalDate.of(year, 1, 1); to = LocalDate.of(year, 12, 31) }
            else -> { from = LocalDate.of(2000, 1, 1); to = LocalDate.now() }
        }
        val totalDone = s.habits.sumOf { s.count(it.id, from, to) }
        val totalPossible = s.habits.sumOf { s.possible(it, from, to) }
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = primary)
                ) {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        Text("${pct(totalDone, totalPossible)}%", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                        Text("$totalDone of $totalPossible check-ins completed", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f))
                    }
                }
            }
            items(s.habits, key = { it.id }) { h ->
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp).fillMaxWidth()) {
                        val c = s.count(h.id, from, to)
                        val p = s.possible(h, from, to)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBubble(h.icon)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(h.title + if (h.archived) " (archived)" else "", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text(
                                    when (mode) {
                                        2 -> "$c total · since ${h.created}"
                                        else -> "$c / $p days · ${pct(c, p)}%"
                                    },
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        when (mode) {
                            0 -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (d in 1..month.lengthOfMonth()) {
                                    val date = month.atDay(d)
                                    val on = s.isDone(h.id, date)
                                    Box(
                                        Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                                            .background(if (on) primary else track.copy(alpha = if (date.isAfter(LocalDate.now())) 0.35f else 1f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("$d", fontSize = 9.sp,
                                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    }
                                }
                            }
                            1 -> Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                                for (m in 1..12) {
                                    val ym = YearMonth.of(year, m)
                                    val cm = s.count(h.id, ym.atDay(1), ym.atEndOfMonth())
                                    val frac = cm / ym.lengthOfMonth().toFloat()
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                                        Box(Modifier.fillMaxWidth().height((3f + 48f * frac).dp).clip(RoundedCornerShape(4.dp)).background(if (cm > 0) primary else track))
                                        Text("JFMAMJJASOND"[m - 1].toString(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    }
                                }
                            }
                            else -> {
                                LinearProgressIndicator(
                                    progress = { if (p == 0) 0f else c / p.toFloat() },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("Success rate ${pct(c, p)}%  ·  🔥 current streak ${s.streak(h.id)}  ·  🏆 best ${s.best(h.id)}", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArchiveScreen(s: Store) {
    val list = s.habits.filter { it.archived }
    var toDelete by remember { mutableStateOf<Habit?>(null) }
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No archived habits", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { h ->
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp).fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBubble(h.icon)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(h.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                if (h.desc.isNotBlank()) Text(h.desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { toDelete = h }) { Text("Delete") }
                            Button(onClick = { s.setArchived(h.id, false) }) { Text("Restore") }
                        }
                    }
                }
            }
        }
    }
    toDelete?.let { h ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete \"${h.title}\"?") },
            text = { Text("This permanently removes the habit and all its history.") },
            confirmButton = { TextButton(onClick = { s.delete(h.id); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }
}
