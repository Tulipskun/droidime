package com.tulipskun.droidime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.util.DisplayMetrics
import android.util.Log

/**
 * Draws a visible pointer on top of every app so a human can see where the agent
 * is about to act.
 *
 * The cursor stays on screen between actions (it does not disappear after the
 * tap animation ends), so a person watching the device can always see the last
 * position the agent used. The window is never touchable and never focusable,
 * so it cannot intercept or disturb the input events it is illustrating.
 */
class PointerOverlay(private val context: Context) {

    private companion object {
        const val TAG = "DroidIME-Pointer"
    }

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var view: PointerView? = null

    @Volatile
    private var enabled: Boolean = true

    fun setEnabled(value: Boolean) {
        enabled = value
        Log.d(TAG, "setEnabled($value)")
        if (!value) {
            main.post { detach() }
        } else {
            attach()
        }
    }

    /**
     * Adds the overlay window eagerly, so the cursor is visible as soon as the
     * MCP server starts instead of only after the first successful action.
     */
    fun attach() {
        if (!enabled) {
            Log.d(TAG, "attach skipped: overlay disabled")
            return
        }
        main.post {
            val v = ensureWindow()
            Log.d(
                TAG,
                "attach result: " + (if (v != null) "window ready" else "window failed")
            )
        }
    }

    fun isEnabled(): Boolean = enabled

    fun showTap(x: Int, y: Int) {
        if (!enabled) {
            Log.d(TAG, "showTap skipped: overlay disabled")
            return
        }
        main.post {
            val v = ensureWindow() ?: return@post
            v.animateTap(x.toFloat(), y.toFloat())
        }
    }

    fun showLongPress(x: Int, y: Int, durationMs: Long) {
        if (!enabled) {
            Log.d(TAG, "showLongPress skipped: overlay disabled")
            return
        }
        main.post {
            val v = ensureWindow() ?: return@post
            v.animateHold(x.toFloat(), y.toFloat(), durationMs)
        }
    }

    fun showSwipe(x1: Int, y1: Int, x2: Int, y2: Int) {
        if (!enabled) {
            Log.d(TAG, "showSwipe skipped: overlay disabled")
            return
        }
        main.post {
            val v = ensureWindow() ?: return@post
            v.animateSwipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureWindow(): PointerView? {
        view?.let { return it }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
        val created = PointerView(context).also { it.setWillNotDraw(false) }

        // MATCH_PARENT resolves to the inset frame (2264px on a 2400px display),
        // which clips a pointer near the bottom edge. Ask for the full display
        // size explicitly so every coordinate from screen_size is drawable.
        val full = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                val metrics = DisplayMetrics()
                wm.defaultDisplay.getRealMetrics(metrics)
                metrics.widthPixels to metrics.heightPixels
            }
        } catch (_: Throwable) {
            null
        }
        Log.d(TAG, "overlay size = " + (full?.first ?: -1) + "x" + (full?.second ?: -1))

        val params = WindowManager.LayoutParams(
            full?.first ?: WindowManager.LayoutParams.MATCH_PARENT,
            full?.second ?: WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // Without this the system insets the window by the navigation
                // bar (2264px on a 2400px screen), so a pointer near the
                // bottom edge is clipped away and only its label survives.
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 1f
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
            Log.d(TAG, "overlay window added")
            created
        } catch (e: Throwable) {
            // No overlay permission: the agent still works, it just is not visible.
            Log.e(TAG, "addView failed, pointer will be invisible", e)
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

        private enum class Mode { IDLE, TAP, HOLD, SWIPE }

        private var mode = Mode.IDLE
        private var progress = 0f

        private var lastX = 0f
        private var lastY = 0f
        private var hasPosition = false

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

        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
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
                    // Keep a persistent cursor instead of clearing the canvas.
                    mode = Mode.IDLE
                    invalidate()
                    return
                }
                invalidate()
                postOnAnimation(this)
            }
        }

        fun animateTap(x: Float, y: Float) {
            lastX = x
            lastY = y
            hasPosition = true
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
            lastX = x
            lastY = y
            hasPosition = true
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
            lastX = x2
            lastY = y2
            hasPosition = true
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

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (!hasPosition && w > 0 && h > 0) {
                lastX = w / 2f
                lastY = h / 2f
                hasPosition = true
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            when (mode) {
                Mode.IDLE -> drawIdle(canvas)
                Mode.TAP -> drawTap(canvas)
                Mode.HOLD -> drawHold(canvas)
                Mode.SWIPE -> drawSwipe(canvas)
            }
        }

        /** A cursor that remains visible between actions. */
        private fun drawIdle(canvas: Canvas) {
            val x = lastX
            val y = lastY

            halo.alpha = 230
            canvas.drawCircle(x, y, 25f, halo)
            halo.alpha = 150
            canvas.drawCircle(x, y, 39f, halo)

            ring.alpha = 230
            canvas.drawCircle(x, y, 20f, ring)
            innerRing.alpha = 150
            canvas.drawCircle(x, y, 34f, innerRing)

            dot.alpha = 255
            canvas.drawCircle(x, y, 8f, dot)

            cross.alpha = 235
            canvas.drawLine(x - 46f, y, x - 20f, y, cross)
            canvas.drawLine(x + 20f, y, x + 46f, y, cross)
            canvas.drawLine(x, y - 46f, x, y - 20f, cross)
            canvas.drawLine(x, y + 20f, x, y + 46f, cross)

            label.setLength(0)
            label.append(x.toInt()).append(", ").append(y.toInt())
            drawLabel(canvas, x, y, 1f)
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
            val textWidth = labelText.measureText(text)
            val padH = 18f
            val padV = 14f
            val boxW = textWidth + padH * 2
            val boxH = labelText.textSize + padV * 2

            // Place above the pointer, flipping below when it would leave the screen.
            var boxTop = y - 70f - boxH
            if (boxTop < 24f) boxTop = y + 70f
            var boxLeft = x - boxW / 2f
            if (boxLeft < 12f) boxLeft = 12f
            if (boxLeft + boxW > this.width.toFloat() - 12f) {
                boxLeft = this.width.toFloat() - 12f - boxW
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
            // ~1.4s at 60fps, plus a persistent cursor afterwards.
            const val STEP = 0.012f
        }
    }
}
