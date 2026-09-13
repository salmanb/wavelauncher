package com.salman.wavelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * The wave alphabet rail, simplified. Letters sit in fixed slots. The letter
 * of the list section currently at the viewport top renders bigger and in the
 * accent color. While the user drags the list, a bubble showing the current
 * letter floats above the touch point (MainActivity feeds it via
 * showScrollHint/hideScrollHint). Dragging the rail itself still scrubs by
 * letter with the magnifier bubble.
 */
class WaveRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var letters: List<String> = emptyList()
        set(value) { field = value; requestLayout(); invalidate() }

    /** rail index of the letter currently at the top of the list; -1 = none */
    var currentLetterIndex: Int = -1
        set(value) { if (field != value) { field = value; invalidate() } }

    /** vertical offset of the scroll-hint bubble above the touch point (px) */
    var hintOffsetPx: Float = 120f * resources.displayMetrics.density

    var onLetterDrag: ((index: Int, letter: String) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    var accentColor: Int = 0xFF4DB6AC.toInt()
    var textColor: Int = 0xFF9AA0A6.toInt()
    var dark: Boolean = true

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val bubbleBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val magPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var dragging = false
    private var dragLetter = ""
    private var dragY = 0f

    private var hintLetter: String? = null
    private var hintY = 0f

    /** show the current-letter bubble above the list touch point (rail coords) */
    fun showScrollHint(letter: String, y: Float) {
        if (letter.isEmpty()) return
        hintLetter = letter
        hintY = y
        invalidate()
    }

    fun hideScrollHint() {
        if (hintLetter != null) {
            hintLetter = null
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = letters.size
        if (n == 0) return
        val step = height.toFloat() / (n + 1)
        val cx = width / 2f

        for (i in 0 until n) {
            val active = i == currentLetterIndex
            paint.textSize = (if (active) 17.5f else 11.5f).sp
            paint.color = if (active) accentColor else textColor
            paint.alpha = if (active) 255 else 200
            val y = step * (i + 1)
            canvas.drawText(letters[i], cx, y + paint.textSize / 3f, paint)
        }

        hintLetter?.let { drawBubble(canvas, it, hintY) }
        if (dragging) drawMagnifier(canvas)
    }

    /** accent chip with the current letter, floating well above the touch point */
    private fun drawBubble(canvas: Canvas, letter: String, y: Float) {
        bubbleText.textSize = 22f.sp
        val w = bubbleText.measureText(letter) + 26f.dpi
        val h = 40f.dpi
        val right = -10f.dpi                       // just left of the rail edge
        val top = (y - hintOffsetPx - h / 2f).coerceIn(6f, height - h - 6f)
        bubbleBg.color = accentColor
        canvas.drawRoundRect(right - w, top, right, top + h, 13f.dpi, 13f.dpi, bubbleBg)
        bubbleText.color = if (dark) Color.BLACK else Color.WHITE
        bubbleText.alpha = 255
        canvas.drawText(letter, right - w / 2f, top + h / 2f + bubbleText.textSize / 3f, bubbleText)
    }

    private fun drawMagnifier(canvas: Canvas) {
        val text = dragLetter
        magPaint.textSize = 26f.sp
        magPaint.color = accentColor
        val w = magPaint.measureText(text) + 28f.dpi
        val x = width - w - 12f.dpi
        val top = (dragY - hintOffsetPx).coerceIn(6f, height - 46f.dpi)
        canvas.save()
        canvas.translate(x, top)
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
        val idx = (Math.round(y / step) - 1).coerceIn(0, n - 1)
        dragLetter = letters[idx]
        onLetterDrag?.invoke(idx, dragLetter)
        invalidate()
    }

    private companion object {
        const val BASE_SP = 11.5f
    }

    private val Float.sp: Float get() = this * resources.displayMetrics.scaledDensity
    private val Float.dpi: Float get() = this * resources.displayMetrics.density
}
