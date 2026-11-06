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
            "image" -> {
                drawRoundRect(tint, topLeft = o(.14f, .2f), size = Size(.72f * w, .6f * w), cornerRadius = CornerRadius(.08f * w), style = st)
                drawCircle(tint, radius = .06f * w, center = o(.36f, .4f))
                drawPath(poly(.18f to .74f, .42f to .52f, .58f to .66f, .68f to .58f, .84f to .74f), tint, style = st)
            }
            "search" -> {
                drawCircle(tint, radius = .27f * w, center = o(.45f, .45f), style = st)
                drawLine(tint, o(.66f, .66f), o(.84f, .84f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            "close" -> {
                drawLine(tint, o(.28f, .28f), o(.72f, .72f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, o(.72f, .28f), o(.28f, .72f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            "camera" -> {
                drawRoundRect(tint, topLeft = o(.12f, .3f), size = Size(.76f * w, .52f * w), cornerRadius = CornerRadius(.1f * w), style = st)
                drawCircle(tint, radius = .14f * w, center = o(.5f, .56f), style = st)
                drawPath(poly(.36f to .3f, .42f to .2f, .58f to .2f, .64f to .3f), tint, style = st)
            }
            "minus" -> drawLine(tint, o(.2f, .5f), o(.8f, .5f), strokeWidth = sw, cap = StrokeCap.Round)
            "down" -> drawPath(poly(.22f to .38f, .5f to .66f, .78f to .38f), tint, style = st)
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
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(P.card.copy(alpha = 1f))
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
            ) { T(o, if (options.size > 2) 12 else 14, FontWeight.Medium, if (i == sel) Color.White else P.sub, maxLines = 1) }
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

