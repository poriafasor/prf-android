package com.prf.security.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.prf.security.util.Persian
import com.prf.security.util.Wheel
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The prize wheel, drawn rather than shipped as a picture.
 *
 * Drawing it in code rather than with six PNGs is not a flourish: the slice
 * widths have to be proportional to the weights, and three of those weights are
 * zero. A fixed image would have to draw three slices with real width for them
 * to be visible, and a slice with visible width looks winnable. Here the three
 * zero slices take up no angle at all — the pointer passes over them as part of
 * the rim and they are labelled underneath in the list instead.
 *
 * The spin is an animation over the same [Wheel.spin] result the logic already
 * decided, so what the pointer lands on and what the app then acts on cannot
 * disagree. The animation is decoration over a decision that has already been
 * made and stored.
 */
class WheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#27364F")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#21E6C1")
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFB340")
    }

    /** Zero-weight slices get a colour but no arc, so the rim still reads as one piece. */
    private val colors = listOf(
        "#1F3B57", "#1F3B57", "#1F3B57",
        "#16202F", "#0F8F7C", "#16202F",
    )

    private var rotation = 0f
    private var spinner: ValueAnimator? = null

    /** The slice the pointer is over, for the caller to announce. */
    var landed: Wheel.Slice? = null
        private set

    init {
        contentDescription = "گردونه‌ی شانس"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = min(w, h)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 10f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)

        canvas.save()
        canvas.rotate(rotation, cx, cy)

        val total = Wheel.SLICES.sumOf { it.weight }.toFloat()
        var start = -90f
        for ((i, slice) in Wheel.SLICES.withIndex()) {
            val sweep = slice.weight / total * 360f
            if (sweep > 0f) {
                arcPaint.color = Color.parseColor(colors[i % colors.size])
                canvas.drawArc(oval, start, sweep, true, arcPaint)
                canvas.drawArc(oval, start, sweep, true, edgePaint)
                drawLabel(canvas, slice.label, cx, cy, r, start + sweep / 2f)
            }
            start += sweep
        }
        canvas.restore()

        // The rim, drawn outside the rotation so it does not turn with the wheel
        // and give the whole thing a wobble.
        canvas.drawCircle(cx, cy, r + 4f, rimPaint)
        canvas.drawCircle(cx, cy, 6f, pointerPaint)
        drawPointer(canvas, cx, cy, r)
    }

    private fun drawLabel(canvas: Canvas, label: String, cx: Float, cy: Float, r: Float, midAngle: Float) {
        val rad = Math.toRadians((midAngle).toDouble())
        val lx = cx + (r * 0.66f) * cos(rad).toFloat()
        val ly = cy + (r * 0.66f) * sin(rad).toFloat()
        textPaint.textSize = (r * 0.085f).coerceIn(9f, 16f)
        canvas.save()
        canvas.rotate(midAngle + 90f, lx, ly)
        // The long labels are the whole label, not a truncation: a wheel whose
        // slices say "۵۰ ه…" and "۱۰۰ ه…" looks like it has two 50-toman prizes.
        canvas.drawText(label, lx, ly + textPaint.textSize / 3f, textPaint)
        canvas.restore()
    }

    private fun drawPointer(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Path()
        val top = cy - r - 2f
        p.moveTo(cx, top + r * 0.16f)
        p.lineTo(cx - r * 0.07f, top)
        p.lineTo(cx + r * 0.07f, top)
        p.close()
        canvas.drawPath(p, pointerPaint)
    }

    /**
     * Spins to [hit] and reports it.
     *
     * Four full turns plus the angle that puts [hit] under the pointer. The
     * duration grows with how far it has to travel, because a spin that always
     * takes the same time reads as a canned animation rather than a result.
     */
    fun spinTo(hit: Wheel.Slice) {
        landed = hit
        val index = Wheel.SLICES.indexOfFirst { it.key == hit.key }.coerceAtLeast(0)
        val target = Wheel.centerAngle(index)
        spinner?.cancel()
        val from = rotation
        val delta = ((target - (from % 360f)) + 360f * 4) % 360f
        val spin = 4 * 360f + delta
        spinner = ValueAnimator.ofFloat(from, from + spin).apply {
            duration = 1600L + (delta.toLong() * 4L)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                rotation = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** The weight of each slice, as Persian text, for the list under the wheel. */
    fun oddsLine(): String = Wheel.SLICES.joinToString("  ·  ") { s ->
        Persian.toPersianDigits(s.weight.toString()) + "٪ " + s.label
    }

    override fun onDetachedFromWindow() {
        spinner?.cancel()
        spinner = null
        super.onDetachedFromWindow()
    }
}
