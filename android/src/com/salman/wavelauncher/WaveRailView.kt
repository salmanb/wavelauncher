package com.salman.wavelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * The wave alphabet rail. MainActivity feeds `letterRelY`: each letter's row
 * position relative to the viewport top (negative = scrolled past). Letters
 * that just passed the top keep a docked-tilted pose; letters in the
 * transition zone trace a continuous rotation curve, so the visible letters
 * flow into an arc. Scroll velocity (`waveAmp`) pulses the amplitude.
 */
class WaveRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var letters: List<String> = emptyList()
        set(value) { field = value; requestLayout(); invalidate() }

    /** viewport-relative Y (px) of each letter's first row; negative = passed */
    var letterRelY: IntArray = IntArray(0)
        set(value) { field = value; invalidate() }

    var waveAmp: Float = 0f
        set(value) { if (abs(field - value) > 0.3f) { field = value; invalidate() } }

    var onLetterDrag: ((index: Int, letter: String) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    var accentColor: Int = 0xFF4DB6AC.toInt()
    var textColor: Int = 0xFF9AA0A6.toInt()
    var dark: Boolean = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val magPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var dragging = false
    private var dragLetter = ""
    private var dragY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = letters.size
        if (n == 0 || letterRelY.size != n) return
        val step = height.toFloat() / (n + 1)
        val cx = width / 2f
        val pulse = 1f + min(waveAmp, 6f) * 0.25f

        for (i in 0 until n) {
            val rel = letterRelY[i].toFloat()
            val y = step * (i + 1)          // fixed slot — letters never move vertically

            var xOut = 0f
            var rot = 0f
            var alpha = 235f
            var size = 11.5f

            when {
                rel < -10f -> {                    // row scrolled past: docked tilt
                    xOut = 9f.dpi * pulse
                    rot = 42f
                    alpha = 72f
                    size = 10.5f
                }
                rel < 150f -> {                    // transition: swing out over the list
                    val t = 1f - (rel / 150f).coerceIn(0f, 1f)
                    val swing = sin(t * Math.PI).toFloat() * 10f.dpi * pulse
                    xOut = swing
                    rot = swing * 3.4f
                    alpha = 235f
                    size = 11.5f
                }
                else -> { }                        // below crest: upright
            }

            val active = rel > -40f && rel < 120f
            paint.textSize = (if (active) 15f else size).sp
            paint.color = if (active) accentColor else textColor
            paint.alpha = alpha.toInt().coerceIn(0, 255)

            canvas.save()
            canvas.translate(cx - xOut, y)
            if (rot > 0.5f) canvas.rotate(rot)
            canvas.drawText(letters[i], 0f, paint.textSize / 3f, paint)
            canvas.restore()
        }

        if (dragging) drawMagnifier(canvas)
    }

    private data class Pose(val xOut: Float, val rot: Float, val alpha: Float, val size: Float)

    private fun dockX(): Float = 7f.dpi

    private fun drawMagnifier(canvas: Canvas) {
        val text = dragLetter
        magPaint.textSize = 26f.sp
        magPaint.color = accentColor
        val w = magPaint.measureText(text) + 28f.dpi
        val x = width - w - 12f.dpi
        val y = dragY.coerceIn(30f, height - 30f)
        canvas.save()
        canvas.translate(x, y - 14f.dpi)
        bgPaint.color = if (dark) 0xFF1B1F24.toInt() else 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(0f, 0f, w, 40f.dpi, 12f.dpi, 12f.dpi, bgPaint)
        magPaint.alpha = 255
        canvas.drawText(text, w / 2f, 28f.dpi, magPaint)
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                dragY = e.y
                pickLetter(e.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                dragY = e.y
                pickLetter(e.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                onDragEnd?.invoke()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun pickLetter(y: Float) {
        val n = letters.size
        if (n == 0) return
        val step = height.toFloat() / (n + 1)
        val idx = ((y / step).toInt() - 1).coerceIn(0, n - 1)
        dragLetter = letters[idx]
        onLetterDrag?.invoke(idx, dragLetter)
        invalidate()
    }

    private val Float.sp: Float get() = this * resources.displayMetrics.scaledDensity
    private val Float.dpi: Float get() = this * resources.displayMetrics.density
}
