package org.flowseal.tgwsproxy.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.flowseal.tgwsproxy.R
import org.flowseal.tgwsproxy.RunState

enum class AppTheme(val key: String) { GLASS("glass"), GTA("gta"), AMOLED("amoled"), LIGHT("light");
    companion object { fun of(k: String?) = values().firstOrNull { it.key == k } ?: GLASS }
}

class Palette(
    val bg: Color, val a: Color, val b: Color, val c: Color, val d: Color,
    val text: Color, val navFill: Color, val onAccent: Color,
    val dark: Boolean = true, val blob: Float = 1f,
)

private val glassPalette = Palette(
    bg = Color(0xFF05060F), a = Color(0xFF3DE8FF), b = Color(0xFF8B6CFF), c = Color(0xFFFF5CA8),
    d = Color(0xFF55F2B5), text = Color(0xFFF4F6FF), navFill = Color(0xFF0B0E1E), onAccent = Color(0xFF06101C))

// Vice-City sunset: hot pink, orange, neon teal on a deep violet night.
private val gtaPalette = Palette(
    bg = Color(0xFF12052B), a = Color(0xFFFF4FA3), b = Color(0xFFFFA23C), c = Color(0xFFFF3B6B),
    d = Color(0xFF2DE2E6), text = Color(0xFFFFF3FA), navFill = Color(0xFF1A0733), onAccent = Color(0xFF2A0A24))

private val amoledPalette = Palette(
    bg = Color(0xFF000000), a = Color(0xFF3DE8FF), b = Color(0xFF8B6CFF), c = Color(0xFFFF5CA8),
    d = Color(0xFF55F2B5), text = Color(0xFFF4F6FF), navFill = Color(0xFF0A0A0A), onAccent = Color(0xFF06101C),
    blob = 0.35f)

private val lightPalette = Palette(
    bg = Color(0xFFF1F3FB), a = Color(0xFF0A84C6), b = Color(0xFF6A4CFF), c = Color(0xFFE0407A),
    d = Color(0xFF0FA97A), text = Color(0xFF10131F), navFill = Color(0xFFFFFFFF), onAccent = Color(0xFFFFFFFF),
    dark = false, blob = 0.40f)

object Themes {
    var current by mutableStateOf(AppTheme.GLASS)
    val palette: Palette
        get() = when (current) {
            AppTheme.GTA -> gtaPalette
            AppTheme.AMOLED -> amoledPalette
            AppTheme.LIGHT -> lightPalette
            else -> glassPalette
        }
}

/** Colours of the active theme; reading them inside a composable recomposes on theme change. */
object G {
    val bg get() = Themes.palette.bg
    val cyan get() = Themes.palette.a
    val violet get() = Themes.palette.b
    val pink get() = Themes.palette.c
    val mint get() = Themes.palette.d
    val text get() = Themes.palette.text
    val textDim get() = Themes.palette.text.copy(alpha = 0.70f)
    val textFaint get() = Themes.palette.text.copy(alpha = 0.50f)
    /** Colour of glass highlights: white on dark themes, ink on the light one. */
    val tint get() = if (Themes.palette.dark) Color.White else Color(0xFF1B2240)
    val glassTop get() = if (Themes.palette.dark) Color.White.copy(alpha = 0.17f) else Color.White.copy(alpha = 0.75f)
    val glassBottom get() = if (Themes.palette.dark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.35f)
    val danger get() = Themes.palette.c
    val navFill get() = Themes.palette.navFill
    val onAccent get() = Themes.palette.onAccent
}

/** Slowly drifting aurora blobs behind everything; this is what the glass panes refract. */
@Composable
fun GlassBackground(modifier: Modifier = Modifier) {
    if (Themes.current == AppTheme.GTA) { GtaBackground(modifier); return }
    val t = rememberInfiniteTransition(label = "bg")
    val a by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(19000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(23000, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    Canvas(modifier.fillMaxSize().background(G.bg)) {
        val w = size.width
        val h = size.height
        fun blob(color: Color, cx: Float, cy: Float, r: Float) = drawCircle(
            brush = Brush.radialGradient(
                listOf(color.copy(alpha = 0.55f * Themes.palette.blob), color.copy(alpha = 0.18f * Themes.palette.blob), Color.Transparent),
                center = Offset(cx, cy), radius = r),
            radius = r, center = Offset(cx, cy))
        blob(G.violet, w * (0.15f + 0.55f * a), h * (0.12f + 0.12f * b), w * 0.85f)
        blob(G.cyan, w * (0.95f - 0.5f * b), h * (0.38f + 0.1f * c), w * 0.8f)
        blob(G.pink, w * (0.2f + 0.5f * c), h * (0.78f - 0.12f * a), w * 0.9f)
        blob(G.mint, w * (0.85f - 0.4f * a), h * (0.95f - 0.1f * b), w * 0.55f)
    }
}

private fun glassBorder(): Brush = Brush.linearGradient(
    listOf(G.tint.copy(alpha = 0.60f), G.tint.copy(alpha = 0.06f), G.tint.copy(alpha = 0.28f)))

/** A frosted pane: translucent fill, bright rim light, soft specular highlight on the top edge. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 28.dp,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(G.glassTop, G.glassBottom)))
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = if (Themes.palette.dark) 0.10f else 0.35f), Color.Transparent),
                        endY = size.height * 0.38f))
            }
            .border(1.dp, glassBorder(), shape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(bottom = 10.dp),
        color = G.cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
}

@Composable
fun ScreenTitle(text: String) {
    Text(text, color = G.text, fontSize = 30.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 20.dp, bottom = 16.dp))
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = G.textFaint, fontSize = 12.5.sp, lineHeight = 17.sp)
}

/** Pill button. [primary] gets an accent gradient, otherwise a plain glass pill. */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    val fill = if (primary) Brush.horizontalGradient(
        listOf(G.cyan.copy(alpha = 0.92f), G.violet.copy(alpha = 0.92f)))
    else Brush.verticalGradient(listOf(G.tint.copy(alpha = 0.16f), G.tint.copy(alpha = 0.06f)))
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, glassBorder(), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (primary) G.onAccent else G.text,
            fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            modifier = Modifier.then(if (enabled) Modifier else Modifier.background(Color.Transparent)),
        )
    }
}

@Composable
fun GlassSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = G.text, fontSize = 15.5.sp)
            if (subtitle != null) Hint(subtitle, Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = G.cyan.copy(alpha = 0.85f),
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = G.tint.copy(alpha = 0.85f),
                uncheckedTrackColor = G.tint.copy(alpha = 0.14f),
                uncheckedBorderColor = G.tint.copy(alpha = 0.30f),
            ),
        )
    }
}

@Composable
fun GlassField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    number: Boolean = false,
    mono: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = if (error != null) ({ Text(error) }) else null,
        singleLine = singleLine,
        minLines = minLines,
        trailingIcon = trailing,
        visualTransformation = VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        textStyle = androidx.compose.ui.text.TextStyle(
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, fontSize = 15.sp),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = G.text, unfocusedTextColor = G.text,
            errorTextColor = G.text,
            focusedContainerColor = G.tint.copy(alpha = 0.07f),
            unfocusedContainerColor = G.tint.copy(alpha = 0.04f),
            errorContainerColor = G.tint.copy(alpha = 0.04f),
            cursorColor = G.cyan, errorCursorColor = G.pink,
            focusedBorderColor = G.cyan.copy(alpha = 0.9f),
            unfocusedBorderColor = G.tint.copy(alpha = 0.26f),
            errorBorderColor = G.pink,
            focusedLabelColor = G.cyan, unfocusedLabelColor = G.textDim,
            errorLabelColor = G.pink, errorSupportingTextColor = G.pink,
        ),
    )
}

// ---- hero: the power orb --------------------------------------------------------------

@Composable
fun PowerOrb(state: RunState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = when (state) {
        RunState.ON, RunState.STARTING -> G.cyan
        RunState.ERROR -> G.pink
        RunState.OFF -> G.textDim
    }
    val inf = rememberInfiniteTransition(label = "orb")
    val rot by inf.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(if (state == RunState.ON) 7000 else 1800, easing = LinearEasing)),
        label = "rot")
    val pulse by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(
            tween(when (state) { RunState.ON -> 2400; RunState.STARTING -> 1100; else -> 1600 },
                easing = LinearEasing)),
        label = "pulse")
    val live = state != RunState.OFF
    val ringBrush = if (state == RunState.ERROR)
        Brush.sweepGradient(listOf(G.pink, G.violet, G.pink))
    else if (live) Brush.sweepGradient(listOf(G.cyan, G.violet, G.pink, G.cyan))
    else Brush.sweepGradient(listOf(G.tint.copy(0.45f), G.tint.copy(0.10f), G.tint.copy(0.45f)))

    Box(
        modifier.size(230.dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            if (live) {
                val s = 0.62f + 0.38f * pulse
                drawCircle(accent.copy(alpha = (1f - pulse) * 0.38f), radius = r * s)
            }
            rotate(if (live) rot else 0f) {
                drawCircle(
                    brush = ringBrush, radius = r * 0.80f,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Box(
            Modifier.size(150.dp).clip(CircleShape)
                .background(Brush.verticalGradient(listOf(G.tint.copy(0.24f), G.tint.copy(0.05f))))
                .border(1.dp, glassBorder(), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_power), null, tint = accent, modifier = Modifier.size(58.dp))
        }
    }
}

/** Download-speed history as a glowing line. */
@Composable
fun Spark(values: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val mx = maxOf(values.max(), 1f)
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height * (1f - 0.9f * v / mx) - 2f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val brush = Brush.horizontalGradient(listOf(G.cyan, G.violet, G.pink))
        drawPath(path, brush, alpha = 0.25f,
            style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, brush,
            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ---- bottom navigation ------------------------------------------------------------------

class NavItem(val label: String, val icon: Int)

/** Floating glass dock. [position] is the (fractional) page index, so the highlight slides with the pager. */
@Composable
fun GlassNav(items: List<NavItem>, position: Float, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(32.dp)
    BoxWithConstraints(
        modifier
            .clip(shape)
            .background(G.navFill.copy(alpha = 0.94f))
            .background(Brush.verticalGradient(listOf(G.tint.copy(0.14f), G.tint.copy(0.04f))))
            .border(1.dp, glassBorder(), shape)
            .padding(6.dp),
    ) {
        val itemW = maxWidth / items.size
        val itemPx = with(androidx.compose.ui.platform.LocalDensity.current) { itemW.toPx() }
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .offset { IntOffset((position * itemPx).roundToInt(), 0) }
                    .width(itemW)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(26.dp))
                    .background(Brush.horizontalGradient(listOf(G.cyan.copy(alpha = 0.30f), G.violet.copy(alpha = 0.24f)))))
        }
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, item ->
                val close = (1f - abs(position - i)).coerceIn(0f, 1f)
                val tint = lerp(G.textDim, G.cyan, close)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(26.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null) { onSelect(i) }
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        painterResource(item.icon), null, tint = tint,
                        modifier = Modifier.size(24.dp).graphicsLayer {
                            val sc = 1f + 0.2f * close
                            scaleX = sc; scaleY = sc
                            translationY = -3.dp.toPx() * close
                        })
                    Spacer(Modifier.height(2.dp))
                    Text(item.label, color = tint, fontSize = 11.sp,
                        fontWeight = if (close > 0.5f) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
fun GapW(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun GapH(h: Dp) = Spacer(Modifier.height(h))

@Suppress("unused")
private val unusedBorderStroke = BorderStroke(0.dp, Color.Transparent)


/** Vice-City night: stars, striped sun over the sea, neon skyline, palms. Slowly animated. */
@Composable
fun GtaBackground(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "vice")
    val glow by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(4500, easing = LinearEasing), RepeatMode.Reverse), label = "glow")
    val twinkle by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "tw")
    val drift by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(60000, easing = LinearEasing), RepeatMode.Restart), label = "drift")
    val shimmer by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Reverse), label = "sh")

    val rnd = remember { kotlin.random.Random(7) }
    val stars = remember { List(60) { Triple(rnd.nextFloat(), rnd.nextFloat() * 0.34f, rnd.nextFloat()) } }
    // skyline: (x fraction, width fraction, height fraction, layer)
    val towers = remember {
        val r = kotlin.random.Random(11)
        buildList {
            var x = -0.02f
            while (x < 1.02f) {
                val wd = 0.035f + r.nextFloat() * 0.055f
                add(floatArrayOf(x, wd, 0.05f + r.nextFloat() * 0.17f, r.nextFloat()))
                x += wd * (0.75f + r.nextFloat() * 0.35f)
            }
        }
    }

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.60f
        val seaEnd = h * 0.80f

        // sky
        drawRect(Brush.verticalGradient(
            listOf(Color(0xFF0B0224), Color(0xFF2A0A5E), Color(0xFF8E1B8F), Color(0xFFFF3E9A), Color(0xFFFF8A3D), Color(0xFFFFC35A)),
            startY = 0f, endY = horizon))

        // stars
        stars.forEach { (sx, sy, ph) ->
            val a = 0.25f + 0.55f * (0.5f + 0.5f * kotlin.math.sin((twinkle + ph) * 6.28f))
            drawCircle(Color.White.copy(alpha = a * (1f - sy / 0.34f)), radius = (0.8f + ph * 1.4f).dp.toPx(),
                center = Offset(sx * w, sy * h))
        }

        // sun
        val cx = w * 0.5f
        val r = w * 0.31f
        val cy = horizon - r * 0.28f
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFFFFB347).copy(alpha = 0.40f + 0.18f * glow), Color(0xFFFF3E9A).copy(alpha = 0.12f), Color.Transparent),
                center = Offset(cx, cy), radius = r * 2.3f),
            radius = r * 2.3f, center = Offset(cx, cy))
        drawCircle(
            Brush.verticalGradient(
                listOf(Color(0xFFFFF176), Color(0xFFFFA63D), Color(0xFFFF3B8B)),
                startY = cy - r, endY = cy + r),
            radius = r, center = Offset(cx, cy))
        // retro stripes through the lower half of the sun
        var y = cy + r * 0.08f
        var gap = r * 0.03f
        while (y < cy + r) {
            drawRect(Color(0xFFB0197A), topLeft = Offset(cx - r, y),
                size = androidx.compose.ui.geometry.Size(2 * r, gap))
            y += gap * 3.3f
            gap += r * 0.012f
        }

        // drifting neon clouds
        for (i in 0..2) {
            val yy = h * (0.17f + 0.10f * i)
            val xx = ((drift + i * 0.33f) % 1f) * (w * 1.6f) - w * 0.3f
            drawRoundRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color(0xFFFF6EC7).copy(alpha = 0.35f), Color.Transparent),
                    startX = xx, endX = xx + w * 0.7f),
                topLeft = Offset(xx, yy), size = androidx.compose.ui.geometry.Size(w * 0.7f, 5.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
        }

        // skyline (far layer lighter, near layer darker, lit windows)
        for (layer in 0..1) {
            towers.forEach { tw ->
                if ((tw[3] > 0.5f) != (layer == 1)) return@forEach
                val bx = tw[0] * w
                val bw = tw[1] * w
                val bh = tw[2] * h * (if (layer == 0) 1.0f else 0.72f)
                val col = if (layer == 0) Color(0xFF3A0F6E) else Color(0xFF1A0540)
                drawRect(col, topLeft = Offset(bx, horizon - bh), size = androidx.compose.ui.geometry.Size(bw, bh))
                // neon roof edge
                drawRect(Color(0xFFFF4FA3).copy(alpha = if (layer == 0) 0.55f else 0.85f),
                    topLeft = Offset(bx, horizon - bh), size = androidx.compose.ui.geometry.Size(bw, 1.5.dp.toPx()))
                // windows
                val ws = 3.dp.toPx()
                var wy = horizon - bh + 6.dp.toPx()
                var row = 0
                while (wy < horizon - 6.dp.toPx()) {
                    var wx = bx + 4.dp.toPx()
                    var colI = 0
                    while (wx < bx + bw - 5.dp.toPx()) {
                        val on = ((row * 7 + colI * 13 + (tw[0] * 100).toInt()) % 5) < 2
                        if (on) {
                            val c = if ((row + colI) % 3 == 0) Color(0xFF2DE2E6) else Color(0xFFFFB347)
                            drawRect(c.copy(alpha = 0.55f + 0.3f * twinkle), topLeft = Offset(wx, wy),
                                size = androidx.compose.ui.geometry.Size(ws, ws))
                        }
                        wx += ws * 2.2f
                        colI++
                    }
                    wy += ws * 2.4f
                    row++
                }
            }
        }

        // sea
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF7A1B8C), Color(0xFF3A0B63), Color(0xFF1A0540)), startY = horizon, endY = seaEnd),
            topLeft = Offset(0f, horizon), size = androidx.compose.ui.geometry.Size(w, seaEnd - horizon))
        // sun reflection: shimmering bars narrowing downwards
        var ry = horizon + 2.dp.toPx()
        var rh = 3.dp.toPx()
        var i = 0
        while (ry < seaEnd) {
            val widthK = (1f - (ry - horizon) / (seaEnd - horizon)) * (0.55f + 0.1f * shimmer * (if (i % 2 == 0) 1f else -1f))
            val rw = r * 1.7f * widthK
            drawRect(
                Color(0xFFFFB347).copy(alpha = 0.55f * (1f - (ry - horizon) / (seaEnd - horizon))),
                topLeft = Offset(cx - rw / 2f, ry), size = androidx.compose.ui.geometry.Size(rw, rh))
            ry += rh * 2.1f
            rh += 0.6f
            i++
        }
        // neon horizon line
        drawRect(Color(0xFFFF4FA3).copy(alpha = 0.6f + 0.3f * glow), topLeft = Offset(0f, horizon - 1.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(w, 2.dp.toPx()))

        // foreground ground with a faint perspective grid
        drawRect(
            Brush.verticalGradient(listOf(Color(0xFF1A0540), Color(0xFF080118)), startY = seaEnd, endY = h),
            topLeft = Offset(0f, seaEnd), size = androidx.compose.ui.geometry.Size(w, h - seaEnd))
        drawRect(Color(0xFF2DE2E6).copy(alpha = 0.5f + 0.25f * glow), topLeft = Offset(0f, seaEnd),
            size = androidx.compose.ui.geometry.Size(w, 1.5.dp.toPx()))
        val gridC = Color(0xFFFF4FA3).copy(alpha = 0.20f)
        for (k in -9..9) {
            drawLine(gridC, Offset(cx, seaEnd), Offset(cx + k * w * 0.20f, h), strokeWidth = 1.dp.toPx())
        }
        var gy = seaEnd
        var step = 6.dp.toPx()
        while (gy < h) {
            gy += step
            step *= 1.38f
            drawLine(gridC, Offset(0f, gy), Offset(w, gy), strokeWidth = 1.dp.toPx())
        }

        // palms
        val dark = Color(0xFF07010F)
        fun palm(x: Float, base: Float, height: Float, lean: Float, scale: Float) {
            val topX = x + lean
            val topY = base - height
            val trunk = androidx.compose.ui.graphics.Path().apply {
                moveTo(x, base)
                quadraticBezierTo(x + lean * 0.15f, base - height * 0.6f, topX, topY)
            }
            drawPath(trunk, dark, style = Stroke(width = 8.dp.toPx() * scale, cap = StrokeCap.Round))
            val fronds = listOf(-175f, -150f, -125f, -100f, -78f, -55f, -30f, -5f, 20f, 45f)
            fronds.forEach { deg ->
                val rad = Math.toRadians(deg.toDouble())
                val len = height * 0.46f
                val ex = topX + (kotlin.math.cos(rad) * len).toFloat()
                val ey = topY + (kotlin.math.sin(rad) * len).toFloat() * 0.55f + len * 0.42f
                val ctrlX = (topX + ex) / 2f
                val ctrlY = topY - len * 0.38f
                val f = androidx.compose.ui.graphics.Path().apply {
                    moveTo(topX, topY)
                    quadraticBezierTo(ctrlX, ctrlY, ex, ey)
                }
                drawPath(f, dark, style = Stroke(width = 4.dp.toPx() * scale, cap = StrokeCap.Round))
                // leaflets along the frond
                for (q in 1..7) {
                    val tt = q / 8f
                    val px = (1 - tt) * (1 - tt) * topX + 2 * (1 - tt) * tt * ctrlX + tt * tt * ex
                    val py = (1 - tt) * (1 - tt) * topY + 2 * (1 - tt) * tt * ctrlY + tt * tt * ey
                    val ll = len * 0.16f * (1f - tt * 0.5f)
                    drawLine(dark, Offset(px, py), Offset(px + 0f, py + ll), strokeWidth = 2.dp.toPx() * scale, cap = StrokeCap.Round)
                    drawLine(dark, Offset(px, py), Offset(px + ll * (if (ex > topX) 0.6f else -0.6f), py + ll * 0.7f),
                        strokeWidth = 2.dp.toPx() * scale, cap = StrokeCap.Round)
                }
            }
        }
        palm(w * 0.07f, h * 1.02f, h * 0.40f, w * 0.07f, 1f)
        palm(w * 0.94f, h * 1.02f, h * 0.34f, -w * 0.06f, 0.9f)
        palm(w * 0.20f, h * 1.02f, h * 0.20f, w * 0.03f, 0.6f)

        // keep the UI readable
        drawRect(Color(0xFF0B0224).copy(alpha = 0.34f))
    }
}

/** Fades the content out behind the floating tab bar so text never shows through it. */
@Composable
fun NavFade(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(150.dp)
        .background(Brush.verticalGradient(listOf(Color.Transparent, G.bg.copy(alpha = 0.92f), G.bg))))
}
