package com.aiquickassist.service

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import com.aiquickassist.data.BubbleStyle

enum class BubbleStatus { IDLE, LOADING, DONE, ERROR }

/** Dibuja la burbuja; lo usan la vista flotante y la vista previa de configuración. */
object BubbleRenderer {
    private const val GREEN = 0xFF2E7D32.toInt()
    private const val RED = 0xFFC62828.toInt()

    fun draw(c: Canvas, w: Float, h: Float, bs: BubbleStyle, color: Int, opacity: Float, status: BubbleStatus, density: Float) {
        val a = (opacity.coerceIn(0.02f, 1f) * 255).toInt()
        val ink = when (status) { BubbleStatus.DONE -> GREEN; BubbleStatus.ERROR -> RED; else -> color }
        val stroke = 2f * density
        val cx = w / 2; val cy = h / 2
        val r = minOf(w, h) / 2 - stroke
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        val filled = bs == BubbleStyle.NORMAL || bs == BubbleStyle.TRANSPARENT
        val onFill = if (ColorUtils.calculateLuminance(ink) > 0.6) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val glyphColor = if (filled) onFill else ink

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ink; this.style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ink; this.style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = glyphColor; this.style = Paint.Style.STROKE; strokeWidth = stroke * 1.2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = glyphColor; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }

        when (bs) {
            BubbleStyle.NORMAL -> { fill.alpha = a; c.drawCircle(cx, cy, r, fill) }
            BubbleStyle.TRANSPARENT -> { fill.alpha = (a * 0.25f).toInt(); c.drawCircle(cx, cy, r, fill) }
            BubbleStyle.OUTLINE -> { line.alpha = a; c.drawCircle(cx, cy, r, line) }
            BubbleStyle.SQUARE -> { line.alpha = a; c.drawRoundRect(box, r * 0.2f, r * 0.2f, line) }
            BubbleStyle.SQUARE_LETTERS -> {}
            else -> {}
        }
        glyph.alpha = a; textP.alpha = a

        fun centerText(t: String, size: Float) {
            textP.textSize = size
            c.drawText(t, cx, cy - (textP.ascent() + textP.descent()) / 2, textP)
        }

        // Estados: …  ✓  !
        if (status != BubbleStatus.IDLE) {
            val s = r * 0.45f
            when (status) {
                BubbleStatus.LOADING -> {
                    val d = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = glyphColor; this.alpha = a }
                    for (i in -1..1) c.drawCircle(cx + i * s * 0.7f, cy, stroke * 0.9f, d)
                }
                BubbleStatus.DONE -> c.drawPath(Path().apply { moveTo(cx - s, cy); lineTo(cx - s * 0.25f, cy + s * 0.7f); lineTo(cx + s, cy - s * 0.6f) }, glyph)
                else -> centerText("!", r * 1.1f)
            }
            return
        }

        when (bs) {
            BubbleStyle.LETTERS -> centerText("AI", r * 0.95f)
            BubbleStyle.SQUARE_LETTERS -> centerText("[ AI ]", r * 0.7f)
            BubbleStyle.SHIELD -> {
                val s = r * 0.55f
                val p = Path().apply {
                    moveTo(cx, cy - s); lineTo(cx + s * 0.8f, cy - s * 0.6f); lineTo(cx + s * 0.8f, cy + s * 0.1f)
                    quadTo(cx + s * 0.8f, cy + s * 0.7f, cx, cy + s); quadTo(cx - s * 0.8f, cy + s * 0.7f, cx - s * 0.8f, cy + s * 0.1f)
                    lineTo(cx - s * 0.8f, cy - s * 0.6f); close()
                }
                line.strokeWidth = stroke * 0.6f; line.alpha = (a * 0.7f).toInt(); c.drawPath(p, line)
            }
            BubbleStyle.OUTLINE, BubbleStyle.SQUARE -> centerText("AI", r * 0.7f)
            else -> { // lupa simple
                val s = r * 0.42f
                c.drawCircle(cx - s * 0.2f, cy - s * 0.2f, s * 0.7f, glyph)
                c.drawLine(cx + s * 0.3f, cy + s * 0.3f, cx + s * 0.95f, cy + s * 0.95f, glyph)
            }
        }
    }
}
