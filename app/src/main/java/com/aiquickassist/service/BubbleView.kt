package com.aiquickassist.service

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.aiquickassist.data.BubbleConfig
import com.aiquickassist.data.BubbleStyle

/** Burbuja flotante: toque, presión larga y arrastre. Sigue siendo táctil aunque sea casi invisible. */
class BubbleView(
    context: Context,
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onMoved: (Int, Int) -> Unit,
    /** Centro de la burbuja mientras se arrastra (para el objetivo ✕). */
    private val onDrag: (Float, Float) -> Unit = { _, _ -> },
    /** Al soltar tras arrastrar: devuelve true si se cerró (soltada sobre la ✕). */
    private val onRelease: (Float, Float) -> Boolean = { _, _ -> false }
) : View(context) {
    var config: BubbleConfig? = null
        set(v) { field = v; invalidate() }
    var status = BubbleStatus.IDLE
        set(v) { field = v; invalidate() }
    /** Multiplicador temporal de opacidad (modo discreto). */
    var fade = 1f
        set(v) { field = v; invalidate() }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var startX = 0; private var startY = 0
    private var moved = false; private var longFired = false
    private val longRunnable = Runnable { longFired = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onLongPress() }

    override fun onDraw(canvas: Canvas) {
        val c = config ?: return
        BubbleRenderer.draw(canvas, width.toFloat(), height.toFloat(), c.style, c.color, c.opacity * fade, status, resources.displayMetrics.density)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                moved = false; longFired = false
                postDelayed(longRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX; val dy = e.rawY - downY
                if (!moved && (dx * dx + dy * dy) > slop * slop) { moved = true; removeCallbacks(longRunnable) }
                if (moved && !longFired) {
                    lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                    runCatching { wm.updateViewLayout(this, lp) }
                    onDrag(lp.x + width / 2f, lp.y + height / 2f)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRunnable)
                if (moved) { if (!onRelease(lp.x + width / 2f, lp.y + height / 2f)) onMoved(lp.x, lp.y) } else if (!longFired) onTap()
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longRunnable)
        }
        return true
    }
}
