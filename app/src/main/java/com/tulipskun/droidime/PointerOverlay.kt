package com.tulipskun.droidime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Draws a visible pointer on top of every app so a human can see where the agent
 * is about to act.
 *
 * The window is never touchable and never focusable, so it cannot intercept or
 * disturb the very input events it is illustrating. Every animation is driven on
 * the main thread and removes itself when it finishes, so an idle overlay costs
 * nothing.
 */
class PointerOverlay(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var view: PointerView? = null

    @Volatile
    private var enabled: Boolean = true

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            main.post { detach() }
        }
    }

    fun isEnabled(): Boolean = enabled

    fun showTap(x: Int, y: Int) {
        if (!enabled) return
        main.post {
            val v = ensureWindow() ?: return@post
            v.animateTap(x.toFloat(), y.toFloat())
        }
    }

    fun showLongPress(x: Int, y: Int, durationMs: Long) {
        if (!enabled) return
        main.post {
            val v = ensureWindow() ?: return@post
            v.animateHold(x.toFloat(), y.toFloat(), durationMs)
        }
    }

    fun showSwipe(x1: Int, y1: Int, x2: Int, y2: Int) {
        if (!enabled) return
        main.post {
            val v = ensureWindow() ?: return@post
            v.animateSwipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
        }
    }

    private fun ensureWindow(): PointerView? {
        view?.let { return it }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val created = PointerView(context)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // Keep it above normal app content but below the status bar / IME.
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        return try {
            wm.addView(created, params)
            windowManager = wm
            view = created
            created
        } catch (e: Throwable) {
            // No overlay permission: the agent still works, it just is not visible.
            null
        }
    }

    private fun detach() {
        val v = view ?: return
        view = null
        try {
            windowManager?.removeView(v)
        } catch (_: Throwable) {
        }
        windowManager = null
    }

    /**
     * Full-screen transparent canvas that renders one short-lived pointer animation.
     * All state lives here so the overlay window can be added once and reused.
     */
    private class PointerView(context: Context) : View(context) {

        private enum class Mode { NONE, TAP, HOLD, SWIPE }

        private var mode = Mode.NONE
        private var progress = 0f

        private var startX = 0f
        private var startY = 0f
        private var endX = 0f
        private var endY = 0f
        private var holdMs = 0L

        private val accent = Color.parseColor("#FF3B30")
        private val accentSoft = Color.parseColor("#66FF3B30")

        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = accent
        }

        private val innerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = accent
        }

        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = accent
        }

        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.WHITE
        }

        private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            color = accentSoft
            strokeCap = Paint.Cap.ROUND
        }

        private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#CC111111")
        }

        private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            isFakeBoldText = true
        }

        private val label = StringBuilder()

        private val tick = object : Runnable {
            override fun run() {
                progress += STEP
                if (progress >= 1f) {
                    progress = 1f
                    mode = Mode.NONE
                    invalidate()
                    return
                }
                invalidate()
                postOnAnimation(this)
            }
        }

        fun animateTap(x: Float, y: Float) {
            startX = x
            startY = y
            endX = x
            endY = y
            mode = Mode.TAP
            progress = 0f
            label.setLength(0)
            label.append(x.toInt()).append(", ").append(y.toInt())
            restart()
        }

        fun animateHold(x: Float, y: Float, durationMs: Long) {
            startX = x
            startY = y
            endX = x
            endY = y
            holdMs = durationMs
            mode = Mode.HOLD
            progress = 0f
            label.setLength(0)
            label.append("hold ").append(x.toInt()).append(", ").append(y.toInt())
            restart()
        }

        fun animateSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
            startX = x1
            startY = y1
            endX = x2
            endY = y2
            mode = Mode.SWIPE
            progress = 0f
            label.setLength(0)
            label.append(x1.toInt()).append(", ").append(y1.toInt())
                .append("  >  ")
                .append(x2.toInt()).append(", ").append(y2.toInt())
            restart()
        }

        private fun restart() {
            removeCallbacks(tick)
            invalidate()
            postOnAnimation(tick)
        }

        override fun onDraw(canvas: Canvas) {
            if (mode == Mode.NONE) return

            when (mode) {
                Mode.TAP -> drawTap(canvas)
                Mode.HOLD -> drawHold(canvas)
                Mode.SWIPE -> drawSwipe(canvas)
                Mode.NONE -> Unit
            }
        }

        private fun drawTap(canvas: Canvas) {
            val fade = 1f - progress

            // Expanding confirmation ring.
            val radius = 26f + 58f * progress
            ring.alpha = (255 * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(startX, startY, radius, ring)

            // Steady inner ring so the exact point stays readable.
            innerRing.alpha = (200 * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(startX, startY, 22f, innerRing)

            // Centre dot plus crosshair.
            dot.alpha = (255 * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(startX, startY, 9f, dot)

            cross.alpha = (230 * fade).toInt().coerceIn(0, 255)
            canvas.drawLine(startX - 34f, startY, startX - 14f, startY, cross)
            canvas.drawLine(startX + 14f, startY, startX + 34f, startY, cross)
            canvas.drawLine(startX, startY - 34f, startX, startY - 14f, cross)
            canvas.drawLine(startX, startY + 14f, startX, startY + 34f, cross)

            drawLabel(canvas, startX, startY, fade)
        }

        private fun drawHold(canvas: Canvas) {
            val fade = 1f - progress

            // A hold fills its ring over time, so the duration is visible.
            val sweep = 360f * progress
            ring.alpha = (255 * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(startX, startY, 40f, ring)

            val arc = Paint(ring).apply {
                strokeWidth = 8f
                color = accent
                alpha = (255 * fade).toInt().coerceIn(0, 255)
            }
            canvas.drawArc(
                RectF(startX - 40f, startY - 40f, startX + 40f, startY + 40f),
                -90f,
                sweep,
                false,
                arc
            )

            dot.alpha = (255 * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(startX, startY, 10f, dot)

            drawLabel(canvas, startX, startY, fade)
        }

        private fun drawSwipe(canvas: Canvas) {
            val fade = 1f - progress

            trail.alpha = (200 * fade).toInt().coerceIn(0, 255)
            canvas.drawLine(startX, startY, endX, endY, trail)

            // Dot travelling along the path.
            val cx = startX + (endX - startX) * progress
            val cy = startY + (endY - startY) * progress
            dot.alpha = (255 * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, 14f, dot)

            // Ring at the start point.
            innerRing.alpha = (170 * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(startX, startY, 20f, innerRing)

            drawLabel(canvas, cx, cy, fade)
        }

        private fun drawLabel(canvas: Canvas, x: Float, y: Float, fade: Float) {
            if (label.isEmpty()) return

            val text = label.toString()
            val width = labelText.measureText(text)
            val padH = 18f
            val padV = 14f
            val boxW = width + padH * 2
            val boxH = labelText.textSize + padV * 2

            // Place above the pointer, flipping below when it would leave the screen.
            var boxTop = y - 70f - boxH
            if (boxTop < 24f) boxTop = y + 70f
            var boxLeft = x - boxW / 2f
            if (boxLeft < 12f) boxLeft = 12f
            if (boxLeft + boxW > width.toFloat() - 12f) {
                boxLeft = width.toFloat() - 12f - boxW
            }

            labelBg.alpha = (200 * fade).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(
                RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH),
                14f,
                14f,
                labelBg
            )

            labelText.alpha = (255 * fade).toInt().coerceIn(0, 255)
            canvas.drawText(
                text,
                boxLeft + padH,
                boxTop + padV + labelText.textSize * 0.82f,
                labelText
            )
        }

        private companion object {
            // ~700ms at 60fps.
            const val STEP = 0.024f
        }
    }
}
