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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
import kotlin.math.floor
import java.time.format.TextStyle as DateStyle

// One spacing scale and concentric corners: card 36 = tile 24 + padding 12, as Apple draws nested shapes.
private val Gap = 14.dp
private val CardRadius = 36.dp
private val TileRadius = 24.dp
private val Pad = 12.dp
private val TileBg = Color(0xFF2C2C2E)
private val Hairline = Color(0xFF3A3A3C)
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** Left: charging, the ring running round the whole card. Right: time, weather, calendar, alarm and next event in four tiles. */
@Composable
fun WidgetsPage(now: LocalDateTime, prefs: Prefs, accent: Color) {
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(Gap)) {
        PowerCard(Modifier.weight(1f))
        InfoCard(now, prefs.use24h, accent, Modifier.weight(1.4f))
    }
}

@Composable
private fun Label(text: String, color: Color = Muted, modifier: Modifier = Modifier) = Text(
    text.uppercase(), modifier,
    color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em,
    maxLines = 1, overflow = TextOverflow.Ellipsis,
)

@Composable
private fun Tile(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(TileRadius)).background(TileBg).padding(16.dp), content = content)

// ---------- battery ----------

@Composable
private fun PowerCard(modifier: Modifier) {
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
        0.35f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse",
    )
    val sep = DecimalFormatSymbols.getInstance().decimalSeparator
    val whole = floor(p.percent).toInt()
    val cents = ((p.percent - whole) * 100).toInt().coerceIn(0, 99)

    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(CardRadius)).background(CardBg)
            .chargeRing(progress, colour, if (p.charging) pulse else 0f)
            .padding(horizontal = 26.dp, vertical = 24.dp),
    ) {
        Label(listOf(if (p.charging) "In carica" else "Batteria", p.source).filter { it.isNotEmpty() }.joinToString(" · "), colour)
        Spacer(Modifier.height(6.dp))
        Row {
            BigText("$whole", 84.sp, Color.White, FontWeight.Bold, Modifier.alignByBaseline())
            Text(
                "$sep%02d%%".format(cents), Modifier.alignByBaseline(),
                color = Color.White.copy(alpha = 0.55f), fontSize = 32.sp, fontWeight = FontWeight.SemiBold, style = Tabular,
            )
        }
        Text(
            p.fullInMs?.let { "Carica completa tra ${duration(it)}" } ?: if (p.charging) "Calcolo del tempo…" else "Non in carica",
            color = Muted, fontSize = 15.sp,
        )
        Spacer(Modifier.weight(1f))
        // the four live readings in their own box, split by hairlines
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(TileBg)) {
            Row(Modifier.height(64.dp)) {
                Stat("Potenza", p.watts?.let { "%.1f W".format(it) })
                VLine()
                Stat("Corrente", p.milliamps?.let { "$it mA" })
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Hairline))
            Row(Modifier.height(64.dp)) {
                Stat("Tensione", p.volts?.let { "%.2f V".format(it) })
                VLine()
                Stat("Temperatura", p.celsius?.let { "%.1f °C".format(it) })
            }
        }
    }
}

/** Progress drawn along the card's own rounded edge, starting top centre and running clockwise. */
private fun Modifier.chargeRing(progress: Float, colour: Color, glow: Float) = drawBehind {
    val w = 6.dp.toPx()
    val inset = w / 2 + 2.dp.toPx()
    val r = CardRadius.toPx() - inset
    val l = inset; val t = inset; val rt = size.width - inset; val b = size.height - inset
    val path = Path().apply {
        moveTo((l + rt) / 2, t)
        lineTo(rt - r, t)
        arcTo(Rect(rt - 2 * r, t, rt, t + 2 * r), -90f, 90f, false)
        lineTo(rt, b - r)
        arcTo(Rect(rt - 2 * r, b - 2 * r, rt, b), 0f, 90f, false)
        lineTo(l + r, b)
        arcTo(Rect(l, b - 2 * r, l + 2 * r, b), 90f, 90f, false)
        lineTo(l, t + r)
        arcTo(Rect(l, t, l + 2 * r, t + 2 * r), 180f, 90f, false)
        close()
    }
    drawPath(path, colour.copy(alpha = 0.16f), style = Stroke(w))
    if (progress <= 0f) return@drawBehind
    val measure = PathMeasure().apply { setPath(path, false) }
    val end = measure.length * progress
    val done = Path()
    measure.getSegment(0f, end, done, true)
    drawPath(done, colour, style = Stroke(w, cap = StrokeCap.Round))
    if (glow > 0f) drawCircle(colour.copy(alpha = glow * 0.5f), w * 1.6f, measure.getPosition(end))
}

@Composable
private fun RowScope.Stat(label: String, value: String?) =
    Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
        Label(label)
        Text(value ?: "—", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1)
    }

@Composable
private fun VLine() = Box(Modifier.width(0.5.dp).fillMaxHeight().padding(vertical = 12.dp).background(Hairline))

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
                BigText(timeText(now, h24), 58.sp, Color.White, FontWeight.Bold)
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
                if (alarm != null) {
                    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(alarm), ZoneId.systemDefault())
                    Label("Sveglia")
                    Text(timeText(t, h24), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, style = Tabular)
                    Text(dayLabel(t.toLocalDate(), now.toLocalDate()), color = Muted, fontSize = 13.sp)
                } else {
                    Label("Sveglia")
                    Text("Nessuna", color = Muted, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Hairline))
                Spacer(Modifier.weight(1f))
                val e = events.firstOrNull()
                Label("Prossimo evento")
                Text(e?.title ?: "Nessun evento", color = if (e != null) Color.White else Muted, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e?.let { eventTime(it, now, h24) } ?: "nei prossimi 7 giorni", color = Muted, fontSize = 13.sp, maxLines = 1)
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
        Text(state.error ?: "", color = Muted, fontSize = 14.sp, maxLines = 3)
        return
    }
    Row(verticalAlignment = Alignment.Bottom) {
        BigText("${w.temp}°", 40.sp, Color.White, FontWeight.Light, Modifier.alignByBaseline())
        Text("  ${w.hi}° / ${w.lo}°", Modifier.alignByBaseline(), color = Muted, fontSize = 13.sp, style = Tabular)
    }
    Text(weatherLabel(w.code), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
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
    Column(modifier.fillMaxWidth()) {
        Row {
            for (d in 0 until 7) Text(
                firstDow.plus(d.toLong()).getDisplayName(DateStyle.SHORT, locale).take(1).uppercase(locale),
                Modifier.weight(1f), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
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
                                "$day", color = if (isToday) Color.Black else Color.White,
                                fontSize = 12.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium, style = Tabular,
                            )
                        }
                    }
                }
            }
        }
    }
}
