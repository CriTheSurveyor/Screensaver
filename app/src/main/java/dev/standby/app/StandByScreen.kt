package dev.standby.app

import android.net.Uri
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// iOS dark palette
val CardBg = Color(0xFF1C1C1E)
val Muted = Color(0xFF8E8E93)
val Accents = listOf(
    Color(0xFFFFFFFF), Color(0xFFFF453A), Color(0xFFFF9F0A), Color(0xFFFFD60A),
    Color(0xFF30D158), Color(0xFF66D4CF), Color(0xFF0A84FF), Color(0xFFFF375F),
)
private val NightRed = Color(0xFFFF2D1F)

fun timeText(t: LocalDateTime, h24: Boolean): String = t.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm"))
fun dateText(t: LocalDateTime, pattern: String): String =
    t.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault())).replaceFirstChar { it.titlecase(Locale.getDefault()) }

@Composable
fun rememberNow(): State<LocalDateTime> = produceState(LocalDateTime.now()) {
    while (true) {
        value = LocalDateTime.now()
        delay(1000 - System.currentTimeMillis() % 1000)
    }
}

/** Three pages like iOS: widgets, photos, full-screen clock. */
@Composable
fun StandByScreen(prefs: Prefs, night: Boolean) {
    val now by rememberNow()
    var accentIndex by remember { mutableIntStateOf(prefs.accent) }
    val accent = Accents[accentIndex % Accents.size]
    val pager = rememberPagerState(prefs.page) { 3 }
    LaunchedEffect(pager.settledPage) { prefs.page = pager.settledPage }

    // shifts everything a few dp each minute so a static clock doesn't burn into an OLED panel overnight
    val driftX = ((now.minute % 5) - 2) * 2
    val driftY = ((now.minute % 3) - 1) * 2

    Box(Modifier.fillMaxSize().background(Color.Black).nightFilter(night)) {
        HorizontalPager(pager, Modifier.fillMaxSize().offset(driftX.dp, driftY.dp)) { page ->
            when (page) {
                0 -> WidgetsPage(now, prefs, accent)
                1 -> PhotosPage(now, prefs)
                else -> ClockPage(now, prefs, accent) {
                    accentIndex = (accentIndex + 1) % Accents.size
                    prefs.accent = accentIndex
                }
            }
        }
    }
}

/** Desaturate, then multiply by red: every page turns red without each widget knowing about night mode. */
private fun Modifier.nightFilter(on: Boolean) = if (!on) this else this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (Build.VERSION.SDK_INT >= 29) drawRect(Color.Gray, blendMode = BlendMode.Saturation)
        drawRect(NightRed, blendMode = BlendMode.Modulate)
    }

// ---------- clock page: swipe up/down for the style, tap for the colour ----------

@Composable
private fun ClockPage(now: LocalDateTime, prefs: Prefs, accent: Color, nextColour: () -> Unit) {
    val pager = rememberPagerState(prefs.clockStyle) { 4 }
    LaunchedEffect(pager.settledPage) { prefs.clockStyle = pager.settledPage }
    VerticalPager(
        pager,
        Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = nextColour),
    ) { style ->
        when (style) {
            0 -> DigitalClock(now, prefs.use24h, accent)
            1 -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) { AnalogClock(accent, Modifier) }
            2 -> SolarClock(now, prefs.use24h, accent)
            else -> StackedClock(now, prefs.use24h, accent)
        }
    }
}

@Composable
fun BigText(text: String, size: TextUnit, color: Color, weight: FontWeight, modifier: Modifier = Modifier) = Text(
    text, modifier,
    color = color, fontSize = size, fontWeight = weight,
    letterSpacing = (-0.03).em, lineHeight = size, maxLines = 1, softWrap = false,
    style = TextStyle(
        fontFeatureSettings = "tnum",
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    ),
)

@Composable
private fun DigitalClock(now: LocalDateTime, h24: Boolean, accent: Color) =
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val size = with(LocalDensity.current) { minOf(maxWidth / 2.9f, maxHeight * 0.9f).toSp() }
        BigText(timeText(now, h24), size, accent, FontWeight.Bold)
    }

@Composable
private fun StackedClock(now: LocalDateTime, h24: Boolean, accent: Color) =
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val size = with(LocalDensity.current) { minOf(maxHeight * 0.47f, maxWidth / 1.5f).toSp() }
        val (h, m) = timeText(now, h24).split(":")
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BigText(h, size, accent, FontWeight.Black)
            BigText(m, size, Color.White, FontWeight.Black)
        }
    }

@Composable
private fun SolarClock(now: LocalDateTime, h24: Boolean, accent: Color) =
    BoxWithConstraints(
        Modifier.fillMaxSize().drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(size.width / 2, size.height * 1.15f),
                    radius = size.width * 0.65f,
                )
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        val size = with(LocalDensity.current) { minOf(maxWidth / 3.1f, maxHeight * 0.62f).toSp() }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BigText(timeText(now, h24), size, Color.White, FontWeight.Thin)
            Text(dateText(now, "EEEE d MMMM"), color = Muted, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
    }

/** Smooth sweeping second hand: redraws every frame, cheap because only the Canvas draw phase reads the time. */
@Composable
fun AnalogClock(accent: Color, modifier: Modifier, numbers: Boolean = true) {
    val time by produceState(LocalTime.now()) {
        while (true) androidx.compose.runtime.withFrameMillis { value = LocalTime.now() }
    }
    val measurer = rememberTextMeasurer()
    Canvas(modifier.aspectRatio(1f).fillMaxSize()) {
        val r = size.minDimension / 2
        val c = center
        for (i in 0 until 60) {
            val major = i % 5 == 0
            rotate(i * 6f, c) {
                drawLine(
                    if (major) Color.White else Muted,
                    Offset(c.x, c.y - r), Offset(c.x, c.y - r + if (major) r * 0.07f else r * 0.035f),
                    strokeWidth = if (major) r * 0.018f else r * 0.008f, cap = StrokeCap.Round,
                )
            }
        }
        if (numbers) for (n in 1..12) {
            val a = Math.toRadians(n * 30.0 - 90)
            val layout = measurer.measure(
                n.toString(),
                TextStyle(color = Color.White, fontSize = (r * 0.17f).toSp(), fontWeight = FontWeight.SemiBold),
            )
            val p = Offset(c.x + cos(a).toFloat() * r * 0.76f, c.y + sin(a).toFloat() * r * 0.76f)
            drawText(layout, topLeft = p - Offset(layout.size.width / 2f, layout.size.height / 2f))
        }
        val sec = time.second + time.nano / 1e9f
        val min = time.minute + sec / 60f
        val hour = time.hour % 12 + min / 60f
        hand(hour * 30f, r * 0.5f, r * 0.06f, Color.White)
        hand(min * 6f, r * 0.8f, r * 0.045f, Color.White)
        hand(sec * 6f, r * 0.9f, r * 0.016f, accent, tail = r * 0.16f)
        drawCircle(accent, r * 0.04f, c)
        drawCircle(Color.Black, r * 0.016f, c)
    }
}

private fun DrawScope.hand(deg: Float, len: Float, width: Float, color: Color, tail: Float = 0f) = rotate(deg, center) {
    drawLine(color, Offset(center.x, center.y + tail), Offset(center.x, center.y - len), width, StrokeCap.Round)
}

// ---------- photos page ----------

@Composable
private fun PhotosPage(now: LocalDateTime, prefs: Prefs) {
    val context = LocalContext.current
    val uris = remember { prefs.photos.map(Uri::parse).shuffled() }
    if (uris.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Scegli le foto dalle impostazioni dell'app", color = Muted, fontSize = 18.sp)
        }
        return
    }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            index = (index + 1) % uris.size
        }
    }
    val photo by produceState<ImageBitmap?>(null, index) {
        value = withContext(Dispatchers.IO) { loadBitmap(context, uris[index]) } ?: value
    }
    Box(Modifier.fillMaxSize()) {
        Crossfade(photo, animationSpec = tween(1200), label = "photo") { bmp ->
            if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        // keeps the white clock readable on bright photos
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.5f), 0.55f to Color.Transparent)))
        Column(Modifier.padding(horizontal = 36.dp, vertical = 28.dp)) {
            Text(dateText(now, "EEEE d MMMM"), color = Color.White.copy(alpha = 0.85f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            BigText(timeText(now, prefs.use24h), 96.sp, Color.White, FontWeight.Bold)
        }
    }
}
