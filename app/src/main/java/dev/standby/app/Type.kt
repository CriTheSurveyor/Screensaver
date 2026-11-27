package dev.standby.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** One variable font file gives every weight. */
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int) = FontFamily(
    listOf(100, 200, 300, 400, 500, 600, 700, 800, 900).map { w ->
        Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

class FontChoice(val name: String, val family: FontFamily)

val Fonts = listOf(
    FontChoice("Sistema", FontFamily.SansSerif),
    FontChoice("Arrotondato", variable(R.font.nunito)),
    FontChoice("Moderno", variable(R.font.inter)),
    FontChoice("Geometrico", variable(R.font.outfit)),
    FontChoice("Elegante", variable(R.font.playfair)),
    FontChoice("Condensato", variable(R.font.oswald)),
    FontChoice("Monospazio", variable(R.font.jetbrains)),
)

val LocalFont = compositionLocalOf<FontFamily> { FontFamily.SansSerif }

/** The clock's own face and weight; null weight = each clock style keeps its own. */
val ClockWeights = listOf<Pair<String, FontWeight?>>(
    "Auto" to null, "Sottile" to FontWeight.ExtraLight, "Leggero" to FontWeight.Light,
    "Normale" to FontWeight.Medium, "Grassetto" to FontWeight.Bold, "Nero" to FontWeight.Black,
)
val LocalClockFont = compositionLocalOf<FontFamily> { FontFamily.SansSerif }
val LocalClockWeight = compositionLocalOf<FontWeight?> { null }

private val Tight = (-0.03).em

// The whole StandBy type scale. Every text on screen is one of these five, so sizes and weights never drift.
// Display: big numerals. Title: a tile's main value. Body: a line of content. Caption: secondary grey. Label: small caps headers.

@Composable
fun BigText(text: String, size: TextUnit, color: Color, weight: FontWeight, modifier: Modifier = Modifier, family: FontFamily = LocalFont.current) = Text(
    text, modifier,
    color = color, fontSize = size, fontWeight = weight, fontFamily = family,
    letterSpacing = Tight, lineHeight = size, maxLines = 1, softWrap = false,
    style = TextStyle(
        fontFeatureSettings = "tnum",
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    ),
)

@Composable
fun Title(text: String, modifier: Modifier = Modifier, color: Color = Color.White) =
    Line(text, 22.sp, FontWeight.SemiBold, color, modifier)

@Composable
fun Body(text: String, modifier: Modifier = Modifier, color: Color = Color.White) =
    Line(text, 16.sp, FontWeight.Medium, color, modifier)

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = Muted, lines: Int = 1) =
    Line(text, 13.sp, FontWeight.Medium, color, modifier, lines)

@Composable
fun Label(text: String, color: Color = Muted, modifier: Modifier = Modifier) = Text(
    text.uppercase(), modifier,
    color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = LocalFont.current,
    letterSpacing = 0.08.em, maxLines = 1, overflow = TextOverflow.Ellipsis,
)

@Composable
private fun Line(text: String, size: TextUnit, weight: FontWeight, color: Color, modifier: Modifier, lines: Int = 1) = Text(
    text, modifier,
    color = color, fontSize = size, fontWeight = weight, fontFamily = LocalFont.current,
    maxLines = lines, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFeatureSettings = "tnum"),
)

/** A time readout in the clock's chosen face and weight. */
@Composable
fun ClockText(text: String, size: TextUnit, color: Color, weight: FontWeight, modifier: Modifier = Modifier) =
    BigText(text, size, color, LocalClockWeight.current ?: weight, modifier, LocalClockFont.current)

/** The biggest size at which [sample] fits [maxWidth] in the clock's face and weight, never above [cap]: any font fills its space. */
@Composable
fun fitClock(sample: String, maxWidth: Dp, cap: TextUnit, weight: FontWeight): TextUnit {
    val measurer = rememberTextMeasurer()
    val family = LocalClockFont.current
    val w = LocalClockWeight.current ?: weight
    val ref = remember(sample, family, w) {
        measurer.measure(sample, TextStyle(fontSize = 100.sp, fontFamily = family, fontWeight = w, letterSpacing = Tight, fontFeatureSettings = "tnum")).size.width
    }
    val fit = with(LocalDensity.current) { 100f * maxWidth.toPx() / ref.coerceAtLeast(1) }
    return minOf(fit, cap.value).sp
}
