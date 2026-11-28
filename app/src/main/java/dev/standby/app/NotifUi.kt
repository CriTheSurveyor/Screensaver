package dev.standby.app

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private val RowBg = Color(0xFF3A3A3C)
// one inner spacing for pop-up and preview: card padding, icon to text, line to line
private val CardPad = 24.dp
private val IconGap = 16.dp
private val LineGap = 8.dp
private const val SlideMs = 280
private const val StaggerMs = 45

/** Where each notification row's icon sits on screen, so a big logo can turn into it, and back. */
class Dock {
    val icons = mutableStateMapOf<String, Rect>()
    /** The row icon a flying logo is standing in for: hidden meanwhile, so it never shows twice. */
    var hidden by mutableStateOf<String?>(null)
}

/** Blurs whatever it's on by [amount] 0..1; Android 12+ only, older phones keep just the dark veil. */
fun Modifier.blurBy(amount: () -> Float) = graphicsLayer {
    val r = amount().coerceIn(0f, 1f) * 28.dp.toPx()
    renderEffect = if (Build.VERSION.SDK_INT >= 31 && r > 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
}

/**
 * Moves and scales an icon from where it's laid out (at 0) onto [other] (at 1). With no [other] on screen it just shrinks away.
 * Only the layer reads [progress], so a flight costs no recomposition.
 */
@Composable
private fun Modifier.morph(other: () -> Rect?, progress: () -> Float): Modifier {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return onGloballyPositioned { coords[0] = it }.graphicsLayer {
        val f = progress()
        val c = coords[0]?.takeIf { it.isAttached } ?: return@graphicsLayer
        val o = other()
        if (o == null) {
            scaleX = 1 - f; scaleY = 1 - f; alpha = 1 - f
            return@graphicsLayer
        }
        val self = Rect(c.positionInRoot(), c.size.toSize())
        translationX = (o.center.x - self.center.x) * f
        translationY = (o.center.y - self.center.y) * f
        val s = 1 + (o.width / self.width - 1) * f
        scaleX = s; scaleY = s
    }
}

/**
 * iOS StandBy's notification over [content] (which gets blurred): the app logo springs up big in the middle,
 * holds 4 seconds, then shrinks into that notification's own icon in the list. New ones queue behind it.
 */
@Composable
fun NotifHost(dock: Dock, content: @Composable (Modifier) -> Unit) {
    var current by remember { mutableStateOf<Notif?>(null) }
    val pop = remember { Animatable(0f) }
    val fly = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // collect runs one at a time, so a burst of notifications plays in order instead of overlapping
        Bus.incoming.collect { n ->
            fly.snapTo(0f); pop.snapTo(0f)
            current = n
            // a soft overshoot, like the iOS spring
            pop.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 320f))
            delay(4000)
            dock.hidden = n.key
            fly.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
            current = null
            dock.hidden = null
        }
    }
    val veil = { pop.value.coerceIn(0f, 1f) * (1 - fly.value) }
    content(Modifier.blurBy(veil))
    val n = current ?: return
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // the logo is half the square: 50% bigger than before, the square grown so the padding stays the same
        val side = minOf(maxHeight * 0.82f, 378.dp)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil() }.background(Color.Black.copy(alpha = 0.45f)))
        Column(
            Modifier.size(side)
                .graphicsLayer {
                    val s = 0.3f + 0.7f * pop.value
                    scaleX = s; scaleY = s
                    alpha = pop.value.coerceIn(0f, 1f)
                }
                // the square fades as the logo leaves it; not clipped, so the logo can fly out
                .drawBehind { drawRoundRect(CardBg, cornerRadius = CornerRadius(side.toPx() * 0.2f), alpha = 1 - fly.value) }
                .padding(CardPad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppIcon(n, side * 0.5f, Modifier.morph({ dock.icons[n.key] }, { fly.value }))
            Spacer(Modifier.height(IconGap))
            Column(Modifier.graphicsLayer { alpha = 1 - fly.value }, horizontalAlignment = Alignment.CenterHorizontally) {
                Label(n.app)
                Spacer(Modifier.height(LineGap))
                Title(n.title.ifBlank { n.text }, lines = 2)
            }
        }
    }
}

@Composable
private fun AppIcon(n: Notif, size: Dp, modifier: Modifier = Modifier) {
    val bmp = n.icon
    val m = modifier.size(size).clip(RoundedCornerShape(size * 0.24f))
    if (bmp != null) Image(bmp, null, m)
    else Box(m.background(RowBg), contentAlignment = Alignment.Center) { Title(n.app.take(1).uppercase()) }
}

/** The notifications tile: newest first, a tap opens the whole text, a button at the end clears them all. */
@Composable
fun ColumnScope.NotifList(list: List<Notif>, listening: Boolean, h24: Boolean, accent: Color, dock: Dock, onOpen: (Notif) -> Unit) {
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    val sweep = remember { Animatable(0f) }
    val latest by rememberUpdatedState(list)
    // iOS "Clear all": the rows slide off to the side one after another, then they go from the phone too
    val stagger = minOf(list.size - 1, 8).coerceAtLeast(0) * StaggerMs
    fun clear() = scope.launch {
        if (sweep.isRunning) return@launch
        sweep.animateTo(1f, tween(SlideMs + stagger, easing = LinearEasing))
        NotifService.clearAll()
        // the ones the phone won't clear (or a listener not connected) slide back in
        withTimeoutOrNull(1500) { snapshotFlow { latest.isEmpty() }.first { it } }
        sweep.snapTo(0f)
    }
    Label(if (list.isEmpty()) "Notifiche" else "Notifiche · ${list.size}", accent)
    Spacer(Modifier.height(LineGap))
    if (!listening || list.isEmpty()) {
        Spacer(Modifier.weight(1f))
        Body(if (listening) "Nessuna notifica" else "Notifiche spente", color = Muted)
        if (!listening) Caption("Consenti l'accesso alle notifiche nelle impostazioni dell'app", lines = 3)
        Spacer(Modifier.weight(1f))
        return
    }
    // a new one lands on top: scroll there, so the pop-up's logo has its row to fly into instead of fading out
    LaunchedEffect(list.first()) { scroll.animateScrollToItem(0) }
    LazyColumn(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)), scroll, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(list, key = { _, n -> n.key }) { i, n ->
            DisposableEffect(n.key) { onDispose { dock.icons.remove(n.key) } }
            Row(
                Modifier.fillMaxWidth()
                    .graphicsLayer {
                        val f = FastOutSlowInEasing.transform(((sweep.value * (SlideMs + stagger) - minOf(i, 8) * StaggerMs) / SlideMs).coerceIn(0f, 1f))
                        translationX = size.width * f
                        alpha = 1 - f
                    }
                    .clip(RoundedCornerShape(16.dp)).background(RowBg).clickable { onOpen(n) }.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppIcon(
                    n, 34.dp,
                    Modifier
                        .onGloballyPositioned { dock.icons[n.key] = Rect(it.positionInRoot(), it.size.toSize()) }
                        .graphicsLayer { alpha = if (dock.hidden == n.key) 0f else 1f },
                )
                Column(Modifier.weight(1f)) {
                    Row {
                        Caption(n.app, Modifier.weight(1f))
                        Caption(clock(n.time, h24))
                    }
                    if (n.title.isNotBlank()) Body(n.title)
                    Caption(n.text, color = Color.White.copy(alpha = 0.75f), lines = 2)
                }
            }
        }
        item(key = "clear") {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).graphicsLayer { alpha = 1 - sweep.value }
                    .clip(RoundedCornerShape(16.dp)).background(RowBg).clickable { clear() },
                contentAlignment = Alignment.Center,
            ) { Body("Cancella tutte", color = accent) }
        }
    }
}

/**
 * The full notification over [content] (blurred behind it): the row's icon grows into the card's header,
 * every line of the text below, scrollable; tap anywhere and the icon goes back into its row.
 */
@Composable
fun NotifPreviewHost(open: Notif?, h24: Boolean, accent: Color, dock: Dock, onClose: () -> Unit, content: @Composable (Modifier) -> Unit) {
    val context = LocalContext.current
    val t = remember { Animatable(0f) }
    var shown by remember { mutableStateOf<Notif?>(null) }
    LaunchedEffect(open) {
        if (open != null) {
            shown = open
            dock.hidden = open.key
            t.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 380f))
        } else if (shown != null) {
            t.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
            dock.hidden = null
            shown = null
        }
    }
    val a = { t.value.coerceIn(0f, 1f) }
    Box(Modifier.fillMaxSize()) {
        content(Modifier.blurBy(a))
        val m = shown ?: return@Box
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = a() }.background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = null, indication = null, onClick = onClose),
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // as tall as the text needs, up to most of the screen; longer text scrolls
            Column(
                Modifier.fillMaxWidth(0.62f).fillMaxHeight(0.86f).wrapContentHeight()
                    .drawBehind { drawRoundRect(CardBg, cornerRadius = CornerRadius(32.dp.toPx()), alpha = a()) }
                    .clickable(interactionSource = null, indication = null, onClick = onClose)
                    .padding(CardPad),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(m, 44.dp, Modifier.morph({ dock.icons[m.key] }, { 1 - t.value }))
                    Column(Modifier.weight(1f).graphicsLayer { alpha = a() }) {
                        Label(m.app)
                        Caption(clock(m.time, h24))
                    }
                }
                Spacer(Modifier.height(IconGap))
                Column(Modifier.weight(1f, fill = false).graphicsLayer { alpha = a() }.verticalScroll(rememberScrollState())) {
                    if (m.title.isNotBlank()) {
                        Title(m.title, lines = Int.MAX_VALUE)
                        Spacer(Modifier.height(LineGap))
                    }
                    Body(m.text, color = Color.White.copy(alpha = 0.9f), lines = Int.MAX_VALUE)
                }
                // outside the scrolling text, so it's always there; only when the app gave the notification a tap action
                if (m.intent != null) {
                    Spacer(Modifier.height(IconGap))
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).graphicsLayer { alpha = a() }
                            .clip(RoundedCornerShape(16.dp)).background(RowBg)
                            .clickable { onClose(); context.openNotif(m) },
                        contentAlignment = Alignment.Center,
                    ) { Body("Apri in ${m.app}", color = accent) }
                }
            }
        }
    }
}

private fun clock(ms: Long, h24: Boolean) =
    timeText(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()), h24)
