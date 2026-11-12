package com.example.pettracker.charts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * UI-6.2: A small bar or line graph, drawn by hand so the app needs no extra library.
 * Use setBars(...) or setLine(...) with the values and their labels.
 * With no values it shows [emptyText] instead.
 */
class SimpleChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var values = FloatArray(0)
    private var labels = arrayOf<String>()
    private var bars = true
    private var unit = ""
    var emptyText = "No data yet"
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE2E8F0.toInt()
        strokeWidth = 1f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF475569.toInt()
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    /** Bar graph, one bar per label (meals per day). */
    fun setBars(values: FloatArray, labels: Array<String>, color: Int, unit: String = "") {
        this.values = values; this.labels = labels; this.bars = true; this.unit = unit
        barPaint.color = color
        invalidate()
    }

    /** Line graph (movement over time). Only the first and last labels are drawn. */
    fun setLine(values: FloatArray, labels: Array<String>, color: Int, unit: String = "") {
        this.values = values; this.labels = labels; this.bars = false; this.unit = unit
        linePaint.color = color
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = resolveSize((180 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(emptyText, width / 2f, height / 2f, textPaint)
            return
        }

        // Space for the numbers on the left and the labels at the bottom
        val left = 46 * density
        val bottom = height - 22 * density
        val top = 8 * density
        val right = width.toFloat() - 4 * density
        // Bars count things (meals), so keep the grid on whole numbers: 2, 4, 6...
        val biggest = values.maxOrNull() ?: 0f
        val max = if (bars) (Math.ceil(biggest / 2.0) * 2).toFloat().coerceAtLeast(2f) else niceMax(biggest)

        // 3 grid lines with their values: 0, half, max
        textPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..2) {
            val y = bottom - (bottom - top) * i / 2f
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText(format(max * i / 2f) + unit, left - 6 * density, y + 4 * density, textPaint)
        }

        val chartWidth = right - left
        textPaint.textAlign = Paint.Align.CENTER
        if (bars) {
            val slot = chartWidth / values.size
            val barWidth = slot * 0.55f
            for (i in values.indices) {
                val x = left + slot * i + (slot - barWidth) / 2
                val y = bottom - (bottom - top) * (values[i] / max)
                canvas.drawRoundRect(RectF(x, y, x + barWidth, bottom), 4 * density, 4 * density, barPaint)
                if (i < labels.size) canvas.drawText(labels[i], x + barWidth / 2, height - 4 * density, textPaint)
            }
        } else {
            val path = Path()
            val step = if (values.size > 1) chartWidth / (values.size - 1) else 0f
            for (i in values.indices) {
                val x = left + step * i
                val y = bottom - (bottom - top) * (values[i] / max)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)
            if (labels.isNotEmpty()) {
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(labels.first(), left, height - 4 * density, textPaint)
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(labels.last(), right, height - 4 * density, textPaint)
            }
        }
    }

    companion object {
        /** A round top value for the graph (1, 2, 5, 10, 20, ...), so the grid numbers are easy to read. */
        @JvmStatic
        fun niceMax(value: Float): Float {
            if (value <= 0f) return 1f
            var step = 0.01f
            while (true) {
                for (m in floatArrayOf(1f, 2f, 5f)) {
                    if (step * m >= value) return step * m
                }
                step *= 10f
            }
        }

        private fun format(v: Float): String = when {
            v == Math.round(v).toFloat() || v >= 10f -> Math.round(v).toString()
            v >= 1f -> "%.1f".format(v)
            else -> "%.2f".format(v)
        }
    }
}
