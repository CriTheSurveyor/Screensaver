package dev.standby.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.floor
import java.time.format.TextStyle as DateStyle

/** Left: charging in detail. Right: one panel with time, calendar, weather, next event and alarm. */
@Composable
fun WidgetsPage(now: LocalDateTime, prefs: Prefs, accent: Color) {
    Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        PowerPanel(Modifier.weight(1f))
        InfoPanel(now, prefs.use24h, accent, Modifier.weight(1.25f))
    }
}

private fun Modifier.card() = fillMaxHeight().clip(RoundedCornerShape(36.dp)).background(CardBg)

private val Tabular = TextStyle(fontFeatureSettings = "tnum")

// ---------- battery ----------

@Composable
private fun PowerPanel(modifier: Modifier) {
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
    val sep = DecimalFormatSymbols.getInstance().decimalSeparator
    val whole = floor(p.percent).toInt()
    val cents = ((p.percent - whole) * 100).toInt().coerceIn(0, 99)

    Column(modifier.card().padding(28.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Text(
            listOf(if (p.charging) "In carica" else "Batteria", p.source).filter { it.isNotEmpty() }.joinToString(" · ").uppercase(),
            color = colour, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        )
        Row {
            BigText("$whole", 88.sp, Color.White, FontWeight.Bold, Modifier.alignByBaseline())
            Text(
                "$sep%02d%%".format(cents), Modifier.alignByBaseline(),
                color = Color.White.copy(alpha = 0.6f), fontSize = 34.sp, fontWeight = FontWeight.Bold, style = Tabular,
            )
        }
        Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(colour.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((p.percent / 100).toFloat().coerceIn(0f, 1f)).clip(RoundedCornerShape(6.dp)).background(colour))
        }
        Row {
            Stat("Potenza", p.watts?.let { "%.1f W".format(it) })
            Stat("Corrente", p.milliamps?.let { "$it mA" })
        }
        Row {
            Stat("Tensione", p.volts?.let { "%.2f V".format(it) })
            Stat("Temperatura", p.celsius?.let { "%.1f °C".format(it) })
        }
        Text(p.fullInMs?.let { "Carica completa tra ${duration(it)}" } ?: " ", color = Muted, fontSize = 15.sp)
    }
}

@Composable
private fun RowScope.Stat(label: String, value: String?) = Column(Modifier.weight(1f)) {
    Text(label.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Text(value ?: "—", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, style = Tabular)
}

private fun duration(ms: Long): String {
    val min = (ms / 60_000).toInt().coerceAtLeast(1)
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}

// ---------- info panel ----------

@Composable
private fun InfoPanel(now: LocalDateTime, h24: Boolean, accent: Color, modifier: Modifier) {
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

    Column(modifier.card().padding(horizontal = 28.dp, vertical = 24.dp)) {
        Text(dateText(now, "EEEE d MMMM"), color = accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        BigText(timeText(now, h24), 76.sp, Color.White, FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            MiniMonth(now.toLocalDate(), accent, Modifier.weight(1f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceBetween) {
                val w = weather.weather
                if (w != null) InfoRow("Meteo" + if (w.city.isNotEmpty()) " · ${w.city}" else "", "${w.temp}° ${weatherLabel(w.code)}", "Max ${w.hi}°  Min ${w.lo}°")
                else InfoRow("Meteo", weather.error ?: "", null, small = true)

                val e = events.firstOrNull()
                if (e != null) InfoRow("Prossimo evento", e.title, eventTime(e, now, h24))
                else InfoRow("Calendario", "Nessun evento", "nei prossimi 7 giorni")

                if (alarm != null) {
                    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(alarm), ZoneId.systemDefault())
                    InfoRow("Sveglia", timeText(t, h24), dayLabel(t.toLocalDate(), now.toLocalDate()))
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, sub: String?, small: Boolean = false) = Column {
    Text(label.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(value, color = Color.White, fontSize = if (small) 15.sp else 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (sub != null) Text(sub, color = Muted, fontSize = 14.sp, maxLines = 1)
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
    Column(modifier.fillMaxHeight()) {
        Row {
            for (d in 0 until 7) Text(
                firstDow.plus(d.toLong()).getDisplayName(DateStyle.SHORT, locale).take(1).uppercase(locale),
                Modifier.weight(1f), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
        }
        for (r in 0 until rows) Row(Modifier.weight(1f).fillMaxWidth()) {
            for (c in 0 until 7) {
                val day = r * 7 + c - offset + 1
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    if (day in 1..days) {
                        val isToday = day == today.dayOfMonth
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(if (isToday) accent else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$day", color = if (isToday) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
