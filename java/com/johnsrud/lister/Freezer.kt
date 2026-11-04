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
import androidx.compose.ui.draw.clip
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
fun expiryInfo(d: LocalDate): Pair<String, Int> {
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

// ───────────────────────── Små byggeklosser ─────────────────────────

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

// ───────────────────────── Fryserdatabase ─────────────────────────

@Composable
fun FreezerScreen(r: Repo, tab: Int, onTab: (Int) -> Unit, onBackground: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FreezerItem?>(null) }
    var menuFor by remember { mutableStateOf<FreezerItem?>(null) }

    val all = r.freezer
    val allCats = (DEFAULT_CATS + r.customCategories).distinctBy { c -> c.lowercase() }
    val q = query.trim().lowercase()
    val shown = all.filter { x ->
        q.isEmpty() || x.name.lowercase().contains(q) || x.category.lowercase().contains(q)
    }
    val sorted = if (sortMode == 0) {
        shown.sortedWith(compareBy<FreezerItem> { x -> x.expiry }.thenBy { x -> x.name.lowercase() })
    } else {
        shown.sortedWith(
            compareBy<FreezerItem>(
                { x -> allCats.indexOfFirst { c -> c.equals(x.category, true) }.let { n -> if (n < 0) Int.MAX_VALUE else n } },
                { x -> x.expiry },
                { x -> x.name.lowercase() }
            )
        )
    }
    val ids = all.map { x -> x.id }.toSet()
    val sel = selected.filter { id -> id in ids }.toSet()

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
                    Segmented(listOf("Utløpsdato", "Kategori"), sortMode) { sortMode = it }
                    Gap(h = 4)
                }
            }
            if (sorted.isEmpty()) {
                item {
                    if (all.isEmpty()) Empty("Fryseren er tom", "Trykk + når du legger noe nytt i fryseren.")
                    else Empty("Ingen treff", "Prøv et annet søk.")
                }
            } else if (sortMode == 0) {
                items(sorted, key = { x -> x.id }) { x ->
                    FreezerRow(
                        item = x, selected = x.id in sel,
                        onToggle = { selected = if (x.id in sel) sel - x.id else sel + x.id },
                        onMenu = { menuFor = x }
                    )
                }
            } else {
                val groups = sorted.groupBy { x -> x.category.ifBlank { "Uten kategori" } }
                groups.forEach { (cat, group) ->
                    item(key = "h_$cat") {
                        Row(Modifier.padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            T(catEmoji(cat), 14)
                            Gap(w = 8)
                            T(cat.uppercase(), 11, FontWeight.SemiBold, P.sub, spacing = 1f)
                            Gap(w = 8)
                            T("${group.size}", 11, FontWeight.Medium, P.sub)
                        }
                    }
                    items(group, key = { x -> x.id }) { x ->
                        FreezerRow(
                            item = x, selected = x.id in sel,
                            onToggle = { selected = if (x.id in sel) sel - x.id else sel + x.id },
                            onMenu = { menuFor = x }
                        )
                    }
                }
            }
            item { BgLink(onBackground) }
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
        item = null, cats = allCats, onDismiss = { showAdd = false },
        onSave = { n, c, d, p -> r.addFreezer(n, c, d, p); showAdd = false }
    )
    editing?.let { m ->
        FreezerDialog(
            item = m, cats = allCats, onDismiss = { editing = null },
            onSave = { n, c, d, p -> r.updateFreezer(m.id, n, c, d, p); editing = null }
        )
    }
    menuFor?.let { m ->
        Sheet(onDismiss = { menuFor = null }) {
            Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                EmojiTile(catEmoji(m.category), 40.dp)
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
fun FreezerRow(item: FreezerItem, selected: Boolean, onToggle: () -> Unit, onMenu: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val info = expiryInfo(item.expiry)
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
            else T(catEmoji(item.category), 26)
        }
        Gap(w = 14)
        Column(Modifier.weight(1f)) {
            T(item.name, 16, FontWeight.SemiBold, maxLines = 1)
            T(info.first, 13, FontWeight.Medium, expColor)
            if (item.category.isNotBlank()) T(item.category, 12, color = P.sub)
        }
        CheckCircle(selected)
        Gap(w = 2)
        IconBtn("more", onClick = onMenu)
    }
}

// ───────────────────────── Ny / rediger ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FreezerDialog(
    item: FreezerItem?, cats: List<String>, onDismiss: () -> Unit,
    onSave: (String, String, LocalDate, String) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(item?.name ?: "") }
    var category by remember { mutableStateOf(item?.category ?: "") }
    var expiry by remember { mutableStateOf(item?.expiry ?: LocalDate.now().plusMonths(3)) }
    var photo by remember { mutableStateOf(item?.photo ?: "") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var newCat by remember { mutableStateOf(false) }
    var newCatText by remember { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }
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

            T("Utløpsdato", 12, FontWeight.Medium, P.sub)
            Gap(h = 6)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.bg)
                    .border(1.dp, P.border, RoundedCornerShape(14.dp))
                    .tap { showDate = true }.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                T(fmtDate(expiry), 16, modifier = Modifier.weight(1f))
                T("Endre", 13, FontWeight.Medium, P.accent)
            }
            Gap(h = 10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1 mnd" to 1, "3 mnd" to 3, "6 mnd" to 6, "1 år" to 12).forEach { (label, months) ->
                    val target = LocalDate.now().plusMonths(months.toLong())
                    Chip(label, expiry == target) { expiry = target }
                }
            }
            Gap(h = 18)

            T("Kategori", 12, FontWeight.Medium, P.sub)
            Gap(h = 8)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cats.forEach { c ->
                    Chip(catEmoji(c) + " " + c, !newCat && category.equals(c, true)) {
                        category = if (category.equals(c, true)) "" else c
                        newCat = false
                    }
                }
                Chip("+ Ny kategori", newCat) {
                    newCat = !newCat
                    newCatText = ""
                    category = ""
                }
            }
            if (newCat) {
                Gap(h = 10)
                Field("Skriv inn kategori", newCatText, "F.eks. Grønnsaker") {
                    newCatText = it
                    category = it.trim()
                }
            }
            Gap(h = 22)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Avbryt", Modifier.weight(1f), filled = false, enabled = !busy, onClick = onDismiss)
                PillButton("Lagre", Modifier.weight(1f), enabled = !busy) {
                    onSave(name.trim().ifEmpty { "Uten navn" }, category.trim(), expiry, photo)
                }
            }
        }
    }
    if (showDate) DateSheet(expiry, { showDate = false }) { expiry = it }
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
