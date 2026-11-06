package com.johnsrud.lister

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

val DEFAULT_CATS = listOf("Kjøtt", "Fisk", "Brød", "Ferdigmat", "Restemat", "Dessert")
val NB: Locale = Locale("nb", "NO")
private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d. MMM yyyy", NB)

fun catEmoji(c: String): String = when (c.lowercase()) {
    "kjøtt" -> "🥩"
    "fisk" -> "🐟"
    "brød" -> "🍞"
    "ferdigmat" -> "🍲"
    "restemat" -> "🥡"
    "dessert" -> "🍰"
    else -> "🧊"
}

fun fmtDate(d: LocalDate): String = d.format(DATE_FMT)

/** Text for the expiry date and how urgent it is: 0 = fine, 1 = soon, 2 = expired. */
fun expiryInfo(d: LocalDate?): Pair<String, Int> {
    if (d == null) return Pair("Ingen utløpsdato", 0)
    val days = ChronoUnit.DAYS.between(LocalDate.now(), d)
    return when {
        days < 0 -> Pair("Utløpt for ${-days} ${if (-days == 1L) "dag" else "dager"} siden", 2)
        days == 0L -> Pair("Utløper i dag", 1)
        days == 1L -> Pair("Utløper i morgen", 1)
        days <= 7L -> Pair("Utløper om $days dager", 1)
        days <= 14L -> Pair("Utløper om $days dager", 0)
        else -> Pair("Utløper ${fmtDate(d)}", 0)
    }
}

fun decodePhoto(b64: String): ImageBitmap? {
    if (b64.isEmpty()) return null
    return try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/** Shrinks a photo (from camera or gallery) to a small JPEG that fits in a database document. */
fun preparePhoto(ctx: Context, uri: Uri): String? {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp: Bitmap = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        val orientation = try {
            ctx.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
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

        val longest = maxOf(bmp.width, bmp.height)
        if (longest > 640) {
            val f = 640f / longest
            bmp = Bitmap.createScaledBitmap(
                bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true
            )
        }

        var q = 80
        var bytes: ByteArray
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
            bytes = out.toByteArray()
            if (bytes.size <= 150_000 || q <= 40) break
            q -= 10
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (e: Exception) {
        return null
    }
}


@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.clip(shape)
            .background(if (selected) P.accent else P.bg)
            .border(1.dp, if (selected) P.accent else P.border, shape)
            .tap(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        T(text, 13, FontWeight.Medium, if (selected) Color.White else P.text)
    }
}

@Composable
fun SearchBar(value: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(P.card).border(1.dp, P.border, shape)
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Ic("search", P.sub, 20.dp)
        Gap(w = 10)
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (value.isEmpty()) T("Søk i fryseren", 16, color = P.sub.copy(alpha = 0.7f))
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = P.text, fontSize = 16.sp),
                cursorBrush = SolidColor(P.accent), modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) IconBtn("close") { onChange("") }
    }
}

// ───────────────────────── Små komponenter ─────────────────────────

val FREEZER_EMOJI = listOf("🥩", "🐟", "🍞", "🍲", "🥡", "🍰", "🥦", "🍓", "🍕", "🧀", "🥛", "🍦", "🍗", "🥟", "🫐", "🧊")

val SORT_LABELS = listOf("Tidligst utløpsdato", "Senest utløpsdato", "Høyest antall", "Lavest antall")

/** Items without an expiry date always come last in the two date sorts. */
fun freezerComparator(mode: Int): Comparator<FreezerItem> = Comparator { a, b ->
    fun byDate(asc: Boolean): Int {
        val x = a.expiry
        val y = b.expiry
        return when {
            x == null && y == null -> 0
            x == null -> 1
            y == null -> -1
            asc -> x.compareTo(y)
            else -> y.compareTo(x)
        }
    }
    val first = when (mode) {
        0 -> byDate(true)
        1 -> byDate(false)
        2 -> b.qty.compareTo(a.qty)
        else -> a.qty.compareTo(b.qty)
    }
    if (first != 0) first
    else if (mode >= 2 && byDate(true) != 0) byDate(true)
    else a.name.lowercase().compareTo(b.name.lowercase())
}

@Composable
fun MiniBtn(kind: String, size: Int = 30, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size / 3).dp))
            .background(if (enabled) P.accentSoft else P.border.copy(alpha = 0.5f))
            .tap(enabled, onClick),
        contentAlignment = Alignment.Center
    ) { Ic(kind, if (enabled) P.accent else P.sub.copy(alpha = 0.4f), (size / 2).dp, 2.2f) }
}

@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MiniBtn("minus", 40, value > 1) { onChange(value - 1) }
        T("$value", 20, FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp), align = TextAlign.Center)
        MiniBtn("plus", 40, value < 999) { onChange(value + 1) }
    }
}

@Composable
fun CatChip(text: String, selected: Boolean, onClick: () -> Unit, onEdit: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val fg = if (selected) Color.White else P.text
    Row(
        Modifier.clip(shape)
            .background(if (selected) P.accent else P.bg)
            .border(1.dp, if (selected) P.accent else P.border, shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        T(text, 13, FontWeight.Medium, fg, Modifier.tap(onClick = onClick).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 6.dp))
        Box(Modifier.tap(onClick = onEdit).padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)) {
            Ic("edit", if (selected) Color.White.copy(alpha = 0.85f) else P.sub, 14.dp, 1.6f)
        }
    }
}

@Composable
fun DropButton(label: String, value: String, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier) {
        T(label, 12, FontWeight.Medium, P.sub)
        Gap(h = 6)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(P.card)
                .border(1.dp, if (open) P.accent else P.border, shape)
                .tap(onClick = onClick).padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            T(value, 14, FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
            Ic("down", P.sub, 16.dp, 2f)
        }
    }
}

// ───────────────────────── Fryserdatabase ─────────────────────────

@Composable
fun FreezerScreen(r: Repo, tab: Int, onTab: (Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableIntStateOf(0) }
    var catFilter by remember { mutableStateOf(setOf<String>()) }
    var openMenu by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FreezerItem?>(null) }
    var menuFor by remember { mutableStateOf<FreezerItem?>(null) }
    var editCat by remember { mutableStateOf<FreezerCat?>(null) }
    var newCat by remember { mutableStateOf(false) }

    val all = r.freezer
    val cats = r.categories
    val activeFilter = catFilter.filter { f -> f.isEmpty() || cats.any { c -> c.name.lowercase() == f } }.toSet()
    val q = query.trim().lowercase()
    val shown = all.filter { x ->
        (q.isEmpty() || x.name.lowercase().contains(q) || x.category.lowercase().contains(q)) &&
            (activeFilter.isEmpty() || x.category.lowercase() in activeFilter)
    }
    val sorted = shown.sortedWith(freezerComparator(sortMode))
    val ids = all.map { x -> x.id }.toSet()
    val sel = selected.filter { id -> id in ids }.toSet()
    val hasBlank = all.any { x -> x.category.isBlank() }

    fun emojiOf(name: String): String = r.emojiFor(name)

    BackHandler(enabled = sel.isNotEmpty()) { selected = emptySet() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(20.dp, 20.dp, 20.dp, 150.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column {
                    TopTabs(tab, onTab)
                    Gap(h = 14)
                    SearchBar(query) { query = it }
                    Gap(h = 12)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DropButton("Sorter", SORT_LABELS[sortMode], openMenu == 1, Modifier.weight(1f)) {
                            openMenu = if (openMenu == 1) 0 else 1
                        }
                        DropButton(
                            "Kategori",
                            when {
                                activeFilter.isEmpty() -> "Alle"
                                activeFilter.size == 1 -> cats.firstOrNull { c -> c.name.lowercase() == activeFilter.first() }?.name ?: "Uten kategori"
                                else -> "${activeFilter.size} valgt"
                            },
                            openMenu == 2, Modifier.weight(1f)
                        ) { openMenu = if (openMenu == 2) 0 else 2 }
                    }
                    if (openMenu != 0) {
                        Gap(h = 8)
                        val shape = RoundedCornerShape(18.dp)
                        Column(Modifier.fillMaxWidth().clip(shape).background(P.card).border(1.dp, P.border, shape).padding(8.dp)) {
                            if (openMenu == 1) {
                                SORT_LABELS.forEachIndexed { i, label ->
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                            .tap { sortMode = i; openMenu = 0 }.padding(horizontal = 10.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        T(label, 15, if (i == sortMode) FontWeight.SemiBold else FontWeight.Normal,
                                            if (i == sortMode) P.accent else P.text, Modifier.weight(1f))
                                        if (i == sortMode) Ic("check", P.accent, 18.dp, 2.2f)
                                    }
                                }
                            } else {
                                cats.forEach { c ->
                                    val on = c.name.lowercase() in activeFilter
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                            .tap { catFilter = if (on) activeFilter - c.name.lowercase() else activeFilter + c.name.lowercase() }
                                            .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CheckCircle(on, 24.dp)
                                        Gap(w = 12)
                                        T(c.emoji + "  " + c.name, 15, modifier = Modifier.weight(1f), maxLines = 1)
                                        IconBtn("edit") { editCat = c }
                                    }
                                }
                                if (hasBlank) {
                                    val on = "" in activeFilter
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                            .tap { catFilter = if (on) activeFilter - "" else activeFilter + "" }
                                            .padding(horizontal = 10.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CheckCircle(on, 24.dp)
                                        Gap(w = 12)
                                        T("Uten kategori", 15, color = P.sub, modifier = Modifier.weight(1f))
                                    }
                                }
                                T(
                                    "+ Ny kategori", 14, FontWeight.Medium, P.accent,
                                    Modifier.fillMaxWidth().tap { newCat = true }.padding(horizontal = 10.dp, vertical = 12.dp)
                                )
                                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    T("Nullstill", 14, FontWeight.Medium, P.sub, Modifier.tap { catFilter = emptySet() }.padding(vertical = 6.dp))
                                    Spacer(Modifier.weight(1f))
                                    T("Ferdig", 14, FontWeight.SemiBold, P.accent, Modifier.tap { openMenu = 0 }.padding(vertical = 6.dp))
                                }
                            }
                        }
                    }
                    Gap(h = 4)
                }
            }
            if (sorted.isEmpty()) {
                item {
                    if (all.isEmpty()) Empty("Fryseren er tom", "Trykk + når du legger noe nytt i fryseren.")
                    else Empty("Ingen treff", "Prøv et annet søk eller en annen kategori.")
                }
            } else {
                items(sorted, key = { x -> x.id }) { x ->
                    FreezerRow(
                        item = x, emoji = emojiOf(x.category), selected = x.id in sel,
                        onToggle = { selected = if (x.id in sel) sel - x.id else sel + x.id },
                        onMenu = { menuFor = x },
                        onQty = { d -> r.changeQty(x, d) }
                    )
                }
            }
        }

        if (sel.isEmpty()) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 24.dp)
                    .shadow(14.dp, RoundedCornerShape(20.dp), spotColor = P.accent, ambientColor = P.accent)
                    .size(58.dp).clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF4A86FF), Color(0xFF2456E8))))
                    .tap { showAdd = true },
                contentAlignment = Alignment.Center
            ) { Ic("plus", Color.White, 26.dp, 2.4f) }
        } else {
            val barShape = RoundedCornerShape(24.dp)
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)
                    .shadow(12.dp, barShape, spotColor = P.accent, ambientColor = P.accent)
                    .clip(barShape).background(P.card.copy(alpha = 1f)).border(1.dp, P.accent.copy(alpha = 0.5f), barShape)
                    .padding(start = 6.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                T(
                    "Avbryt", 15, FontWeight.Medium, P.sub,
                    Modifier.tap { selected = emptySet() }.padding(horizontal = 14.dp, vertical = 12.dp)
                )
                PillButton("Ta ut av fryseren (${sel.size})", Modifier.weight(1f)) {
                    r.removeFreezer(sel)
                    selected = emptySet()
                }
            }
        }
    }

    if (showAdd) FreezerDialog(
        item = null, r = r, onDismiss = { showAdd = false },
        onSave = { n, c, d, qty, p -> r.addFreezer(n, c, d, qty, p); showAdd = false }
    )
    editing?.let { m ->
        FreezerDialog(
            item = m, r = r, onDismiss = { editing = null },
            onSave = { n, c, d, qty, p -> r.updateFreezer(m.id, n, c, d, qty, p); editing = null }
        )
    }
    editCat?.let { c -> CategoryDialog(r, c, onDismiss = { editCat = null }) }
    if (newCat) CategoryDialog(r, null, onDismiss = { newCat = false })
    menuFor?.let { m ->
        Sheet(onDismiss = { menuFor = null }) {
            Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                EmojiTile(emojiOf(m.category), 40.dp)
                Gap(w = 12)
                T(m.name, 17, FontWeight.SemiBold, maxLines = 1)
            }
            SheetRow("edit", "Rediger") { menuFor = null; editing = m }
            SheetRow("up", "Ta ut av fryseren") {
                r.removeFreezer(listOf(m.id))
                selected = selected - m.id
                menuFor = null
            }
        }
    }
}

@Composable
fun FreezerRow(
    item: FreezerItem, emoji: String, selected: Boolean,
    onToggle: () -> Unit, onMenu: () -> Unit, onQty: (Int) -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    val exp = item.expiry
    val info = expiryInfo(exp)
    val expColor = when (info.second) {
        2 -> P.danger
        1 -> Color(0xFFF5A524)
        else -> P.sub
    }
    val photo = remember(item.photo) { decodePhoto(item.photo) }
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) P.accentSoft else P.card)
            .border(1.dp, if (selected) P.accent else P.border, shape)
            .tap(onClick = onToggle)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(P.accentSoft),
            contentAlignment = Alignment.Center
        ) {
            if (photo != null) Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else T(emoji, 26)
        }
        Gap(w = 14)
        Column(Modifier.weight(1f)) {
            T(item.name, 16, FontWeight.SemiBold, maxLines = 1)
            T(info.first, 13, FontWeight.Medium, expColor)
            if (item.category.isNotBlank()) T(item.category, 12, color = P.sub, maxLines = 1)
            Gap(h = 6)
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("Antall", 12, color = P.sub)
                Gap(w = 10)
                MiniBtn("minus", 30, item.qty > 1) { onQty(-1) }
                T("${item.qty}", 15, FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp))
                MiniBtn("plus", 30, item.qty < 999) { onQty(1) }
            }
        }
        CheckCircle(selected)
        Gap(w = 2)
        IconBtn("more", onClick = onMenu)
    }
}

// ───────────────────────── Kategorier ─────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CategoryDialog(
    r: Repo, cat: FreezerCat?, onDismiss: () -> Unit,
    onSaved: (String) -> Unit = {}, onDeleted: () -> Unit = {}
) {
    var name by remember { mutableStateOf(cat?.name ?: "") }
    var emoji by remember { mutableStateOf(cat?.emoji ?: "🧊") }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current

    Sheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            T(if (cat == null) "Ny kategori" else "Rediger kategori", 22, FontWeight.SemiBold, spacing = -0.5f)
            Gap(h = 18)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(P.accentSoft)
                        .border(1.dp, P.accent.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                        .tap { focus.requestFocus(); kb?.show() },
                    contentAlignment = Alignment.Center
                ) {
                    BasicTextField(
                        value = emoji,
                        onValueChange = { nv -> val g = lastGrapheme(nv); if (g.isNotBlank()) emoji = g },
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
                FREEZER_EMOJI.forEach { e ->
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                            .background(if (e == emoji) P.accentSoft else P.bg)
                            .border(1.dp, if (e == emoji) P.accent else P.border, RoundedCornerShape(13.dp))
                            .tap { emoji = e },
                        contentAlignment = Alignment.Center
                    ) { T(e, 20) }
                }
            }
            Gap(h = 18)
            Field("Navn", name, "F.eks. Grønnsaker") { name = it }
            Gap(h = 22)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Avbryt", Modifier.weight(1f), filled = false, onClick = onDismiss)
                PillButton("Lagre", Modifier.weight(1f), enabled = name.isNotBlank()) {
                    if (cat == null) onSaved(r.addCategory(name, emoji))
                    else {
                        r.updateCategory(cat, name, emoji)
                        onSaved(name.trim())
                    }
                    onDismiss()
                }
            }
            if (cat != null) {
                T(
                    "Slett kategori", 14, FontWeight.Medium, P.danger,
                    Modifier.tap { confirmDelete = true }.padding(top = 16.dp, bottom = 2.dp)
                )
            }
        }
    }
    if (confirmDelete && cat != null) Sheet(onDismiss = { confirmDelete = false }) {
        T("Slette kategorien?", 20, FontWeight.SemiBold)
        Gap(h = 8)
        T("«${cat.name}» fjernes. Det som ligger i fryseren i denne kategorien blir værende, men uten kategori.", 14, color = P.sub)
        Gap(h = 20)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Avbryt", Modifier.weight(1f), filled = false) { confirmDelete = false }
            PillButton("Slett", Modifier.weight(1f), color = P.danger) {
                r.deleteCategory(cat)
                confirmDelete = false
                onDeleted()
                onDismiss()
            }
        }
    }
}

// ───────────────────────── Ny / rediger ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FreezerDialog(
    item: FreezerItem?, r: Repo, onDismiss: () -> Unit,
    onSave: (String, String, LocalDate?, Int, String) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cats = r.categories
    var name by remember { mutableStateOf(item?.name ?: "") }
    var category by remember { mutableStateOf(item?.category ?: "") }
    var expiry by remember { mutableStateOf(item?.expiry) }   // no date is chosen automatically
    var qty by remember { mutableIntStateOf(item?.qty ?: 1) }
    var photo by remember { mutableStateOf(item?.photo ?: "") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var newCat by remember { mutableStateOf(false) }
    var editCat by remember { mutableStateOf<FreezerCat?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val preview = remember(photo) { decodePhoto(photo) }

    fun process(uri: Uri) {
        busy = true
        failed = false
        scope.launch {
            val b64 = withContext(Dispatchers.Default) { preparePhoto(ctx, uri) }
            if (b64 != null) photo = b64 else failed = true
            busy = false
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) process(uri)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = cameraUri
        if (ok && u != null) process(u)
    }

    fun takePhoto() {
        try {
            val dir = File(ctx.cacheDir, "images")
            dir.mkdirs()
            val f = File(dir, "frys_" + System.currentTimeMillis() + ".jpg")
            val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
            cameraUri = u
            camera.launch(u)
        } catch (e: Exception) {
            failed = true
        }
    }

    Sheet(onDismiss = { if (!busy) onDismiss() }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            T(if (item == null) "Ny i fryseren" else "Rediger", 22, FontWeight.SemiBold, spacing = -0.5f)
            Gap(h = 18)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(P.accentSoft)
                        .border(1.dp, P.accent.copy(alpha = 0.45f), RoundedCornerShape(26.dp))
                        .tap(!busy) { takePhoto() },
                    contentAlignment = Alignment.Center
                ) {
                    if (preview != null) Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Ic("camera", P.accent, 30.dp)
                }
                Gap(w = 14)
                Column(Modifier.weight(1f)) {
                    PillButton("Ta bilde", Modifier.fillMaxWidth(), filled = false, enabled = !busy) { takePhoto() }
                    Gap(h = 8)
                    PillButton("Velg fra galleri", Modifier.fillMaxWidth(), filled = false, enabled = !busy) {
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
            }
            if (busy) {
                Gap(h = 8)
                T("Behandler bildet…", 13, color = P.sub)
            }
            if (failed) {
                Gap(h = 8)
                T("Kunne ikke bruke bildet. Prøv igjen.", 13, color = P.danger)
            }
            if (photo.isNotEmpty() && !busy) {
                T(
                    "Fjern bilde", 13, FontWeight.Medium, P.danger,
                    Modifier.tap { photo = "" }.padding(top = 8.dp, bottom = 2.dp)
                )
            }
            Gap(h = 18)
            Field("Navn", name, "Uten navn") { name = it }
            Gap(h = 18)

            T("Antall", 12, FontWeight.Medium, P.sub)
            Gap(h = 8)
            Stepper(qty) { qty = it }
            Gap(h = 18)

            T("Utløpsdato (valgfritt)", 12, FontWeight.Medium, P.sub)
            Gap(h = 6)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.bg)
                    .border(1.dp, P.border, RoundedCornerShape(14.dp))
                    .tap { showDate = true }.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val e = expiry
                T(
                    if (e == null) "Ingen utløpsdato" else fmtDate(e), 16,
                    color = if (e == null) P.sub.copy(alpha = 0.8f) else P.text, modifier = Modifier.weight(1f)
                )
                T(if (e == null) "Velg" else "Endre", 13, FontWeight.Medium, P.accent)
            }
            Gap(h = 10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1 mnd" to 1, "3 mnd" to 3, "6 mnd" to 6, "1 år" to 12).forEach { (label, months) ->
                    val target = LocalDate.now().plusMonths(months.toLong())
                    Chip(label, expiry == target) { expiry = target }
                }
            }
            if (expiry != null) {
                T(
                    "Fjern utløpsdato", 13, FontWeight.Medium, P.danger,
                    Modifier.tap { expiry = null }.padding(top = 10.dp, bottom = 2.dp)
                )
            }
            Gap(h = 18)

            T("Kategori", 12, FontWeight.Medium, P.sub)
            Gap(h = 8)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cats.forEach { c ->
                    CatChip(
                        c.emoji + " " + c.name, category.equals(c.name, true),
                        onClick = { category = if (category.equals(c.name, true)) "" else c.name },
                        onEdit = { editCat = c }
                    )
                }
                Chip("+ Ny kategori", false) { newCat = true }
            }
            Gap(h = 22)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Avbryt", Modifier.weight(1f), filled = false, enabled = !busy, onClick = onDismiss)
                PillButton("Lagre", Modifier.weight(1f), enabled = !busy) {
                    onSave(name.trim().ifEmpty { "Uten navn" }, category.trim(), expiry, qty, photo)
                }
            }
        }
    }
    if (showDate) DateSheet(expiry ?: LocalDate.now(), { showDate = false }) { expiry = it }
    if (newCat) CategoryDialog(r, null, onDismiss = { newCat = false }, onSaved = { n -> category = n })
    editCat?.let { c ->
        CategoryDialog(
            r, c, onDismiss = { editCat = null },
            onSaved = { n -> if (category.equals(c.name, true)) category = n },
            onDeleted = { if (category.equals(c.name, true)) category = "" }
        )
    }
}

@Composable
fun DateSheet(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    val today = LocalDate.now()
    val offset = month.atDay(1).dayOfWeek.value - 1
    val len = month.lengthOfMonth()
    val rows = (offset + len + 6) / 7
    Sheet(onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconBtn("left") { month = month.minusMonths(1) }
            T(
                month.format(DateTimeFormatter.ofPattern("MMMM yyyy", NB)).replaceFirstChar { ch -> ch.uppercase() },
                16, FontWeight.SemiBold, modifier = Modifier.weight(1f), align = TextAlign.Center
            )
            IconBtn("right") { month = month.plusMonths(1) }
        }
        Gap(h = 8)
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "O", "T", "F", "L", "S").forEach { d ->
                T(d, 12, FontWeight.Medium, P.sub, Modifier.weight(1f), align = TextAlign.Center)
            }
        }
        Gap(h = 6)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (rw in 0 until rows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (cl in 0 until 7) {
                        val n = rw * 7 + cl - offset + 1
                        if (n in 1..len) {
                            val day = month.atDay(n)
                            val isSel = day == initial
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                                    .background(if (isSel) P.accent else if (day == today) P.accentSoft else Color.Transparent)
                                    .tap { onPick(day); onDismiss() },
                                contentAlignment = Alignment.Center
                            ) { T("$n", 14, FontWeight.Medium, if (isSel) Color.White else P.text) }
                        } else {
                            Spacer(Modifier.weight(1f).aspectRatio(1f))
                        }
                    }
                }
            }
        }
        Gap(h = 14)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Avbryt", Modifier.weight(1f), filled = false, onClick = onDismiss)
            PillButton("I dag", Modifier.weight(1f)) { onPick(today); onDismiss() }
        }
    }
}
