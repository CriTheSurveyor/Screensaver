package dev.standby.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private val RowBg = Color(0xFF3A3A3C)

/**
 * iOS StandBy's notification: the app icon in a big square springs up in the middle of the screen,
 * holds 4 seconds, then shrinks and flies into the notifications tile. New ones queue behind it.
 */
@Composable
fun NotifPopup(dock: Offset) {
    var current by remember { mutableStateOf<Notif?>(null) }
    val scale = remember { Animatable(0.3f) }
    val alpha = remember { Animatable(0f) }
    val fly = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // collect runs one at a time, so a burst of notifications plays in order instead of overlapping
        Bus.incoming.collect { n ->
            current = n
            fly.snapTo(0f); scale.snapTo(0.3f); alpha.snapTo(0f)
            coroutineScope {
                launch { alpha.animateTo(1f, tween(220)) }
                // a soft overshoot, like the iOS spring
                scale.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 320f))
            }
            delay(4000)
            coroutineScope {
                val ease = tween<Float>(650, easing = FastOutSlowInEasing)
                launch { scale.animateTo(0.12f, ease) }
                launch { alpha.animateTo(0f, tween(260, delayMillis = 420)) }
                fly.animateTo(1f, ease)
            }
            current = null
        }
    }
    val n = current ?: return
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val side = minOf(maxHeight * 0.66f, 300.dp)
        val centre = with(density) { Offset(maxWidth.toPx() / 2, maxHeight.toPx() / 2) }
        // no tile measured yet (StandBy opened on another page): fly to where the tile sits, bottom left of centre
        val target = if (dock.isSpecified) dock else with(density) { Offset(maxWidth.toPx() * 0.45f, maxHeight.toPx() * 0.7f) }
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value * (1 - fly.value) }.background(Color.Black.copy(alpha = 0.6f)))
        Column(
            Modifier.size(side)
                .graphicsLayer {
                    translationX = (target.x - centre.x) * fly.value
                    translationY = (target.y - centre.y) * fly.value
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                }
                .clip(RoundedCornerShape(side * 0.2f)).background(CardBg).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppIcon(n, side * 0.42f)
            Spacer(Modifier.height(14.dp))
            Label(n.app)
            Spacer(Modifier.height(4.dp))
            Text(
                n.title.ifBlank { n.text }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = LocalFont.current, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AppIcon(n: Notif, size: androidx.compose.ui.unit.Dp) {
    val bmp = n.icon
    if (bmp != null) Image(bmp, null, Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)))
    else Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(RowBg), contentAlignment = Alignment.Center) {
        Title(n.app.take(1).uppercase())
    }
}

/** The notifications tile: newest first, a tap opens the whole text. */
@Composable
fun ColumnScope.NotifList(list: List<Notif>, listening: Boolean, h24: Boolean, accent: Color, onOpen: (Notif) -> Unit) {
    Label(if (list.isEmpty()) "Notifiche" else "Notifiche · ${list.size}", accent)
    Spacer(Modifier.height(8.dp))
    if (!listening || list.isEmpty()) {
        Spacer(Modifier.weight(1f))
        Body(if (listening) "Nessuna notifica" else "Notifiche spente", color = Muted)
        if (!listening) Caption("Consenti l'accesso alle notifiche nelle impostazioni dell'app", lines = 3)
        Spacer(Modifier.weight(1f))
        return
    }
    LazyColumn(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(list, key = { it.key }) { n ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RowBg).clickable { onOpen(n) }.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppIcon(n, 34.dp)
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
    }
}

/** The full notification over the widgets: every line of the text, scrollable, tap anywhere to close. */
@Composable
fun NotifPreview(n: Notif?, h24: Boolean, onClose: () -> Unit) {
    // keeps the last one on screen while the exit animation plays
    var shown by remember { mutableStateOf(n) }
    if (n != null) shown = n
    AnimatedVisibility(n != null, enter = fadeIn(tween(200)) + scaleIn(spring(0.7f, 400f), initialScale = 0.92f), exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.95f)) {
        val m = shown ?: return@AnimatedVisibility
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(interactionSource = null, indication = null, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            // as tall as the text needs, up to most of the screen; longer text scrolls
            Column(Modifier.fillMaxWidth(0.62f).fillMaxHeight(0.86f).wrapContentHeight().clip(RoundedCornerShape(32.dp)).background(CardBg).padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(m, 44.dp)
                    Column(Modifier.weight(1f)) {
                        Label(m.app)
                        Caption(clock(m.time, h24))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (m.title.isNotBlank()) Text(m.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = LocalFont.current)
                    Spacer(Modifier.height(8.dp))
                    Text(m.text, color = Color.White.copy(alpha = 0.9f), fontSize = 18.sp, lineHeight = 26.sp, fontFamily = LocalFont.current)
                }
            }
        }
    }
}

private fun clock(ms: Long, h24: Boolean) =
    timeText(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()), h24)
