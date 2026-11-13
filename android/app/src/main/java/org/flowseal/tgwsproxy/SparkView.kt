package org.flowseal.tgwsproxy

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** Tiny neon sparkline of recent download speed. Cheap: one path, redrawn once a second. */
class SparkView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    private val size = 60
    private val values = FloatArray(size)
    private var count = 0

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glow = Paint(line).apply { strokeWidth = line.strokeWidth * 3.2f; alpha = 38 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val area = Path()

    private val cStart = 0xFFFF4FA3.toInt()
    private val cEnd = 0xFFFF9A3D.toInt()

    fun push(v: Float) {
        System.arraycopy(values, 1, values, 0, size - 1)
        values[size - 1] = v
        if (count < size) count++
        invalidate()
    }

    fun clear() {
        values.fill(0f)
        count = 0
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val sh = LinearGradient(0f, 0f, w.toFloat(), 0f, cStart, cEnd, Shader.TileMode.CLAMP)
        line.shader = sh
        glow.shader = sh
        fill.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(), 0x33FF4FA3, 0x00FF4FA3, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = line.strokeWidth
        val max = maxOf(values.max(), 64f * 1024f) // keep a sane floor so idle isn't noisy
        val step = w / (size - 1)

        path.reset()
        area.reset()
        for (i in 0 until size) {
            val x = i * step
            val y = h - pad - (values[i] / max) * (h - 2 * pad)
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, h)
                area.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(w, h)
        area.close()
        c.drawPath(area, fill)
        c.drawPath(path, glow)
        c.drawPath(path, line)
    }
}
