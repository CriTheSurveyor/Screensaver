package dev.standby.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import java.time.format.TextStyle as DateStyle

// One spacing scale and concentric corners: card 36 = tile 24 + padding 12, as Apple draws nested shapes.
private val Gap = 14.dp
private val CardRadius = 36.dp
private val TileRadius = 24.dp
private val Pad = 12.dp
private val TileBg = Color(0xFF2C2C2E)
private val Hairline = Color(0xFF3A3A3C)

/** Left: the charging circle. Right: time, weather, calendar, alarm and next event in four tiles. */
@Composable
fun WidgetsPage(now: LocalDateTime, prefs: Prefs, accent: Color) {
    // wide side margins keep the cards clear of the camera cutout, whichever side it lands on
    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(Gap), verticalAlignment = Alignment.CenterVertically) {
        PowerCircle(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true))
        InfoCard(now, prefs.use24h, accent, Modifier.weight(1f))
    }
}

@Composable
private fun Tile(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(TileRadius)).background(TileBg).padding(16.dp), content = content)

// ---------- battery ----------

@Composable
private fun PowerCircle(modifier: Modifier) {
    val context = LocalContext.current
    val precise = remember { PrecisePercent() }
    val p by produceState(readPower(context, precise)) {
        while (true) {
            delay(1000)
            value = readPower(context, precise)
        }
    }
    val colour = when {
        p.charging -> Accents[4]
        p.level <= 20 -> Accents[1]
        else -> Color.White
    }
    val progress by animateFloatAsState((p.percent / 100).toFloat().coerceIn(0f, 1f), tween(800), label = "charge")
    // the head of the ring breathes while charging, like the iOS charging glyph
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse",
    )
    val sep = DecimalFormatSymbols.getInstance().decimalSeparator
    val whole = floor(p.percent).toInt()
    val cents = ((p.percent - whole) * 100).toInt().coerceIn(0, 99)

    Box(
        modifier.clip(CircleShape).background(CardBg).chargeRing(progress, colour, if (p.charging) pulse else 0f),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Label(listOf(if (p.charging) "In carica" else "Batteria", p.source).filter { it.isNotEmpty() }.joinToString(" · "), colour)
            Row {
                BigText("$whole", 88.sp, Color.White, FontWeight.Bold, Modifier.alignByBaseline())
                Text(
                    "$sep%02d%%".format(cents), Modifier.alignByBaseline(),
                    color = Color.White.copy(alpha = 0.55f), fontSize = 32.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = LocalFont.current, style = TextStyle(fontFeatureSettings = "tnum"),
                )
            }
            Caption(p.fullInMs?.let { "Piena tra ${duration(it)}" } ?: if (p.charging) "Calcolo del tempo…" else "Non in carica")
            Spacer(Modifier.height(14.dp))
            // the four live readings in their own box, split by hairlines; sized to sit inside the circle
            Column(Modifier.width(224.dp).clip(RoundedCornerShape(18.dp)).background(TileBg)) {
                Row(Modifier.height(54.dp)) {
                    Stat("Potenza", p.watts?.let { "%.1f W".format(it) })
                    VLine()
                    Stat("Corrente", p.milliamps?.let { "$it mA" })
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Hairline))
                Row(Modifier.height(54.dp)) {
                    Stat("Tensione", p.volts?.let { "%.2f V".format(it) })
                    VLine()
                    Stat("Temp.", p.celsius?.let { "%.1f °C".format(it) })
                }
            }
        }
    }
}

// a 300° ring open at the bottom, like a gauge: the gap leaves room for the readings
private const val RingStart = 120f
private const val RingSweep = 300f

/** Progress round the circle's own edge, clockwise from the bottom-left end of the gap. */
private fun Modifier.chargeRing(progress: Float, colour: Color, glow: Float) = drawBehind {
    val w = 8.dp.toPx()
    val inset = w / 2 + 3.dp.toPx()
    val d = size.minDimension - 2 * inset
    val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
    val arc = androidx.compose.ui.geometry.Size(d, d)
    drawArc(colour.copy(alpha = 0.16f), RingStart, RingSweep, false, topLeft, arc, style = Stroke(w, cap = StrokeCap.Round))
    if (progress <= 0f) return@drawBehind
    drawArc(colour, RingStart, RingSweep * progress, false, topLeft, arc, style = Stroke(w, cap = StrokeCap.Round))
    if (glow > 0f) {
        val a = Math.toRadians(RingStart + RingSweep * progress.toDouble())
        val head = Offset(center.x + cos(a).toFloat() * d / 2, center.y + sin(a).toFloat() * d / 2)
        drawCircle(colour.copy(alpha = glow * 0.5f), w * 1.5f, head)
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String?) =
    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
        Label(label)
        Body(value ?: "—")
    }

@Composable
private fun VLine() = Box(Modifier.width(0.5.dp).fillMaxHeight().padding(vertical = 10.dp).background(Hairline))

private fun duration(ms: Long): String {
    val min = (ms / 60_000).toInt().coerceAtLeast(1)
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}

// ---------- info ----------

@Composable
private fun InfoCard(now: LocalDateTime, h24: Boolean, accent: Color, modifier: Modifier) {
    val context = LocalContext.current
    val events by produceState(emptyList<Event>(), now.minute / 5) {
        value = withContext(Dispatchers.IO) { upcomingEvents(context) }
    }
    val weather by produceState(WeatherState(null, "Caricamento…")) {
        while (true) {
            val s = fetchWeather(context)
            if (s.weather != null || value.weather == null) value = s
            // a failed try comes back in a minute, a good one lasts half an hour
            delay(if (s.weather != null) 30 * 60_000L else 60_000L)
        }
    }
    val alarm = remember(now.minute) { nextAlarm(context) }

    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(CardRadius)).background(CardBg).padding(Pad),
        verticalArrangement = Arrangement.spacedBy(Pad),
    ) {
        Row(Modifier.weight(0.36f), horizontalArrangement = Arrangement.spacedBy(Pad)) {
            Tile(Modifier.weight(1.25f)) {
                Label(dateText(now, "EEEE d MMMM"), accent)
                Spacer(Modifier.weight(1f))
                BigText(timeText(now, h24), 56.sp, Color.White, FontWeight.Bold)
            }
            Tile(Modifier.weight(1f)) { WeatherTile(weather) }
        }
        Row(Modifier.weight(0.64f), horizontalArrangement = Arrangement.spacedBy(Pad)) {
            Tile(Modifier.weight(1.25f)) {
                Label(now.month.getDisplayName(DateStyle.FULL_STANDALONE, Locale.getDefault()), accent)
                Spacer(Modifier.height(6.dp))
                MiniMonth(now.toLocalDate(), accent, Modifier.weight(1f))
            }
            Tile(Modifier.weight(1f)) {
                Label("Sveglia")
                if (alarm != null) {
                    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(alarm), ZoneId.systemDefault())
                    Title(timeText(t, h24))
                    Caption(dayLabel(t.toLocalDate(), now.toLocalDate()))
                } else {
                    Title("Nessuna", color = Muted)
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Hairline))
                Spacer(Modifier.weight(1f))
                val e = events.firstOrNull()
                Label("Prossimo evento")
                Body(e?.title ?: "Nessun evento", color = if (e != null) Color.White else Muted)
                Caption(e?.let { eventTime(it, now, h24) } ?: "nei prossimi 7 giorni")
            }
        }
    }
}

@Composable
private fun ColumnScope.WeatherTile(state: WeatherState) {
    val w = state.weather
    Label(if (w != null && w.city.isNotEmpty()) w.city else "Meteo")
    Spacer(Modifier.weight(1f))
    if (w == null) {
        Caption(state.error ?: "", lines = 3)
        return
    }
    Row {
        BigText("${w.temp}°", 40.sp, Color.White, FontWeight.Light, Modifier.alignByBaseline())
        Caption("  ${w.hi}° / ${w.lo}°", Modifier.alignByBaseline())
    }
    Caption(weatherLabel(w.code), color = Color.White)
}

private fun dayLabel(day: LocalDate, today: LocalDate) = when (day) {
    today -> "oggi"
    today.plusDays(1) -> "domani"
    else -> day.dayOfWeek.getDisplayName(DateStyle.FULL, Locale.getDefault())
}

private fun eventTime(e: Event, now: LocalDateTime, h24: Boolean): String {
    val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.start), ZoneId.systemDefault())
    val day = dayLabel(start.toLocalDate(), now.toLocalDate())
    return if (e.allDay) "$day, tutto il giorno" else "$day, ${timeText(start, h24)}"
}

@Composable
private fun MiniMonth(today: LocalDate, accent: Color, modifier: Modifier) {
    val locale = Locale.getDefault()
    val firstDow = WeekFields.of(locale).firstDayOfWeek
    val offset = (today.withDayOfMonth(1).dayOfWeek.value - firstDow.value + 7) % 7
    val days = today.lengthOfMonth()
    val rows = (offset + days + 6) / 7
    val font = LocalFont.current
    Column(modifier.fillMaxWidth()) {
        Row {
            for (d in 0 until 7) Text(
                firstDow.plus(d.toLong()).getDisplayName(DateStyle.SHORT, locale).take(1).uppercase(locale),
                Modifier.weight(1f), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = font, textAlign = TextAlign.Center,
            )
        }
        for (r in 0 until rows) Row(Modifier.weight(1f).fillMaxWidth()) {
            for (c in 0 until 7) {
                val day = r * 7 + c - offset + 1
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    if (day in 1..days) {
                        val isToday = day == today.dayOfMonth
                        // the circle takes the row's height, so six-week months still fit
                        Box(
                            Modifier.fillMaxHeight(0.92f).aspectRatio(1f).clip(CircleShape)
                                .background(if (isToday) accent else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$day", color = if (isToday) Color.Black else Color.White, fontFamily = font,
                                fontSize = 13.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                                style = TextStyle(fontFeatureSettings = "tnum"),
                            )
                        }
                    }
                }
            }
        }
    }
}
