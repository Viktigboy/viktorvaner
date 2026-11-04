package com.viktorvaner.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import java.text.BreakIterator
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DayStyle
import java.util.Locale

// ───────────────────────── Design tokens ─────────────────────────

data class Pal(
    val bg: Color, val card: Color, val border: Color, val text: Color, val sub: Color,
    val accent: Color, val accentSoft: Color, val danger: Color
)

val DarkPal = Pal(
    bg = Color(0xFF070B16), card = Color(0xFF0E1527), border = Color(0xFF1A2440),
    text = Color(0xFFF2F5FF), sub = Color(0xFF7D89A8), accent = Color(0xFF3D7BFF),
    accentSoft = Color(0x263D7BFF), danger = Color(0xFFFF6B7A)
)
val LightPal = Pal(
    bg = Color(0xFFF4F7FE), card = Color.White, border = Color(0xFFE2E9F7),
    text = Color(0xFF0A1330), sub = Color(0xFF6A7694), accent = Color(0xFF2F6BFF),
    accentSoft = Color(0x1A2F6BFF), danger = Color(0xFFE5484D)
)
val LocalPal = staticCompositionLocalOf { DarkPal }
val P: Pal
    @Composable get() = LocalPal.current

val QUICK_EMOJI = listOf("✅", "💧", "🏃", "📚", "🧘", "💪", "😴", "🥗", "💊", "🚭", "✍️", "🧠")

/** Things the home-screen widget (or a lock event) asks the app to do. */
object Pending {
    var failId by mutableStateOf<String?>(null)
    var journal by mutableStateOf(false)
    var lockTick by mutableIntStateOf(0)
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = Store.get(this)
        handle(intent)
        store.syncCloud()
        setContent { App(store) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onStop() {
        super.onStop()
        Pending.lockTick++
    }

    private fun handle(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "viktorvaner") return
        when (d.host) {
            "fail" -> Pending.failId = d.lastPathSegment
            "journal" -> Pending.journal = true
        }
    }
}

// ───────────────────────── Primitives ─────────────────────────

fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null, enabled = enabled, onClick = onClick
    )
}

@Composable
fun T(
    text: String, size: Int = 15, weight: FontWeight = FontWeight.Normal, color: Color = Color.Unspecified,
    modifier: Modifier = Modifier, strike: Boolean = false, align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE, spacing: Float = 0f
) {
    Text(
        text = text, modifier = modifier,
        color = if (color == Color.Unspecified) P.text else color,
        fontSize = size.sp, fontWeight = weight, letterSpacing = spacing.sp,
        textDecoration = if (strike) TextDecoration.LineThrough else null,
        textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun Gap(h: Int = 0, w: Int = 0) = Spacer(Modifier.height(h.dp).width(w.dp))

/** Minimal line icons, drawn in code so they match the design exactly. */
@Composable
fun Ic(kind: String, tint: Color, dp: Dp = 22.dp, stroke: Float = 1.8f) {
    Canvas(Modifier.size(dp)) {
        val w = this.size.width
        val sw = stroke.dp.toPx()
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * w, y * w)
        fun poly(vararg p: Pair<Float, Float>, close: Boolean = false): Path {
            val path = Path()
            p.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * w, y * w) else path.lineTo(x * w, y * w) }
            if (close) path.close()
            return path
        }
        when (kind) {
            "check" -> drawPath(poly(.2f to .52f, .42f to .72f, .8f to .3f), tint, style = st)
            "today" -> {
                drawCircle(tint, radius = .42f * w, style = st)
                drawPath(poly(.32f to .52f, .46f to .65f, .68f to .38f), tint, style = st)
            }
            "stats" -> {
                drawLine(tint, o(.25f, .82f), o(.25f, .55f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, o(.5f, .82f), o(.5f, .22f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, o(.75f, .82f), o(.75f, .4f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            "archive" -> {
                drawRoundRect(tint, topLeft = o(.12f, .2f), size = Size(.76f * w, .18f * w), cornerRadius = CornerRadius(.04f * w), style = st)
                drawPath(poly(.18f to .38f, .18f to .8f, .82f to .8f, .82f to .38f), tint, style = st)
                drawLine(tint, o(.4f, .54f), o(.6f, .54f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            "plus" -> {
                drawLine(tint, o(.5f, .2f), o(.5f, .8f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, o(.2f, .5f), o(.8f, .5f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            "more" -> {
                drawCircle(tint, radius = .075f * w, center = o(.5f, .2f))
                drawCircle(tint, radius = .075f * w, center = o(.5f, .5f))
                drawCircle(tint, radius = .075f * w, center = o(.5f, .8f))
            }
            "left" -> drawPath(poly(.62f to .2f, .32f to .5f, .62f to .8f), tint, style = st)
            "right" -> drawPath(poly(.38f to .2f, .68f to .5f, .38f to .8f), tint, style = st)
            "trash" -> {
                drawLine(tint, o(.16f, .28f), o(.84f, .28f), strokeWidth = sw, cap = StrokeCap.Round)
                drawPath(poly(.38f to .28f, .38f to .16f, .62f to .16f, .62f to .28f), tint, style = st)
                drawPath(poly(.26f to .28f, .3f to .84f, .7f to .84f, .74f to .28f), tint, style = st)
            }
            "edit" -> drawPath(poly(.22f to .78f, .28f to .56f, .62f to .22f, .78f to .38f, .44f to .72f, close = true), tint, style = st)
            "up" -> {
                drawLine(tint, o(.5f, .8f), o(.5f, .22f), strokeWidth = sw, cap = StrokeCap.Round)
                drawPath(poly(.3f to .42f, .5f to .22f, .7f to .42f), tint, style = st)
            }
            "book" -> {
                drawPath(poly(.5f to .26f, .3f to .2f, .14f to .24f, .14f to .76f, .3f to .72f, .5f to .82f, close = true), tint, style = st)
                drawPath(poly(.5f to .26f, .7f to .2f, .86f to .24f, .86f to .76f, .7f to .72f, .5f to .82f, close = true), tint, style = st)
            }
            else -> {}
        }
    }
}

@Composable
fun IconBtn(kind: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).tap(enabled, onClick), contentAlignment = Alignment.Center) {
        Ic(kind, if (enabled) P.sub else P.sub.copy(alpha = 0.3f), 20.dp)
    }
}

@Composable
fun CheckCircle(on: Boolean, dp: Dp = 28.dp) {
    val bg by animateColorAsState(if (on) P.accent else Color.Transparent, label = "bg")
    val br by animateColorAsState(if (on) P.accent else P.sub.copy(alpha = 0.45f), label = "br")
    Box(
        Modifier.size(dp).clip(CircleShape).background(bg).border(1.5.dp, br, CircleShape),
        contentAlignment = Alignment.Center
    ) { if (on) Ic("check", Color.White, dp * 0.62f, 2.2f) }
}

@Composable
fun EmojiTile(icon: String, dp: Dp = 46.dp) {
    Box(
        Modifier.size(dp).clip(RoundedCornerShape(dp * 0.32f)).background(P.accentSoft),
        contentAlignment = Alignment.Center
    ) { T(icon, (dp.value * 0.48f).toInt()) }
}

@Composable
fun Pill(text: String) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(P.accentSoft).padding(horizontal = 6.dp, vertical = 2.dp)) {
        T(text, 10, FontWeight.Bold, P.accent, spacing = 0.8f)
    }
}

@Composable
fun PillButton(
    text: String, modifier: Modifier = Modifier, filled: Boolean = true,
    color: Color = Color.Unspecified, enabled: Boolean = true, onClick: () -> Unit
) {
    val c = if (color == Color.Unspecified) P.accent else color
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.clip(shape)
            .background(if (filled) c.copy(alpha = if (enabled) 1f else 0.35f) else Color.Transparent)
            .then(if (filled) Modifier else Modifier.border(1.dp, P.border, shape))
            .tap(enabled, onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        T(text, 15, FontWeight.SemiBold, if (filled) Color.White else if (color == Color.Unspecified) P.text else color)
    }
}

@Composable
fun ProgressBar(frac: Float, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(frac.coerceIn(0f, 1f), label = "pb")
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(P.border)) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(P.accent, Color(0xFF58C7FF))))
        )
    }
}

@Composable
fun Ring(frac: Float, dp: Dp, label: String) {
    val f by animateFloatAsState(frac.coerceIn(0f, 1f), label = "ring")
    val track = P.border
    val acc = P.accent
    Box(Modifier.size(dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 10.dp.toPx()
            val inset = sw / 2
            val box = Size(this.size.width - sw, this.size.height - sw)
            drawArc(color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = box, style = Stroke(sw))
            drawArc(color = acc, startAngle = -90f, sweepAngle = 360f * f, useCenter = false,
                topLeft = Offset(inset, inset), size = box, style = Stroke(sw, cap = StrokeCap.Round))
        }
        T(label, 20, FontWeight.SemiBold)
    }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val bg by animateColorAsState(if (on) P.accent else P.border, label = "tg")
    val x by animateDpAsState(if (on) 20.dp else 2.dp, label = "tx")
    Box(Modifier.size(width = 46.dp, height = 28.dp).clip(CircleShape).background(bg).tap { onChange(!on) }) {
        Box(Modifier.offset(x = x, y = 2.dp).size(24.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(P.card)
                .border(1.dp, P.border, RoundedCornerShape(28.dp)).padding(20.dp),
            content = content
        )
    }
}

@Composable
fun SheetRow(icon: String, label: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    val c = if (color == Color.Unspecified) P.text else color
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).tap(onClick = onClick).padding(horizontal = 8.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Ic(icon, c, 22.dp)
        Gap(w = 14)
        T(label, 16, FontWeight.Medium, c)
    }
}

@Composable
fun Segmented(options: List<String>, sel: Int, onSel: (Int) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(P.card).border(1.dp, P.border, shape).padding(4.dp)) {
        options.forEachIndexed { i, o ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (i == sel) P.accent else Color.Transparent)
                    .tap { onSel(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) { T(o, 14, FontWeight.Medium, if (i == sel) Color.White else P.sub) }
        }
    }
}

@Composable
fun Empty(title: String, sub: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 80.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(P.accentSoft), contentAlignment = Alignment.Center) {
                Ic("today", P.accent, 30.dp)
            }
            Gap(h = 16)
            T(title, 18, FontWeight.SemiBold)
            Gap(h = 4)
            T(sub, 14, color = P.sub, align = TextAlign.Center)
        }
    }
}

fun pct(a: Int, b: Int) = if (b == 0) 0 else a * 100 / b

fun lastGrapheme(s: String): String {
    if (s.isEmpty()) return ""
    val it = BreakIterator.getCharacterInstance()
    it.setText(s)
    val end = it.last()
    val start = it.previous()
    return if (start >= 0 && start < end) s.substring(start, end) else s
}


// ───────────────────────── App shell ─────────────────────────

@Composable
fun App(s: Store) {
    val pal = if (isSystemInDarkTheme()) DarkPal else LightPal
    CompositionLocalProvider(LocalPal provides pal) {
        val ctx = LocalContext.current
        var tab by remember { mutableIntStateOf(0) }
        var showAdd by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<Habit?>(null) }
        var menuFor by remember { mutableStateOf<Habit?>(null) }
        var deleteFor by remember { mutableStateOf<Habit?>(null) }
        var privateOpen by remember { mutableStateOf(false) }
        var journalDate by remember { mutableStateOf<LocalDate?>(null) }
        var failFor by remember { mutableStateOf<Pair<Habit, LocalDate>?>(null) }

        // Reading an existing journal entry needs fingerprint. Writing a new one does not.
        fun openJournal(d: LocalDate) {
            if (s.hasEntry(d)) authenticate(ctx, "Åpne journal") { journalDate = d }
            else journalDate = d
        }

        fun openPrivate() {
            authenticate(ctx, "Åpne privat område") { privateOpen = true }
        }

        val pendingFail = Pending.failId
        LaunchedEffect(pendingFail) {
            if (pendingFail != null) {
                val h = s.habits.firstOrNull { it.id == pendingFail }
                val today = LocalDate.now()
                if (h != null && h.auto && s.isDone(h, today)) failFor = h to today
                Pending.failId = null
            }
        }
        val pendingJournal = Pending.journal
        LaunchedEffect(pendingJournal) {
            if (pendingJournal) {
                openJournal(LocalDate.now())
                Pending.journal = false
            }
        }
        val lock = Pending.lockTick
        LaunchedEffect(lock) { privateOpen = false }

        Box(Modifier.fillMaxSize().background(pal.bg).systemBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> TodayScreen(
                            s, onMenu = { menuFor = it }, onJournal = { openJournal(it) },
                            onPrivate = { openPrivate() }, onUncheck = { h, d -> failFor = h to d }
                        )
                        1 -> StatsScreen(s)
                        else -> ArchiveScreen(s) { deleteFor = it }
                    }
                }
                BottomBar(tab) { tab = it }
            }
            if (tab == 0) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 84.dp)
                        .shadow(14.dp, RoundedCornerShape(20.dp), spotColor = pal.accent, ambientColor = pal.accent)
                        .size(58.dp).clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF4A86FF), Color(0xFF2456E8))))
                        .tap { showAdd = true },
                    contentAlignment = Alignment.Center
                ) { Ic("plus", Color.White, 26.dp, 2.4f) }
            }
            if (privateOpen) {
                Box(Modifier.fillMaxSize().background(pal.bg)) {
                    PrivateScreen(s) { privateOpen = false }
                }
            }
        }

        if (showAdd) HabitDialog(
            h = null, onDismiss = { showAdd = false },
            onSave = { t, d, i, a -> s.add(t, d, i, a); showAdd = false }
        )
        editing?.let { h ->
            HabitDialog(
                h = h, onDismiss = { editing = null },
                onSave = { t, d, i, a -> s.update(h.id, t, d, i, a); editing = null }
            )
        }
        journalDate?.let { d -> JournalEditor(s, d) { journalDate = null } }
        failFor?.let { (h, d) ->
            FailureDialog(
                h, d, onDismiss = { failFor = null },
                onSave = { text ->
                    s.addFailure(h, d, text)
                    s.toggle(h, d)
                    failFor = null
                }
            )
        }
        menuFor?.let { h ->
            Sheet(onDismiss = { menuFor = null }) {
                Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    EmojiTile(h.icon, 40.dp)
                    Gap(w = 12)
                    T(h.title, 17, FontWeight.SemiBold, maxLines = 1)
                }
                SheetRow("edit", "Rediger") { menuFor = null; editing = h }
                SheetRow("archive", "Arkiver") { s.setArchived(h.id, true); menuFor = null }
                SheetRow("trash", "Slett", pal.danger) { menuFor = null; deleteFor = h }
            }
        }
        deleteFor?.let { h ->
            Sheet(onDismiss = { deleteFor = null }) {
                T("Slette vanen?", 20, FontWeight.SemiBold)
                Gap(h = 8)
                T("«${h.title}» og hele historikken fjernes for godt.", 14, color = pal.sub)
                Gap(h = 20)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Avbryt", Modifier.weight(1f), filled = false) { deleteFor = null }
                    PillButton("Slett", Modifier.weight(1f), color = pal.danger) { s.delete(h.id); deleteFor = null }
                }
            }
        }
    }
}

@Composable
fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))
        Row(Modifier.fillMaxWidth().height(64.dp).background(P.bg)) {
            listOf("I dag" to "today", "Statistikk" to "stats", "Arkiv" to "archive").forEachIndexed { i, (label, kind) ->
                val c = if (tab == i) P.accent else P.sub
                Column(
                    Modifier.weight(1f).fillMaxHeight().tap { onTab(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Ic(kind, c, 24.dp)
                    Gap(h = 3)
                    T(label, 11, FontWeight.Medium, c)
                }
            }
        }
    }
}

// ───────────────────────── I dag ─────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TodayScreen(
    s: Store, onMenu: (Habit) -> Unit, onJournal: (LocalDate) -> Unit,
    onPrivate: () -> Unit, onUncheck: (Habit, LocalDate) -> Unit
) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    val today = LocalDate.now()
    val list = s.habits.filter { !it.archived }
    val doneCount = list.count { s.isDone(it, date) }
    val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
    val canNext = !monday.plusWeeks(1).isAfter(today)

    val listState = rememberLazyListState()
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 160.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T(
                            if (date == today) "I dag" else cap(date.format(DateTimeFormatter.ofPattern("EEEE", NB))),
                            36, FontWeight.SemiBold, spacing = -1f
                        )
                        T(date.format(DateTimeFormatter.ofPattern("d. MMMM yyyy", NB)), 14, color = P.sub)
                    }
                    IconBtn("left") { date = date.minusWeeks(1) }
                    IconBtn("right", canNext) {
                        val n = date.plusWeeks(1)
                        date = if (n.isAfter(today)) today else n
                    }
                    Gap(w = 6)
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(P.accentSoft).tap { onPrivate() },
                        contentAlignment = Alignment.Center
                    ) { Ic("book", P.accent, 21.dp) }
                }
                Gap(h = 16)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in 0..6) {
                        val d = monday.plusDays(i.toLong())
                        val sel = d == date
                        val future = d.isAfter(today)
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                                .background(if (sel) P.accent else P.card)
                                .border(1.dp, if (sel) P.accent else P.border, RoundedCornerShape(16.dp))
                                .tap(!future) { date = d }.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            T(
                                cap(d.dayOfWeek.getDisplayName(DayStyle.NARROW, NB)), 11, FontWeight.Medium,
                                if (sel) Color.White.copy(alpha = 0.75f) else P.sub
                            )
                            Gap(h = 2)
                            T(
                                "${d.dayOfMonth}", 16, FontWeight.SemiBold,
                                if (sel) Color.White else if (future) P.sub.copy(alpha = 0.4f) else P.text
                            )
                        }
                    }
                }
                Gap(h = 20)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressBar(if (list.isEmpty()) 0f else doneCount / list.size.toFloat(), Modifier.weight(1f))
                    Gap(w = 12)
                    T("$doneCount / ${list.size}", 13, FontWeight.Medium, P.sub)
                }
                Gap(h = 6)
            }
        }
        if (list.isEmpty()) {
            item { Empty("Ingen vaner ennå", "Trykk + for å legge til din første vane.") }
        } else {
            items(list, key = { it.id }) { h ->
                val dragging = dragId == h.id
                val base = if (dragging) {
                    Modifier.zIndex(1f).graphicsLayer {
                        translationY = dragOffset
                        scaleX = 1.02f
                        scaleY = 1.02f
                    }
                } else Modifier.animateItemPlacement()
                HabitCard(
                    s, h, date,
                    modifier = base.pointerInput(h.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragId = h.id
                                dragOffset = 0f
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val vis = listState.layoutInfo.visibleItemsInfo
                                val info = vis.firstOrNull { it.key == h.id }
                                if (info != null) {
                                    val center = info.offset + info.size / 2f + dragOffset
                                    val target = vis.firstOrNull {
                                        it.key is String && it.key != h.id &&
                                            center >= it.offset && center <= it.offset + it.size
                                    }
                                    if (target != null) {
                                        s.reorder(h.id, target.key as String)
                                        val newOff = if (target.index > info.index) {
                                            target.offset + target.size - info.size
                                        } else target.offset
                                        dragOffset += (info.offset - newOff).toFloat()
                                    }
                                }
                            },
                            onDragEnd = {
                                dragId = null
                                dragOffset = 0f
                                s.commitOrder()
                            },
                            onDragCancel = {
                                dragId = null
                                dragOffset = 0f
                                s.commitOrder()
                            }
                        )
                    },
                    onClick = {
                        when {
                            h.journal -> onJournal(date)
                            h.auto && s.isDone(h, date) -> onUncheck(h, date)
                            else -> s.toggle(h, date)
                        }
                    },
                    onMenu = onMenu
                )
            }
        }
    }
}

@Composable
fun HabitCard(
    s: Store, h: Habit, date: LocalDate, modifier: Modifier,
    onClick: () -> Unit, onMenu: (Habit) -> Unit
) {
    val on = s.isDone(h, date)
    val streak = s.streak(h)
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(P.card)
            .border(1.dp, if (on) P.accent.copy(alpha = 0.4f) else P.border, shape)
            .tap { onClick() }
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = if (h.journal) 14.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EmojiTile(h.icon)
        Gap(w = 14)
        Column(Modifier.weight(1f)) {
            T(h.title, 16, FontWeight.SemiBold, maxLines = 1)
            if (h.desc.isNotBlank()) T(h.desc, 13, color = P.sub, maxLines = 2)
            if (streak > 0 || h.auto) {
                Gap(h = 5)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (streak > 0) T("$streak ${if (streak == 1) "dag" else "dager"} på rad", 12, FontWeight.Medium, P.accent)
                    if (streak > 0 && h.auto) Gap(w = 8)
                    if (h.auto) Pill("AUTO")
                }
            }
        }
        CheckCircle(on)
        if (!h.journal) {
            Gap(w = 2)
            IconBtn("more") { onMenu(h) }
        }
    }
}

// ───────────────────────── Ny / rediger ─────────────────────────

@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, single: Boolean = true,
    minH: Dp = 0.dp, placeholder: String = ""
) {
    Column {
        T(label, 12, FontWeight.Medium, P.sub)
        Gap(h = 6)
        Box(
            Modifier.fillMaxWidth().heightIn(min = minH).clip(RoundedCornerShape(14.dp)).background(P.bg)
                .border(1.dp, P.border, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) T(placeholder, 16, color = P.sub.copy(alpha = 0.6f), maxLines = 1)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = single, maxLines = if (single) 1 else 4,
                textStyle = TextStyle(color = P.text, fontSize = 16.sp),
                cursorBrush = SolidColor(P.accent), modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HabitDialog(h: Habit?, onDismiss: () -> Unit, onSave: (String, String, String, Boolean) -> Unit) {
    var title by remember { mutableStateOf(h?.title ?: "") }
    var desc by remember { mutableStateOf(h?.desc ?: "") }
    var icon by remember { mutableStateOf(h?.icon ?: "✅") }
    var auto by remember { mutableStateOf(h?.auto ?: false) }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current

    Sheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            T(if (h == null) "Ny vane" else "Rediger vane", 22, FontWeight.SemiBold, spacing = -0.5f)
            Gap(h = 18)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(P.accentSoft)
                        .border(1.dp, P.accent.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                        .tap { focus.requestFocus(); kb?.show() },
                    contentAlignment = Alignment.Center
                ) {
                    BasicTextField(
                        value = icon,
                        onValueChange = { nv -> val g = lastGrapheme(nv); if (g.isNotBlank()) icon = g },
                        textStyle = TextStyle(fontSize = 34.sp, textAlign = TextAlign.Center, color = P.text),
                        singleLine = true, cursorBrush = SolidColor(Color.Transparent),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).focusRequester(focus)
                    )
                }
                Gap(w = 14)
                Column {
                    T("Ikon", 15, FontWeight.SemiBold)
                    T("Trykk på ruten, og velg en emoji fra tastaturet.", 12, color = P.sub)
                }
            }
            Gap(h = 12)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUICK_EMOJI.forEach { e ->
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                            .background(if (e == icon) P.accentSoft else P.bg)
                            .border(1.dp, if (e == icon) P.accent else P.border, RoundedCornerShape(13.dp))
                            .tap { icon = e },
                        contentAlignment = Alignment.Center
                    ) { T(e, 20) }
                }
            }
            Gap(h = 18)
            Field("Tittel", title, { title = it })
            Gap(h = 14)
            Field("Beskrivelse (valgfritt)", desc, { desc = it }, single = false, minH = 76.dp)
            Gap(h = 18)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Kryss av automatisk hver dag", 15, FontWeight.Medium)
                    T("Teller som gjort hver dag. Fjern krysset på dager du bommer, og skriv hva som skjedde.", 12, color = P.sub)
                }
                Gap(w = 12)
                Toggle(auto) { auto = it }
            }
            Gap(h = 22)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Avbryt", Modifier.weight(1f), filled = false, onClick = onDismiss)
                PillButton("Lagre", Modifier.weight(1f), enabled = title.isNotBlank()) {
                    onSave(title.trim(), desc.trim(), icon, auto)
                }
            }
        }
    }
}

// ───────────────────────── Statistikk ─────────────────────────

@Composable
fun StatsScreen(s: Store) {
    var mode by remember { mutableIntStateOf(0) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var year by remember { mutableIntStateOf(LocalDate.now().year) }
    val today = LocalDate.now()
    val from: LocalDate
    val to: LocalDate
    when (mode) {
        0 -> { from = month.atDay(1); to = month.atEndOfMonth() }
        1 -> { from = LocalDate.of(year, 1, 1); to = LocalDate.of(year, 12, 31) }
        else -> { from = LocalDate.of(2000, 1, 1); to = today }
    }
    val totalDone = s.habits.sumOf { s.count(it, from, to) }
    val totalPossible = s.habits.sumOf { s.possible(it, from, to) }

    LazyColumn(
        contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                T("Statistikk", 36, FontWeight.SemiBold, spacing = -1f)
                Gap(h = 16)
                Segmented(listOf("Måned", "År", "Totalt"), mode) { mode = it }
                Gap(h = 10)
                when (mode) {
                    0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBtn("left") { month = month.minusMonths(1) }
                        T(
                            cap(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", NB))), 16, FontWeight.SemiBold,
                            modifier = Modifier.weight(1f), align = TextAlign.Center
                        )
                        IconBtn("right", month.isBefore(YearMonth.now())) { month = month.plusMonths(1) }
                    }
                    1 -> Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBtn("left") { year-- }
                        T("$year", 16, FontWeight.SemiBold, modifier = Modifier.weight(1f), align = TextAlign.Center)
                        IconBtn("right", year < today.year) { year++ }
                    }
                    else -> Gap(h = 6)
                }
            }
        }
        if (s.habits.isEmpty()) {
            item { Empty("Ingenting å vise ennå", "Legg til en vane for å begynne å følge den.") }
        } else {
            item {
                val shape = RoundedCornerShape(24.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(P.card).border(1.dp, P.border, shape).padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Ring(if (totalPossible == 0) 0f else totalDone / totalPossible.toFloat(), 96.dp, "${pct(totalDone, totalPossible)}%")
                    Gap(w = 18)
                    Column {
                        T("Gjennomføring", 13, FontWeight.Medium, P.sub)
                        T("$totalDone av $totalPossible", 22, FontWeight.SemiBold, spacing = -0.5f)
                        T("avkrysninger", 13, color = P.sub)
                    }
                }
            }
            items(s.habits, key = { it.id }) { h -> StatCard(s, h, mode, month, year, from, to) }
        }
    }
}

@Composable
fun StatCard(s: Store, h: Habit, mode: Int, month: YearMonth, year: Int, from: LocalDate, to: LocalDate) {
    val shape = RoundedCornerShape(24.dp)
    val c = s.count(h, from, to)
    val p = s.possible(h, from, to)
    val today = LocalDate.now()
    Column(Modifier.fillMaxWidth().clip(shape).background(P.card).border(1.dp, P.border, shape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiTile(h.icon, 40.dp)
            Gap(w = 12)
            Column(Modifier.weight(1f)) {
                T(h.title + if (h.archived) "  ·  arkivert" else "", 16, FontWeight.SemiBold, maxLines = 1)
                T(if (mode == 2) "$c avkrysninger totalt" else "$c av $p dager", 12, color = P.sub)
            }
            T("${pct(c, p)}%", 20, FontWeight.SemiBold, P.accent)
        }
        Gap(h = 14)
        when (mode) {
            0 -> {
                val len = month.lengthOfMonth()
                val offset = month.atDay(1).dayOfWeek.value - 1
                val rows = (offset + len + 6) / 7
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (r in 0 until rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0 until 7) {
                                val n = r * 7 + col - offset + 1
                                if (n in 1..len) {
                                    val date = month.atDay(n)
                                    val on = s.isDone(h, date)
                                    Box(
                                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                                            .background(if (on) P.accent else if (date.isAfter(today)) P.border.copy(alpha = 0.4f) else P.border),
                                        contentAlignment = Alignment.Center
                                    ) { T("$n", 10, FontWeight.Medium, if (on) Color.White else P.sub) }
                                } else Spacer(Modifier.weight(1f).aspectRatio(1f))
                            }
                        }
                    }
                }
            }
            1 -> Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (m in 1..12) {
                    val ym = YearMonth.of(year, m)
                    val cm = s.count(h, ym.atDay(1), ym.atEndOfMonth())
                    val frac = cm / ym.lengthOfMonth().toFloat()
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Box(
                            Modifier.fillMaxWidth(0.7f).height((4f + 64f * frac).dp).clip(RoundedCornerShape(5.dp))
                                .background(if (cm > 0) Brush.verticalGradient(listOf(P.accent, P.accent.copy(alpha = 0.55f))) else SolidColor(P.border))
                        )
                        Gap(h = 6)
                        T("JFMAMJJASOND"[m - 1].toString(), 10, FontWeight.Medium, P.sub)
                    }
                }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatBlock("${s.streak(h)}", "Nå", Modifier.weight(1f))
                    StatBlock("${s.best(h)}", "Beste", Modifier.weight(1f))
                    StatBlock("$c", "Totalt", Modifier.weight(1f))
                }
                Gap(h = 12)
                ProgressBar(if (p == 0) 0f else c / p.toFloat(), Modifier.fillMaxWidth())
                Gap(h = 6)
                T("Suksessrate siden ${h.created}", 12, color = P.sub)
            }
        }
    }
}

@Composable
fun StatBlock(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(P.bg).padding(12.dp)) {
        T(value, 22, FontWeight.SemiBold, spacing = -0.5f)
        T(label, 11, FontWeight.Medium, P.sub)
    }
}

// ───────────────────────── Arkiv ─────────────────────────

@Composable
fun ArchiveScreen(s: Store, onDelete: (Habit) -> Unit) {
    val list = s.habits.filter { it.archived }
    val shape = RoundedCornerShape(22.dp)
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                T("Arkiv", 36, FontWeight.SemiBold, spacing = -1f)
                T("Pausede vaner. Gjenopprett dem når som helst.", 14, color = P.sub)
                Gap(h = 8)
            }
        }
        if (list.isEmpty()) {
            item { Empty("Ingenting arkivert", "Arkiverte vaner vises her.") }
        } else {
            items(list, key = { it.id }) { h ->
                Column(Modifier.fillMaxWidth().clip(shape).background(P.card).border(1.dp, P.border, shape).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EmojiTile(h.icon)
                        Gap(w = 14)
                        Column(Modifier.weight(1f)) {
                            T(h.title, 16, FontWeight.SemiBold, maxLines = 1)
                            if (h.desc.isNotBlank()) T(h.desc, 13, color = P.sub, maxLines = 2)
                        }
                    }
                    Gap(h = 14)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Gjenopprett", Modifier.weight(1f)) { s.setArchived(h.id, false) }
                        PillButton("Slett", Modifier.weight(1f), filled = false, color = P.danger) { onDelete(h) }
                    }
                }
            }
        }
    }
}
