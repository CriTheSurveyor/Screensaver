package dev.standby.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.sin

private enum class Sky { Clear, Partly, Cloudy, Fog, Drizzle, Rain, Snow, Storm }

private fun sky(code: Int) = when (code) {
    0 -> Sky.Clear
    1, 2 -> Sky.Partly
    3 -> Sky.Cloudy
    45, 48 -> Sky.Fog
    in 51..57 -> Sky.Drizzle
    in 61..67, in 80..82 -> Sky.Rain
    in 71..77, 85, 86 -> Sky.Snow
    in 95..99 -> Sky.Storm
    else -> Sky.Cloudy
}

/** Apple Weather style backdrop: the sky's gradient plus the weather itself moving, drawn behind a tile's content. */
@Composable
fun Modifier.weatherSky(code: Int?, day: Boolean): Modifier {
    if (code == null) return this
    // seconds since start; only the draw phase reads it, so nothing recomposes per frame
    val t = rememberInfiniteTransition(label = "sky").animateFloat(
        0f, 3600f, infiniteRepeatable(tween(3_600_000, easing = LinearEasing)), label = "t",
    )
    val kind = sky(code)
    return drawBehind { drawSky(kind, day, t.value) }
}

private fun gradient(kind: Sky, day: Boolean): List<Color> = when {
    kind == Sky.Storm -> listOf(Color(0xFF1B2230), Color(0xFF3A4252))
    kind == Sky.Rain || kind == Sky.Drizzle -> if (day) listOf(Color(0xFF39485C), Color(0xFF5E6E82)) else listOf(Color(0xFF151C27), Color(0xFF2C3645))
    kind == Sky.Snow -> if (day) listOf(Color(0xFF5B7290), Color(0xFF9AAEC4)) else listOf(Color(0xFF1C2638), Color(0xFF3B4A62))
    kind == Sky.Cloudy || kind == Sky.Fog -> if (day) listOf(Color(0xFF4F6278), Color(0xFF7D8EA2)) else listOf(Color(0xFF181F2B), Color(0xFF343E4E))
    else -> if (day) listOf(Color(0xFF1F6FCB), Color(0xFF5BA4E8)) else listOf(Color(0xFF070D1F), Color(0xFF1C2B4E))
}

// fixed pseudo-random positions, so stars, drops and flakes stay put between frames
private fun rnd(i: Int, salt: Int): Float {
    val x = sin(i * 12.9898f + salt * 78.233f) * 43758.547f
    return x - kotlin.math.floor(x)
}

private fun DrawScope.drawSky(kind: Sky, day: Boolean, t: Float) {
    drawRect(Brush.verticalGradient(gradient(kind, day)))
    val w = size.width
    val h = size.height
    when (kind) {
        Sky.Clear, Sky.Partly -> {
            if (day) sun(Offset(w * 0.82f, h * 0.18f), h * 0.22f, t) else stars(t, if (kind == Sky.Clear) 40 else 22)
            if (kind == Sky.Partly) clouds(t, 2, 0.30f)
        }
        Sky.Cloudy -> clouds(t, 4, 0.22f)
        Sky.Fog -> fog(t)
        Sky.Drizzle -> { clouds(t, 3, 0.18f); rain(t, 18, 0.35f) }
        Sky.Rain -> { clouds(t, 3, 0.16f); rain(t, 44, 0.55f) }
        Sky.Snow -> { clouds(t, 2, 0.18f); snow(t) }
        Sky.Storm -> {
            clouds(t, 4, 0.14f)
            rain(t, 50, 0.6f)
            // a double flash every 7 seconds, like the lightning in Apple Weather
            val p = t % 7f
            val flash = if (p < 0.12f || p in 0.25f..0.35f) 0.35f else 0f
            if (flash > 0f) drawRect(Color.White.copy(alpha = flash))
        }
    }
}

private fun DrawScope.sun(c: Offset, r: Float, t: Float) {
    val breathe = 1f + 0.06f * sin(t * 2 * PI.toFloat() / 5f)
    drawCircle(Brush.radialGradient(listOf(Color(0x66FFE9A8), Color.Transparent), c, r * 3.2f * breathe), r * 3.2f * breathe, c)
    drawCircle(Color(0xFFFFE27A), r, c)
    drawCircle(Color(0x33FFFFFF), r * 1.35f * breathe, c)
}

private fun DrawScope.stars(t: Float, n: Int) {
    for (i in 0 until n) {
        val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * (0.8f + rnd(i, 3)) + i))
        drawCircle(Color.White.copy(alpha = twinkle * 0.8f), 1.2f + rnd(i, 4) * 1.6f, Offset(rnd(i, 1) * size.width, rnd(i, 2) * size.height * 0.75f))
    }
}

/** Puffs of overlapping circles drifting left to right, each at its own speed, wrapping round. */
private fun DrawScope.clouds(t: Float, n: Int, alpha: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until n) {
        val s = h * (0.16f + rnd(i, 5) * 0.1f)
        val span = w + s * 6
        val x = ((rnd(i, 6) * span + t * (8f + rnd(i, 7) * 10f)) % span) - s * 3
        val y = h * (0.15f + rnd(i, 8) * 0.45f)
        val c = Color.White.copy(alpha = alpha)
        drawCircle(c, s, Offset(x, y))
        drawCircle(c, s * 0.8f, Offset(x - s * 1.1f, y + s * 0.25f))
        drawCircle(c, s * 0.85f, Offset(x + s * 1.1f, y + s * 0.2f))
        drawCircle(c, s * 0.7f, Offset(x + s * 0.4f, y - s * 0.55f))
    }
}

private fun DrawScope.rain(t: Float, n: Int, alpha: Float) {
    val h = size.height
    val len = h * 0.09f
    for (i in 0 until n) {
        val speed = h * (1.1f + rnd(i, 9) * 0.6f)
        val y = ((rnd(i, 10) * h + t * speed) % (h + len)) - len
        val x = rnd(i, 11) * size.width
        // slanted a little, as rain falls in wind
        drawLine(Color(0xFFB9D6F2).copy(alpha = alpha), Offset(x, y), Offset(x - len * 0.25f, y + len), 1.6f, StrokeCap.Round)
    }
}

private fun DrawScope.snow(t: Float) {
    val h = size.height
    for (i in 0 until 36) {
        val r = 1.5f + rnd(i, 12) * 2.5f
        val y = ((rnd(i, 13) * h + t * h * (0.12f + rnd(i, 14) * 0.1f)) % (h + 2 * r)) - r
        val x = rnd(i, 15) * size.width + sin(t * (0.6f + rnd(i, 16)) + i) * 8f
        drawCircle(Color.White.copy(alpha = 0.85f), r, Offset(x, y))
    }
}

private fun DrawScope.fog(t: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until 4) {
        val y = h * (0.3f + i * 0.17f)
        val dx = sin(t / (6f + i * 2f) + i) * w * 0.15f
        drawLine(Color.White.copy(alpha = 0.16f), Offset(-w * 0.2f + dx, y), Offset(w * 1.2f + dx, y), h * 0.09f, StrokeCap.Round)
    }
}
