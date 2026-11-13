package org.flowseal.tgwsproxy

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.PowerManager
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Animated "Vice-coast sunset" backdrop, drawn entirely in code (no bitmaps):
 * gradient sky, striped sun on the horizon, a city skyline and palm trees scrolling
 * sideways at different speeds (parallax) behind a road seen from the side, with
 * moving dashes. The view is a side-scroller: everything drifts right-to-left.
 *
 * Stops completely while hidden / detached / system animations are off / battery
 * saver is on (then one static frame is drawn).
 */
class AnimatedBackgroundView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val dp = resources.displayMetrics.density
    private val cPink = ctx.getColor(R.color.neon_cyan)      // hot pink accent
    private val cOrange = ctx.getColor(R.color.neon_violet)  // orange accent
    private val cSil = 0xFF0B0314.toInt()                    // palm silhouette

    private val sky = Paint()
    private val ground = Paint()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sun = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val city = Paint(Paint.ANTI_ALIAS_FLAG)
    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF160824.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cPink; style = Paint.Style.STROKE; strokeWidth = 1.6f * dp
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFE9D6.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT
    }
    private val trunk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cSil; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val frond = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cSil; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    private var bx = FloatArray(0)
    private var bw = FloatArray(0)
    private var bh = FloatArray(0)

    private var phase = 0f          // 0..1, loops every LOOP_MS
    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val hz = h * HORIZON
        sky.shader = LinearGradient(
            0f, 0f, 0f, hz,
            intArrayOf(0xFF12051C.toInt(), 0xFF3A1260.toInt(), 0xFF9B2F86.toInt(), 0xFFFF7A3D.toInt()),
            floatArrayOf(0f, 0.45f, 0.86f, 1f), Shader.TileMode.CLAMP
        )
        ground.shader = LinearGradient(
            0f, hz, 0f, h.toFloat(),
            intArrayOf(0xFF3A0F4D.toInt(), 0xFF1A0828.toInt(), 0xFF0B0314.toInt()),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
        )
        val r = w * 0.26f
        sun.shader = LinearGradient(0f, hz - r * 1.15f, 0f, hz + r * 0.85f, cOrange, cPink, Shader.TileMode.CLAMP)
        glow.shader = RadialGradient(
            w / 2f, hz, w * 0.75f, 0x66FF7A3D, 0x00FF7A3D, Shader.TileMode.CLAMP
        )
        // one deterministic skyline tile; it is repeated sideways
        val rnd = java.util.Random(7)
        val n = 9
        bx = FloatArray(n); bw = FloatArray(n); bh = FloatArray(n)
        val tile = w * SKY_TILE
        var x = 0f
        for (i in 0 until n) {
            bx[i] = x
            bw[i] = if (i == n - 1) tile - x - tile * 0.01f else tile / n * (0.8f + rnd.nextFloat() * 0.3f)
            bh[i] = h * (0.025f + rnd.nextFloat() * 0.075f)
            x += bw[i] + tile * 0.01f
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = phase * (2f * PI.toFloat())
        val hz = h * HORIZON
        val cx = w / 2f

        // sky + horizon glow
        c.drawRect(0f, 0f, w, hz, sky)
        c.drawRect(0f, 0f, w, hz, glow)

        // striped sun sitting on the horizon
        val r = w * (0.26f + 0.006f * sin(t * 2f))
        c.save()
        c.clipRect(0f, 0f, w, hz)
        val layer = c.saveLayer(cx - r, hz - r * 1.15f, cx + r, hz + r, null)
        sun.alpha = 235
        c.drawCircle(cx, hz - r * 0.15f, r, sun)
        var y = hz - r * 0.55f
        var th = r * 0.03f
        while (y < hz) {
            c.drawRect(cx - r, y, cx + r, y + th, cut)
            y += th * 3.4f
            th *= 1.25f
        }
        c.restoreToCount(layer)
        c.restore()

        // skyline scrolling slowly sideways (seamless: one tile per loop)
        city.color = 0xFF1B0836.toInt()
        val tile = w * SKY_TILE
        var k = -1
        while (k * tile < w + tile) {
            val off = k * tile - phase * tile
            for (i in bx.indices) c.drawRect(off + bx[i], hz - bh[i], off + bx[i] + bw[i], hz, city)
            k++
        }

        // ground
        c.drawRect(0f, hz, w, h, ground)

        // far palms (slow) standing just below the horizon
        val farGap = w * 0.30f
        var i = -1
        while (i * farGap < w + farGap) {
            val px = i * farGap - phase * farGap
            palm(c, px, h * 0.655f, h * 0.12f, if (i % 2 == 0) 0.3f else -0.25f, sin(t + i), 0.85f)
            i++
        }
        // near palms (fast), behind the road
        val nearGap = w * 0.5f
        val shift = phase * 2f * nearGap
        i = -1
        while (i * nearGap < w + nearGap) {
            val px = i * nearGap - shift
            palm(c, px, h * 0.705f, h * 0.27f, if (i % 2 == 0) 0.4f else -0.35f, sin(t * 2f + i), 1f)
            i++
        }

        // road seen from the side: a horizontal band with neon edges and moving dashes
        val rt = h * 0.705f
        val rb = h * 0.80f
        c.drawRect(0f, rt, w, rb, road)
        c.drawLine(0f, rt, w, rt, edge)
        c.drawLine(0f, rb, w, rb, edge)
        val gap = w * 0.25f
        val dashLen = gap * 0.48f
        val ym = (rt + rb) / 2f
        dash.strokeWidth = 3f * dp
        dash.alpha = 200
        var d = -1
        while (d * gap < w + gap) {
            val dx = d * gap - phase * 4f * gap
            c.drawLine(dx, ym, dx + dashLen, ym, dash)
            d++
        }

        if (animator?.isRunning == true) postInvalidateOnAnimation()
    }

    /** One silhouette palm. lean>0 bends to the right; sway is a small frond wobble. */
    private fun palm(c: Canvas, x: Float, base: Float, hgt: Float, lean: Float, sway: Float, alpha: Float) {
        if (hgt < 2f * dp || alpha <= 0f) return
        val ai = (alpha * 255f).toInt()
        trunk.alpha = ai; frond.alpha = ai
        val tx = x + lean * hgt * 0.28f
        val ty = base - hgt
        trunk.strokeWidth = (hgt * 0.045f).coerceAtLeast(1f)
        path.reset()
        path.moveTo(x, base)
        path.quadTo(x - lean * hgt * 0.04f, base - hgt * 0.55f, tx, ty)
        c.drawPath(path, trunk)

        frond.strokeWidth = (hgt * 0.028f).coerceAtLeast(1f)
        for (k in ANGLES.indices) {
            val a = Math.toRadians(ANGLES[k].toDouble()).toFloat() + sway * 0.06f
            val len = hgt * LENS[k]
            val dx = cos(a); val dy = sin(a)
            for (pass in 0..1) {
                val off = pass * len * 0.10f
                path.reset()
                path.moveTo(tx, ty)
                path.quadTo(tx + dx * len * 0.55f, ty + dy * len * 0.55f - len * 0.28f + off * 0.3f,
                    tx + dx * len, ty + dy * len + len * 0.30f + off)
                c.drawPath(path, frond)
            }
        }
    }

    // ---- lifecycle: only animate while actually visible -------------------------------

    private fun shouldAnimate(): Boolean {
        if (!isAttachedToWindow || windowVisibility != VISIBLE || visibility != VISIBLE) return false
        val scale = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        if (scale == 0f) return false
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isPowerSaveMode == true) return false
        return true
    }

    private fun update() {
        if (shouldAnimate()) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = LOOP_MS
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { phase = it.animatedValue as Float }
                }
            }
            if (animator?.isRunning != true) animator?.start()
            postInvalidateOnAnimation()
        } else {
            animator?.cancel()
            animator = null
            invalidate()   // one static frame
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); update() }
    override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); update() }
    override fun onVisibilityChanged(changed: View, v: Int) { super.onVisibilityChanged(changed, v); update() }

    private companion object {
        const val LOOP_MS = 16000L
        const val SKY_TILE = 0.30f
        const val HORIZON = 0.60f
        // frond directions in degrees (screen coords: 270 = straight up) and relative lengths
        val ANGLES = floatArrayOf(195f, 222f, 250f, 270f, 290f, 318f, 345f)
        val LENS = floatArrayOf(0.30f, 0.34f, 0.32f, 0.26f, 0.32f, 0.34f, 0.30f)
    }
}
