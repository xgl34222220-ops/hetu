package io.github.xgl34222220.hetu.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Region flags drawn on a canvas.
 *
 * The concept shows a flag next to every node and exit address. Flag emoji are not a safe way
 * to get there: several ROMs ship fonts without some regional-indicator pairs, so the same
 * node list renders differently from phone to phone. These are small simplified drawings that
 * look the same everywhere; a region without a drawing falls back to its two-letter code.
 */

private typealias FlagPainter = DrawScope.() -> Unit

private val White = Color(0xFFFFFFFF)
private val Ink = Color(0xFF14161C)

/**
 * Flag for an ISO 3166 alpha-2 [code] (case-insensitive). Draws nothing for a blank code.
 * @param height flag height; the width is 1.42 × height (the concept's 23 × 16 dp proportion).
 */
@Composable
internal fun HomeFlag(code: String, modifier: Modifier = Modifier, height: Dp = 17.dp) {
    val key = code.trim().uppercase(Locale.ROOT)
    if (key.isEmpty()) return
    val c = LocalHomeColors.current
    val shape = RoundedCornerShape(height * .24f)
    val painter = FlagPainters[key]
    if (painter == null) {
        Box(
            modifier.height(height).widthIn(min = height * 1.42f).background(c.sunken, shape).border(1.dp, c.line2, shape).padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center,
        ) { Text(key.take(3), color = c.t2, style = HomeType.regionCode, maxLines = 1) }
        return
    }
    Canvas(modifier.size(height * 1.42f, height).clip(shape).border(.5.dp, c.line2, shape)) { painter() }
}

/** True when [code] has a drawing (callers may want a different fallback than the code chip). */
internal fun homeFlagAvailable(code: String): Boolean = FlagPainters.containsKey(code.trim().uppercase(Locale.ROOT))

/* ------------------------------------------------------------------ */
/*  Drawing helpers                                                     */
/* ------------------------------------------------------------------ */

private fun DrawScope.horizontal(colors: List<Color>) {
    val band = size.height / colors.size
    colors.forEachIndexed { index, color -> drawRect(color, Offset(0f, index * band), Size(size.width, band + 1f)) }
}

private fun DrawScope.vertical(colors: List<Color>) {
    val band = size.width / colors.size
    colors.forEachIndexed { index, color -> drawRect(color, Offset(index * band, 0f), Size(band + 1f, size.height)) }
}

private fun DrawScope.star(centre: Offset, outer: Float, color: Color, points: Int = 5) {
    val inner = outer * .42f
    val path = Path()
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) outer else inner
        val angle = -PI / 2 + i * PI / points
        val x = centre.x + (r * cos(angle)).toFloat()
        val y = centre.y + (r * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

/** A filled disc with a second, offset disc cut out of it in the field colour. */
private fun DrawScope.crescent(centre: Offset, radius: Float, color: Color, field: Color, shift: Float) {
    drawCircle(color, radius, centre)
    drawCircle(field, radius * .8f, Offset(centre.x + shift, centre.y))
}

/** Scandinavian cross: the upright sits left of centre. */
private fun DrawScope.nordic(field: Color, cross: Color, inner: Color? = null) {
    val h = size.height
    drawRect(field)
    val bar = h * .28f
    val x = size.width * .36f
    drawRect(cross, Offset(x - bar / 2f, 0f), Size(bar, h))
    drawRect(cross, Offset(0f, h / 2f - bar / 2f), Size(size.width, bar))
    if (inner != null) {
        val thin = bar * .5f
        drawRect(inner, Offset(x - thin / 2f, 0f), Size(thin, h))
        drawRect(inner, Offset(0f, h / 2f - thin / 2f), Size(size.width, thin))
    }
}

/** Union Jack inside the rectangle [origin]..[origin] + [area]. */
private fun DrawScope.unionJack(origin: Offset, area: Size) {
    val blue = Color(0xFF012169)
    val red = Color(0xFFC8102E)
    val w = area.width
    val h = area.height
    val right = origin.x + w
    val bottom = origin.y + h
    drawRect(blue, origin, area)
    drawLine(White, origin, Offset(right, bottom), strokeWidth = h * .20f)
    drawLine(White, Offset(right, origin.y), Offset(origin.x, bottom), strokeWidth = h * .20f)
    drawLine(red, origin, Offset(right, bottom), strokeWidth = h * .07f)
    drawLine(red, Offset(right, origin.y), Offset(origin.x, bottom), strokeWidth = h * .07f)
    drawRect(White, Offset(origin.x + w / 2f - h * .17f, origin.y), Size(h * .34f, h))
    drawRect(White, Offset(origin.x, origin.y + h / 2f - h * .17f), Size(w, h * .34f))
    drawRect(red, Offset(origin.x + w / 2f - h * .10f, origin.y), Size(h * .20f, h))
    drawRect(red, Offset(origin.x, origin.y + h / 2f - h * .10f), Size(w, h * .20f))
}

/* ------------------------------------------------------------------ */
/*  Flags                                                               */
/* ------------------------------------------------------------------ */

private val FlagPainters: Map<String, FlagPainter> = mapOf(
    "HK" to {
        drawRect(Color(0xFFDE2910))
        val centre = Offset(size.width / 2f, size.height / 2f)
        val h = size.height
        for (i in 0 until 5) rotate(i * 72f, centre) {
            drawOval(White, Offset(centre.x - h * .065f, centre.y - h * .34f), Size(h * .13f, h * .27f))
        }
    },
    "TW" to {
        drawRect(Color(0xFFF20000))
        drawRect(Color(0xFF000095), Offset.Zero, Size(size.width / 2f, size.height / 2f))
        drawCircle(White, size.height * .13f, Offset(size.width / 4f, size.height / 4f))
    },
    "JP" to {
        drawRect(White)
        drawCircle(Color(0xFFBC002D), size.height * .30f, Offset(size.width / 2f, size.height / 2f))
    },
    "SG" to {
        val red = Color(0xFFEF3340)
        horizontal(listOf(red, White))
        crescent(Offset(size.width * .24f, size.height * .25f), size.height * .15f, White, red, size.height * .07f)
        drawCircle(White, size.height * .045f, Offset(size.width * .40f, size.height * .25f))
    },
    "KR" to {
        drawRect(White)
        val r = size.height * .25f
        val centre = Offset(size.width / 2f, size.height / 2f)
        val box = Size(r * 2f, r * 2f)
        val corner = Offset(centre.x - r, centre.y - r)
        drawArc(Color(0xFFCD2E3A), 180f, 180f, true, corner, box)
        drawArc(Color(0xFF0047A0), 0f, 180f, true, corner, box)
        val bar = size.height * .07f
        val reach = size.width * .07f
        listOf(.2f to .26f, .8f to .26f, .2f to .74f, .8f to .74f).forEachIndexed { index, (fx, fy) ->
            val x = size.width * fx
            val y = size.height * fy
            val rise = if (index == 0 || index == 3) -reach else reach
            drawLine(Ink, Offset(x - reach, y - rise), Offset(x + reach, y + rise), strokeWidth = bar)
        }
    },
    "US" to {
        val red = Color(0xFFB22234)
        horizontal(listOf(red, White, red, White, red, White, red))
        drawRect(Color(0xFF3C3B6E), Offset.Zero, Size(size.width * .44f, size.height * 4f / 7f))
        for (row in 0 until 2) for (col in 0 until 3) {
            drawCircle(White, size.height * .032f, Offset(size.width * (.09f + col * .13f), size.height * (.17f + row * .23f)))
        }
    },
    "GB" to { unionJack(Offset.Zero, size) },
    "UK" to { unionJack(Offset.Zero, size) },
    "AU" to {
        drawRect(Color(0xFF012169))
        unionJack(Offset.Zero, Size(size.width / 2f, size.height / 2f))
        star(Offset(size.width * .25f, size.height * .76f), size.height * .13f, White, points = 7)
        listOf(.76f to .22f, .88f to .44f, .76f to .80f, .66f to .50f).forEach { (fx, fy) ->
            drawCircle(White, size.height * .04f, Offset(size.width * fx, size.height * fy))
        }
    },
    "CA" to {
        val red = Color(0xFFD80621)
        drawRect(White)
        drawRect(red, Offset.Zero, Size(size.width * .25f, size.height))
        drawRect(red, Offset(size.width * .75f, 0f), Size(size.width * .25f, size.height))
        star(Offset(size.width / 2f, size.height * .52f), size.height * .26f, red, points = 6)
    },
    "DE" to { horizontal(listOf(Color(0xFF14161C), Color(0xFFDD0000), Color(0xFFFFCE00))) },
    "FR" to { vertical(listOf(Color(0xFF0055A4), White, Color(0xFFEF4135))) },
    "NL" to { horizontal(listOf(Color(0xFFAE1C28), White, Color(0xFF21468B))) },
    "LU" to { horizontal(listOf(Color(0xFFEF3340), White, Color(0xFF00A3E0))) },
    "RU" to { horizontal(listOf(White, Color(0xFF0039A6), Color(0xFFD52B1E))) },
    "IT" to { vertical(listOf(Color(0xFF009246), White, Color(0xFFCE2B37))) },
    "IE" to { vertical(listOf(Color(0xFF169B62), White, Color(0xFFFF883E))) },
    "BE" to { vertical(listOf(Color(0xFF14161C), Color(0xFFFDDA24), Color(0xFFEF3340))) },
    "ES" to {
        drawRect(Color(0xFFAA151B))
        drawRect(Color(0xFFF1BF00), Offset(0f, size.height * .25f), Size(size.width, size.height * .5f))
    },
    "AT" to { horizontal(listOf(Color(0xFFED2939), White, Color(0xFFED2939))) },
    "HU" to { horizontal(listOf(Color(0xFFCE2939), White, Color(0xFF477050))) },
    "PL" to { horizontal(listOf(White, Color(0xFFDC143C))) },
    "UA" to { horizontal(listOf(Color(0xFF0057B7), Color(0xFFFFD700))) },
    "ID" to { horizontal(listOf(Color(0xFFCE1126), White)) },
    "TH" to {
        val red = Color(0xFFA51931)
        horizontal(listOf(red, White, Color(0xFF2D2A4A), Color(0xFF2D2A4A), White, red))
    },
    "VN" to {
        drawRect(Color(0xFFDA251D))
        star(Offset(size.width / 2f, size.height * .52f), size.height * .27f, Color(0xFFFFE600))
    },
    "CN" to {
        val gold = Color(0xFFFFDE00)
        drawRect(Color(0xFFDE2910))
        star(Offset(size.width * .22f, size.height * .32f), size.height * .19f, gold)
        listOf(.40f to .14f, .47f to .28f, .47f to .46f, .40f to .60f).forEach { (fx, fy) ->
            drawCircle(gold, size.height * .04f, Offset(size.width * fx, size.height * fy))
        }
    },
    "TR" to {
        val red = Color(0xFFE30A17)
        drawRect(red)
        crescent(Offset(size.width * .38f, size.height / 2f), size.height * .26f, White, red, size.height * .08f)
        star(Offset(size.width * .60f, size.height / 2f), size.height * .11f, White)
    },
    "IN" to {
        horizontal(listOf(Color(0xFFFF9933), White, Color(0xFF138808)))
        drawCircle(Color(0xFF000080), size.height * .12f, Offset(size.width / 2f, size.height / 2f), style = Stroke(size.height * .04f))
    },
    "CH" to {
        drawRect(Color(0xFFD52B1E))
        val h = size.height
        val centre = Offset(size.width / 2f, h / 2f)
        drawRect(White, Offset(centre.x - h * .09f, centre.y - h * .29f), Size(h * .18f, h * .58f))
        drawRect(White, Offset(centre.x - h * .29f, centre.y - h * .09f), Size(h * .58f, h * .18f))
    },
    "SE" to { nordic(Color(0xFF006AA7), Color(0xFFFECC02)) },
    "DK" to { nordic(Color(0xFFC8102E), White) },
    "FI" to { nordic(White, Color(0xFF003580)) },
    "NO" to { nordic(Color(0xFFBA0C2F), White, Color(0xFF00205B)) },
    "BR" to {
        drawRect(Color(0xFF009C3B))
        val w = size.width
        val h = size.height
        val diamond = Path().apply {
            moveTo(w / 2f, h * .12f); lineTo(w * .90f, h / 2f); lineTo(w / 2f, h * .88f); lineTo(w * .10f, h / 2f); close()
        }
        drawPath(diamond, Color(0xFFFFDF00))
        drawCircle(Color(0xFF002776), h * .20f, Offset(w / 2f, h / 2f))
    },
    "AR" to {
        val sky = Color(0xFF74ACDF)
        horizontal(listOf(sky, White, sky))
        drawCircle(Color(0xFFF6B40E), size.height * .09f, Offset(size.width / 2f, size.height / 2f))
    },
    "AE" to {
        horizontal(listOf(Color(0xFF00732F), White, Color(0xFF14161C)))
        drawRect(Color(0xFFFF0000), Offset.Zero, Size(size.width * .26f, size.height))
    },
)
