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
import androidx.compose.runtime.remember
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

/** Palette of the "liquid glass" look: deep night background, bright aurora blobs, frosted panes. */
object G {
    val bg = Color(0xFF05060F)
    val cyan = Color(0xFF3DE8FF)
    val violet = Color(0xFF8B6CFF)
    val pink = Color(0xFFFF5CA8)
    val mint = Color(0xFF55F2B5)
    val text = Color(0xFFF4F6FF)
    val textDim = Color(0xB3F4F6FF)
    val textFaint = Color(0x80F4F6FF)
    val glassTop = Color.White.copy(alpha = 0.17f)
    val glassBottom = Color.White.copy(alpha = 0.05f)
    val danger = Color(0xFFFF6B8B)
}

/** Slowly drifting aurora blobs behind everything; this is what the glass panes refract. */
@Composable
fun GlassBackground(modifier: Modifier = Modifier) {
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
                listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(cx, cy), radius = r),
            radius = r, center = Offset(cx, cy))
        blob(G.violet, w * (0.15f + 0.55f * a), h * (0.12f + 0.12f * b), w * 0.85f)
        blob(G.cyan, w * (0.95f - 0.5f * b), h * (0.38f + 0.1f * c), w * 0.8f)
        blob(G.pink, w * (0.2f + 0.5f * c), h * (0.78f - 0.12f * a), w * 0.9f)
        blob(G.mint, w * (0.85f - 0.4f * a), h * (0.95f - 0.1f * b), w * 0.55f)
    }
}

private val glassBorder = Brush.linearGradient(
    listOf(Color.White.copy(alpha = 0.60f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.28f)))

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
                        listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                        endY = size.height * 0.38f))
            }
            .border(1.dp, glassBorder, shape)
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
    else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.06f)))
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, glassBorder, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (primary) Color(0xFF06101C) else G.text,
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
                uncheckedThumbColor = Color.White.copy(alpha = 0.85f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.14f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.30f),
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
            focusedContainerColor = Color.White.copy(alpha = 0.07f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
            errorContainerColor = Color.White.copy(alpha = 0.04f),
            cursorColor = G.cyan, errorCursorColor = G.pink,
            focusedBorderColor = G.cyan.copy(alpha = 0.9f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.26f),
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
    else Brush.sweepGradient(listOf(Color.White.copy(0.45f), Color.White.copy(0.10f), Color.White.copy(0.45f)))

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
                .background(Brush.verticalGradient(listOf(Color.White.copy(0.24f), Color.White.copy(0.05f))))
                .border(1.dp, glassBorder, CircleShape),
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

/** Floating glass dock with the four tabs. */
@Composable
fun GlassNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(0.20f), Color.White.copy(0.08f))))
            .border(1.dp, glassBorder, shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        items.forEachIndexed { i, item ->
            val on = i == selected
            val tint by animateColorAsState(if (on) G.cyan else G.textDim, label = "tint")
            val pill by animateColorAsState(
                if (on) Color.White.copy(alpha = 0.16f) else Color.Transparent, label = "pill")
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(26.dp)).background(pill)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null) { onSelect(i) }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(item.icon), null, tint = tint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(2.dp))
                Text(item.label, color = tint, fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
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
