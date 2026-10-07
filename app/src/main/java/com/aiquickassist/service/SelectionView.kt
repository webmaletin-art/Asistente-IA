package com.aiquickassist.service

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.view.Gravity
import android.widget.LinearLayout

/** Captura congelada con un rectángulo ajustable (esquinas arrastrables) y Cancelar / Analizar. */
class SelectionView(
    context: Context,
    private val shot: Bitmap,
    onCancel: () -> Unit,
    onAnalyze: (Bitmap) -> Unit
) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private val rect = RectF()
    private var drag = 0   // 0 nada, 1 mover, 2.. esquinas/bordes
    private var lastX = 0f; private var lastY = 0f
    private var edges = 0  // bits: 1=L 2=T 4=R 8=B
    private val dim = Paint().apply { color = 0x88000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1 * d }
    private val canvasView = object : View(context) {
        override fun onDraw(c: Canvas) {
            c.drawBitmap(shot, null, Rect(0, 0, width, height), null)
            c.drawRect(0f, 0f, width.toFloat(), rect.top, dim)
            c.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), dim)
            c.drawRect(0f, rect.top, rect.left, rect.bottom, dim)
            c.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, dim)
            c.drawRect(rect, border)
            for ((x, y) in listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom)) {
                c.drawCircle(x, y, 9 * d, handle); c.drawCircle(x, y, 9 * d, handleLine)
            }
        }
    }

    init {
        addView(canvasView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16 * d.toInt(), 8 * d.toInt(), 16 * d.toInt(), 8 * d.toInt()); setBackgroundColor(Color.WHITE) }
        fun btn(t: String, a: () -> Unit) = Button(context).apply {
            text = t; isAllCaps = false; setTextColor(Color.BLACK)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); setStroke(d.toInt().coerceAtLeast(1), 0xFF999999.toInt()); cornerRadius = 6 * d }
            setOnClickListener { a() }
            bar.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8 * d.toInt(), 0, 8 * d.toInt(), 0) })
        }
        btn("Cancelar") { onCancel() }
        btn("Analizar") { onAnalyze(crop()) }
        addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (rect.isEmpty) rect.set(w * 0.1f, h * 0.3f, w * 0.9f, h * 0.55f)
    }

    private fun crop(): Bitmap {
        val sx = shot.width / canvasView.width.toFloat(); val sy = shot.height / canvasView.height.toFloat()
        val l = (rect.left * sx).toInt().coerceIn(0, shot.width - 2)
        val t = (rect.top * sy).toInt().coerceIn(0, shot.height - 2)
        val r = (rect.right * sx).toInt().coerceIn(l + 1, shot.width)
        val b = (rect.bottom * sy).toInt().coerceIn(t + 1, shot.height)
        return Bitmap.createBitmap(shot, l, t, r - l, b - t)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val touch = 28 * d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; edges = 0
                if (kotlin.math.abs(e.x - rect.left) < touch) edges = edges or 1
                if (kotlin.math.abs(e.x - rect.right) < touch) edges = edges or 4
                if (kotlin.math.abs(e.y - rect.top) < touch) edges = edges or 2
                if (kotlin.math.abs(e.y - rect.bottom) < touch) edges = edges or 8
                // Un borde solo cuenta si el toque está dentro del tramo del rectángulo
                if (edges and 3 != 0 && edges and 12 == 0 && (e.y < rect.top - touch || e.y > rect.bottom + touch)) edges = 0
                drag = if (edges != 0) 2 else if (rect.contains(e.x, e.y)) 1 else 0
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX; val dy = e.y - lastY
                val min = 48 * d
                when (drag) {
                    1 -> {
                        val nx = dx.coerceIn(-rect.left, width - rect.right); val ny = dy.coerceIn(-rect.top, height - rect.bottom)
                        rect.offset(nx, ny)
                    }
                    2 -> {
                        if (edges and 1 != 0) rect.left = (rect.left + dx).coerceIn(0f, rect.right - min)
                        if (edges and 4 != 0) rect.right = (rect.right + dx).coerceIn(rect.left + min, width.toFloat())
                        if (edges and 2 != 0) rect.top = (rect.top + dy).coerceIn(0f, rect.bottom - min)
                        if (edges and 8 != 0) rect.bottom = (rect.bottom + dy).coerceIn(rect.top + min, height.toFloat())
                    }
                }
                lastX = e.x; lastY = e.y
                canvasView.invalidate()
            }
        }
        return true
    }
}
