package com.projector.remote.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Touchpad que simula o modo mouse do controle fisico.
 *
 * - 1 dedo arrastando: move o cursor (mostrado na miniatura da tela)
 * - Toque rapido: clique na posicao do cursor
 * - Toque longo: clique longo
 * - 2 dedos arrastando: rolagem (swipe) a partir do cursor
 */
class TouchpadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onTap(x: Int, y: Int)
        fun onLongPress(x: Int, y: Int)
        fun onScroll(x: Int, y: Int, dx: Int, dy: Int)
    }

    var listener: Listener? = null

    var screenWidth = 1920
        private set
    var screenHeight = 1080
        private set

    private var cursorX = screenWidth / 2f
    private var cursorY = screenHeight / 2f

    /** Pixels do projetor por pixel de dedo. */
    var sensitivity = 2.2f

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val handler = Handler(Looper.getMainLooper())

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var multiTouch = false
    private var longPressed = false
    private var scrollStartX = 0f
    private var scrollStartY = 0f
    private var scrollAccumX = 0f
    private var scrollAccumY = 0f

    private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1E24") }
    private val padStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.parseColor("#2D3545")
    }
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#242932") }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3B82F6") }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9CA3AF"); textSize = dp(12f); textAlign = Paint.Align.CENTER
    }

    private val longPressRunnable = Runnable {
        if (!moved && !multiTouch) {
            longPressed = true
            performHapticFeedback(HAPTIC_FEEDBACK_ENUM_LONG_PRESS)
            listener?.onLongPress(cursorX.roundToInt(), cursorY.roundToInt())
        }
    }

    fun setScreenSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        cursorX = cursorX / screenWidth * w
        cursorY = cursorY / screenHeight * h
        screenWidth = w
        screenHeight = h
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                moved = false; multiTouch = false; longPressed = false
                handler.postDelayed(longPressRunnable, longPressTimeout)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                handler.removeCallbacks(longPressRunnable)
                scrollStartX = avgX(event); scrollStartY = avgY(event)
                scrollAccumX = 0f; scrollAccumY = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouch) {
                    if (event.pointerCount >= 2) {
                        scrollAccumX = avgX(event) - scrollStartX
                        scrollAccumY = avgY(event) - scrollStartY
                    }
                } else {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (!moved && hypot(event.x - downX, event.y - downY) > touchSlop) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (moved) {
                        cursorX = (cursorX + dx * sensitivity).coerceIn(0f, screenWidth - 1f)
                        cursorY = (cursorY + dy * sensitivity).coerceIn(0f, screenHeight - 1f)
                        invalidate()
                    }
                    lastX = event.x; lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (multiTouch && event.pointerCount == 2) sendScroll()
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (!multiTouch && !moved && !longPressed) {
                    performClick()
                    listener?.onTap(cursorX.roundToInt(), cursorY.roundToInt())
                }
            }
            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPressRunnable)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun sendScroll() {
        val k = sensitivity * 1.5f
        val dx = (scrollAccumX * k).roundToInt()
        val dy = (scrollAccumY * k).roundToInt()
        if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
            listener?.onScroll(cursorX.roundToInt(), cursorY.roundToInt(), dx, dy)
        }
        scrollAccumX = 0f; scrollAccumY = 0f
    }

    private fun avgX(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getX(i)
        return s / e.pointerCount
    }

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = dp(20f)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, padPaint)
        canvas.drawRoundRect(RectF(1f, 1f, width - 1f, height - 1f), r, r, padStroke)

        // Miniatura da tela do projetor no topo, com a posicao do cursor
        val margin = dp(14f)
        val miniW = width * 0.42f
        val miniH = miniW * screenHeight / screenWidth
        val left = (width - miniW) / 2f
        val top = margin
        canvas.drawRoundRect(RectF(left, top, left + miniW, top + miniH), dp(4f), dp(4f), screenPaint)
        val cx = left + cursorX / screenWidth * miniW
        val cy = top + cursorY / screenHeight * miniH
        canvas.drawCircle(cx, cy, dp(4f), cursorPaint)

        val baseY = height - margin
        canvas.drawText("Toque = clique · Segure = clique longo", width / 2f, baseY - dp(16f), hintPaint)
        canvas.drawText("Arraste = mover cursor · 2 dedos = rolar", width / 2f, baseY, hintPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(longPressRunnable)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
