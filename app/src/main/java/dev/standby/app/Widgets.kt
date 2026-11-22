package dev.standby.app

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Two stacks side by side; swipe each up/down to change widget, as on iOS. */
@Composable
fun WidgetsPage(now: LocalDateTime, prefs: Prefs, accent: Color) {
    Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        WidgetStack(prefs.stackA, { prefs.stackA = it }, 3, Modifier.weight(1f)) { i ->
            when (i) {
                0 -> Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) { AnalogClock(accent, Modifier) }
                1 -> DateWidget(now, prefs.use24h, accent)
                else -> BatteryWidget(now)
            }
        }
        WidgetStack(prefs.stackB, { prefs.stackB = it }, 3, Modifier.weight(1f)) { i ->
            when (i) {
                0 -> MonthWidget(now.toLocalDate(), accent)
                1 -> EventsWidget(now, prefs.use24h, accent)
                else -> WeatherWidget()
            }
        }
    }
}

@Composable
private fun WidgetStack(initial: Int, save: (Int) -> Unit, count: Int, modifier: Modifier, content: @Composable (Int) -> Unit) {
    val pager = rememberPagerState(initial.coerceIn(0, count - 1)) { count }
    LaunchedEffect(pager.settledPage) { save(pager.settledPage) }
    // the page dots show only while swiping, then fade away
    val dots by animateFloatAsState(if (pager.isScrollInProgress) 1f else 0f, tween(if (pager.isScrollInProgress) 150 else 900), label = "dots")
    Box(modifier.fillMaxHeight().clip(RoundedCornerShape(36.dp)).background(CardBg)) {
        VerticalPager(pager, Modifier.fillMaxSize()) { content(it) }
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).alpha(dots),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(count) { i ->
                Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == pager.currentPage) 1f else 0.3f)))
            }
        }
    }
}

@Composable
private fun DateWidget(now: LocalDateTime, h24: Boolean, accent: Color) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text(dateText(now, "EEEE").uppercase(), color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        BigText(now.dayOfMonth.toString(), 120.sp, Color.White, FontWeight.Bold)
        Text(dateText(now, "MMMM") + " · " + timeText(now, h24), color = Muted, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BatteryWidget(now: LocalDateTime) {
    val context = LocalContext.current
    val (level, charging) = remember(now.minute) {
        // null receiver = just read the sticky battery broadcast
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val lvl = (i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0) * 100 / scale
        lvl to ((i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
    }
    val ring = if (level <= 20 && !charging) Accents[1] else Accents[4]
    Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.09f
            val d = size.minDimension - stroke
            val topLeft = androidx.compose.ui.geometry.Offset((size.width - d) / 2, (size.height - d) / 2)
            val arc = androidx.compose.ui.geometry.Size(d, d)
            drawArc(ring.copy(alpha = 0.22f), 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
            drawArc(ring, -90f, 360f * level / 100f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BigText("$level%", 52.sp, Color.White, FontWeight.Bold)
            Text(if (charging) "In carica" else "Batteria", color = Muted, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun MonthWidget(today: LocalDate, accent: Color) {
    val locale = Locale.getDefault()
    val firstDow = WeekFields.of(locale).firstDayOfWeek
    val offset = (today.withDayOfMonth(1).dayOfWeek.value - firstDow.value + 7) % 7
    val days = today.lengthOfMonth()
    val rows = (offset + days + 6) / 7
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 20.dp)) {
        Text(
            today.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).uppercase(locale),
            color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            for (d in 0 until 7) Text(
                firstDow.plus(d.toLong()).getDisplayName(TextStyle.SHORT, locale).take(1).uppercase(locale),
                Modifier.weight(1f), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
        }
        for (r in 0 until rows) Row(Modifier.weight(1f).fillMaxWidth()) {
            for (c in 0 until 7) {
                val day = r * 7 + c - offset + 1
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    if (day in 1..days) {
                        val isToday = day == today.dayOfMonth
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(if (isToday) accent else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$day", color = if (isToday) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventsWidget(now: LocalDateTime, h24: Boolean, accent: Color) {
    val context = LocalContext.current
    val events by produceState(emptyList<Event>(), now.minute / 5) {
        value = withContext(Dispatchers.IO) { upcomingEvents(context) }
    }
    Column(Modifier.fillMaxSize().padding(26.dp)) {
        Text(dateText(now, "EEEE").uppercase(), color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        BigText(now.dayOfMonth.toString(), 44.sp, Color.White, FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        if (events.isEmpty()) {
            Text("Nessun evento nei prossimi giorni", color = Muted, fontSize = 16.sp)
        }
        val today = now.toLocalDate()
        events.forEach { e ->
            val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.start), ZoneId.systemDefault())
            val whenText = when {
                e.allDay -> "Tutto il giorno"
                start.toLocalDate() == today -> timeText(start, h24)
                else -> dateText(start, "EEE") + " " + timeText(start, h24)
            }
            Row(Modifier.padding(bottom = 10.dp)) {
                Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(accent))
                Column(Modifier.padding(start = 10.dp)) {
                    Text(e.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(whenText, color = Muted, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun WeatherWidget() {
    val context = LocalContext.current
    val weather by produceState<Weather?>(null) {
        while (true) {
            value = fetchWeather(context) ?: value
            delay(30 * 60_000L)
        }
    }
    val w = weather
    val sky = when {
        w == null -> listOf(CardBg, CardBg)
        !w.day -> listOf(Color(0xFF0B1630), Color(0xFF26386B))
        w.code >= 3 -> listOf(Color(0xFF4A5868), Color(0xFF7B8794))
        else -> listOf(Color(0xFF1F63C7), Color(0xFF5AA0EE))
    }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(sky)).padding(28.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (w == null) {
            Text("Meteo", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Consenti la posizione nelle impostazioni dell'app", color = Muted, fontSize = 16.sp)
            return@Column
        }
        Column {
            Text(w.city.ifEmpty { "Qui" }, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            BigText("${w.temp}°", 84.sp, Color.White, FontWeight.Light)
        }
        Column {
            Text(weatherLabel(w.code), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Max ${w.hi}°  Min ${w.lo}°", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
        }
    }
}
