package com.projector.remote.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View

/**
 * Dispara [action] ao tocar e repete enquanto o botao estiver pressionado,
 * como no controle fisico. [canRepeat] evita acumular comandos na fila
 * quando o projetor responde mais devagar que a repeticao.
 */
class RepeatTouchListener(
    private val initialDelayMs: Long = 400,
    private val intervalMs: Long = 120,
    private val canRepeat: () -> Boolean = { true },
    private val action: () -> Unit
) : View.OnTouchListener {

    private val handler = Handler(Looper.getMainLooper())
    private var pressedView: View? = null

    private val repeater = object : Runnable {
        override fun run() {
            if (pressedView == null) return
            if (canRepeat()) action()
            handler.postDelayed(this, intervalMs)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedView = v
                v.isPressed = true
                action()
                handler.postDelayed(repeater, initialDelayMs)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeater)
                pressedView = null
                v.isPressed = false
                if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            }
        }
        return true
    }
}
