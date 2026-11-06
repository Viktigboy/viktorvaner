package com.johnsrud.lister

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import java.text.BreakIterator
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DayStyle
import java.util.Locale
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keyboard capitalises the first letter of every sentence, like other apps. */
val CAPS = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)

val LIST_EMOJI = listOf("🛒", "🥦", "🍞", "🥛", "🍖", "🧴", "🏠", "🎉", "💊", "🐕", "🍷", "🧁")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared = Repo.get(this)
        val local = LocalRepo.get(this)
        scheduleWidgetRefresh(this)
        setContent { App(shared, local) }
    }
}

@Composable
fun Field(label: String, value: String, placeholder: String = "", onChange: (String) -> Unit) {
    Column {
        T(label, 12, FontWeight.Medium, P.sub)
        Gap(h = 6)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.bg)
                .border(1.dp, P.border, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) T(placeholder, 16, color = P.sub.copy(alpha = 0.7f))
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = P.text, fontSize = 16.sp),
                keyboardOptions = CAPS,
                cursorBrush = SolidColor(P.accent), modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun TopTabs(tab: Int, onTab: (Int) -> Unit) {
    Segmented(listOf("Johnsrudlister", "Private lister", "Fryserdatabase"), tab, onTab)
}

@Composable
fun BgLink(onClick: () -> Unit) {
    T(
        "Bytt bakgrunnsbilde for alle", 14, FontWeight.Medium, P.accent,
        Modifier.tap(onClick = onClick).padding(top = 10.dp, bottom = 6.dp)
    )
}

@Composable
fun App(shared: Repo, local: LocalRepo) {
    val dark = isSystemInDarkTheme()
    val base = if (dark) DarkPal else LightPal
    val bg = shared.background
    // With a background image the cards turn slightly see-through so the picture shows.
    val pal = if (bg != null) base.copy(bg = base.bg.copy(alpha = 0.55f), card = base.card.copy(alpha = 0.8f)) else base
    val overlay = if (dark) Color(0xFF0B2A6F).copy(alpha = 0.62f) else Color(0xFFDCE8FF).copy(alpha = 0.6f)

    CompositionLocalProvider(LocalPal provides pal) {
        var tab by remember { mutableIntStateOf(0) }
        var openId by remember { mutableStateOf<String?>(null) }
        var showBg by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(base.bg)) {
            if (bg != null) {
                Image(bg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(overlay))
            }
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                if (tab == 2) {
                    FreezerScreen(
                        r = shared, tab = tab,
                        onTab = { tab = it; openId = null }
                    )
                } else {
                    val backend: Backend = if (tab == 0) shared else local
                    val open = backend.lists.firstOrNull { it.id == openId }
                    if (open != null) DetailScreen(backend, open, tab == 0) { openId = null }
                    else HomeScreen(
                        backend = backend, tab = tab,
                        onTab = { tab = it; openId = null },
                        onOpen = { openId = it },
                        onBackground = { showBg = true }
                    )
                }
            }
        }
        if (showBg) BackgroundSheet(shared) { showBg = false }
    }
}

// ───────────────────────── Hjem ─────────────────────────

@Composable
fun HomeScreen(backend: Backend, tab: Int, onTab: (Int) -> Unit, onOpen: (String) -> Unit, onBackground: () -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column {
                    TopTabs(tab, onTab)
                    Gap(h = 8)
                }
            }
            if (backend.lists.isEmpty() && backend.loaded) {
                item {
                    Empty(
                        "Ingen lister ennå",
                        if (tab == 0) "Trykk + for å lage en liste som alle kan se."
                        else "Trykk + for å lage en privat liste."
                    )
                }
            } else {
                items(backend.lists, key = { it.id }) { l ->
                    val its = backend.items.filter { it.listId == l.id }
                    val left = its.count { !it.done }
                    Row(
                        Modifier.fillMaxWidth().clip(shape).background(P.card).border(1.dp, P.border, shape)
                            .tap { onOpen(l.id) }.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EmojiTile(l.icon)
                        Gap(w = 14)
                        Column(Modifier.weight(1f)) {
                            T(l.name, 17, FontWeight.SemiBold, maxLines = 1)
                            T(
                                when {
                                    its.isEmpty() -> "Tom"
                                    left == 0 -> "Alt ferdig"
                                    else -> "$left av ${its.size} igjen"
                                },
                                13, color = if (its.isNotEmpty() && left == 0) P.accent else P.sub
                            )
                        }
                        Ic("right", P.sub, 20.dp)
                    }
                }
            }
            item {
                Column(Modifier.padding(top = 6.dp)) {
                    if (tab == 0) T("Deles med alle som har appen", 13, color = P.sub)
                    backend.error?.let { T("Synkroniseringsfeil: $it", 12, color = P.danger) }
                    BgLink(onBackground)
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 24.dp)
                .shadow(14.dp, RoundedCornerShape(20.dp), spotColor = P.accent, ambientColor = P.accent)
                .size(58.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF4A86FF), Color(0xFF2456E8))))
                .tap { showAdd = true },
            contentAlignment = Alignment.Center
        ) { Ic("plus", Color.White, 26.dp, 2.4f) }
    }
    if (showAdd) ListDialog(null, { showAdd = false }) { n, i -> backend.addList(n, i); showAdd = false }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ListDialog(l: ShopList?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(l?.name ?: "") }
    var icon by remember { mutableStateOf(l?.icon ?: "🛒") }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    Sheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            T(if (l == null) "Ny liste" else "Bytt navn og ikon", 22, FontWeight.SemiBold, spacing = -0.5f)
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
                    T("For å velge en annen emoji, trykk på ruten for å velge din egen", 12, color = P.sub)
                }
            }
            Gap(h = 12)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LIST_EMOJI.forEach { e ->
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
            Field("Navn", name, "Uten navn") { name = it }
            Gap(h = 22)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Avbryt", Modifier.weight(1f), filled = false, onClick = onDismiss)
                PillButton("Lagre", Modifier.weight(1f)) { onSave(name.trim().ifEmpty { "Uten navn" }, icon) }
            }
        }
    }
}

// ───────────────────────── Liste ─────────────────────────

@Composable
fun DetailScreen(r: Backend, list: ShopList, isShared: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val its = r.items.filter { it.listId == list.id }
    val todo = its.filter { !it.done }.sortedBy { it.created }
    val bought = its.filter { it.done }.sortedBy { it.created }
    var text by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val fixed = isShared && list.id == Repo.HANDLE_ID
    val canAdd = text.isNotBlank()

    fun submit() {
        text.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }.forEach { s -> r.addItem(list.id, s) }
        text = ""
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn("left", onClick = onBack)
            Gap(w = 4)
            EmojiTile(list.icon, 40.dp)
            Gap(w = 12)
            Column(Modifier.weight(1f)) {
                T(list.name, 24, FontWeight.SemiBold, maxLines = 1, spacing = -0.5f)
                T("${bought.size} av ${its.size} ferdig", 13, color = P.sub)
            }
            if (!(fixed && its.isEmpty())) IconBtn("more") { menu = true }
        }
        Gap(h = 14)
        ProgressBar(
            if (its.isEmpty()) 0f else bought.size / its.size.toFloat(),
            Modifier.fillMaxWidth().padding(horizontal = 20.dp)
        )
        Gap(h = 16)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            val shape = RoundedCornerShape(16.dp)
            Box(
                Modifier.weight(1f).clip(shape).background(P.card).border(1.dp, P.border, shape)
                    .padding(horizontal = 16.dp, vertical = 15.dp)
            ) {
                if (text.isEmpty()) T("Legg til ny", 16, color = P.sub.copy(alpha = 0.7f))
                BasicTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    textStyle = TextStyle(color = P.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(P.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Gap(w = 10)
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (canAdd) P.accent else P.accentSoft)
                    .tap(canAdd) { submit() },
                contentAlignment = Alignment.Center
            ) { Ic("plus", if (canAdd) Color.White else P.accent, 24.dp, 2.2f) }
        }
        if (fixed && its.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                T(
                    "Tøm listen", 14, FontWeight.Medium, P.danger,
                    Modifier.tap { confirmClear = true }.padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (its.isEmpty()) item { Empty("Ingenting her ennå", "Skriv over for å legge til. Skill flere med komma.") }
            items(todo, key = { x -> x.id }) { x -> ItemRow(r, x) }
            if (bought.isNotEmpty()) {
                item { T("FERDIG", 11, FontWeight.SemiBold, P.sub, Modifier.padding(top = 12.dp, bottom = 2.dp), spacing = 1f) }
                items(bought, key = { x -> x.id }) { x -> ItemRow(r, x) }
            }
        }
    }

    if (menu) Sheet(onDismiss = { menu = false }) {
        Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            EmojiTile(list.icon, 40.dp)
            Gap(w = 12)
            T(list.name, 17, FontWeight.SemiBold, maxLines = 1)
        }
        if (!fixed) SheetRow("edit", "Bytt navn og ikon") { menu = false; edit = true }
        if (bought.isNotEmpty()) SheetRow("check", "Fjern ferdige") { r.clearDone(list.id); menu = false }
        if (fixed) SheetRow("trash", "Tøm listen", P.danger) { menu = false; confirmClear = true }
        else SheetRow("trash", "Slett liste", P.danger) { menu = false; confirmDelete = true }
    }
    if (confirmClear) Sheet(onDismiss = { confirmClear = false }) {
        T("Tømme listen?", 20, FontWeight.SemiBold)
        Gap(h = 8)
        T("Alt i ${list.name} fjernes for alle.", 14, color = P.sub)
        Gap(h = 20)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Avbryt", Modifier.weight(1f), filled = false) { confirmClear = false }
            PillButton("Tøm", Modifier.weight(1f), color = P.danger) { confirmClear = false; r.clearAll(list.id) }
        }
    }
    if (edit) ListDialog(list, { edit = false }) { n, i -> r.updateList(list.id, n, i); edit = false }
    if (confirmDelete) Sheet(onDismiss = { confirmDelete = false }) {
        T("Slette listen?", 20, FontWeight.SemiBold)
        Gap(h = 8)
        T(
            if (isShared) "\"${list.name}\" og alt i den slettes for alle."
            else "\"${list.name}\" og alt i den slettes.",
            14, color = P.sub
        )
        Gap(h = 20)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Avbryt", Modifier.weight(1f), filled = false) { confirmDelete = false }
            PillButton("Slett", Modifier.weight(1f), color = P.danger) { confirmDelete = false; r.deleteList(list.id) }
        }
    }
}

@Composable
fun ItemRow(r: Backend, item: Item) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(P.card).border(1.dp, P.border, shape)
            .tap { r.toggle(item) }.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CheckCircle(item.done, 26.dp)
        Gap(w = 14)
        T(item.name, 16, FontWeight.Medium, if (item.done) P.sub else P.text, Modifier.weight(1f), strike = item.done, maxLines = 2)
        IconBtn("trash") { r.deleteItem(item) }
    }
}

// ───────────────────────── Bakgrunn ─────────────────────────

@Composable
fun BackgroundSheet(shared: Repo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busy = true
            failed = false
            scope.launch {
                val b64 = withContext(Dispatchers.Default) { prepareBackground(ctx, uri) }
                if (b64 != null) { shared.setBackground(b64); onDismiss() } else { failed = true }
                busy = false
            }
        }
    }
    Sheet(onDismiss = { if (!busy) onDismiss() }) {
        T("Bakgrunn", 22, FontWeight.SemiBold, spacing = -0.5f)
        Gap(h = 6)
        T("Bildet du velger byttes for alle som bruker appen, med en gang.", 14, color = P.sub)
        Gap(h = 18)
        val bg = shared.background
        if (bg != null) {
            Box(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, P.border, RoundedCornerShape(18.dp))) {
                Image(bg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Gap(h = 14)
        }
        if (failed) {
            T("Kunne ikke bruke dette bildet. Prøv et annet.", 13, color = P.danger)
            Gap(h = 10)
        }
        PillButton(
            if (busy) "Laster opp…" else "Velg bilde", Modifier.fillMaxWidth(), enabled = !busy
        ) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        if (bg != null) {
            Gap(h = 10)
            PillButton("Fjern bilde", Modifier.fillMaxWidth(), filled = false, color = P.danger, enabled = !busy) {
                shared.removeBackground(); onDismiss()
            }
        }
        Gap(h = 10)
        PillButton("Lukk", Modifier.fillMaxWidth(), filled = false, enabled = !busy, onClick = onDismiss)
    }
}
