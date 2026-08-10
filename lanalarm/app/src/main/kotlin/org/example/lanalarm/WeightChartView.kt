package org.example.lanalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * A plain line chart of weigh-ins over time.
 *
 * Drawn by hand rather than pulling in a charting library: this needs one line,
 * a filled area under it and two axis labels, and a dependency for that would be
 * more code to keep working than the fifty lines below.
 *
 * The vertical scale is padded around the actual range rather than starting at
 * zero — the interesting signal is a couple of kilos of movement, which a
 * zero-based axis would flatten into a straight line.
 */
class WeightChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Point(val atMillis: Long, val weightKg: Double)

    private var points: List<Point> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.secondary_accent)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.secondary_accent)
        alpha = 38
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.secondary_accent)
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ContextCompat.getColor(context, R.color.divider)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        textSize = 26f
    }

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_muted)
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }

    /** Oldest first. */
    fun setPoints(newPoints: List<Point>) {
        points = newPoints.sortedBy { it.atMillis }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (points.size < 2) {
            canvas.drawText(
                if (points.isEmpty()) "No weigh-ins yet" else "One weigh-in so far",
                width / 2f,
                height / 2f,
                emptyPaint
            )
            return
        }

        val minWeightForLabel = points.minOf { it.weightKg }
        val maxWeightForLabel = points.maxOf { it.weightKg }

        val padLeft = 8f
        // Measure the labels rather than guessing a width: at three digits plus
        // a decimal the guess clipped the "g" off "kg".
        val labelGap = 10f
        val padRight = labelGap + listOf(minWeightForLabel, maxWeightForLabel)
            .maxOf { labelPaint.measureText(String.format(Locale.US, "%.1f kg", it)) }
        val padTop = 18f
        val padBottom = 18f

        val plotWidth = width - padLeft - padRight
        val plotHeight = height - padTop - padBottom

        val minWeight = minWeightForLabel
        val maxWeight = maxWeightForLabel
        // Never let a flat series collapse to a zero-height range.
        val span = max(maxWeight - minWeight, 0.4)
        val low = minWeight - span * 0.15
        val high = maxWeight + span * 0.15
        val range = high - low

        val firstAt = points.first().atMillis
        val lastAt = points.last().atMillis
        val timeSpan = max((lastAt - firstAt).toDouble(), 1.0)

        fun x(point: Point): Float =
            padLeft + ((point.atMillis - firstAt) / timeSpan * plotWidth).toFloat()

        fun y(point: Point): Float =
            padTop + ((high - point.weightKg) / range * plotHeight).toFloat()

        // Reference lines at the extremes of the real data, labelled.
        listOf(maxWeight, minWeight).forEach { value ->
            val lineY = padTop + ((high - value) / range * plotHeight).toFloat()
            canvas.drawLine(padLeft, lineY, padLeft + plotWidth, lineY, gridPaint)
            canvas.drawText(
                String.format(Locale.US, "%.1f kg", value),
                padLeft + plotWidth + labelGap,
                lineY + 9f,
                labelPaint
            )
        }

        val line = Path()
        val area = Path()
        points.forEachIndexed { index, point ->
            val px = x(point)
            val py = y(point)
            if (index == 0) {
                line.moveTo(px, py)
                area.moveTo(px, padTop + plotHeight)
                area.lineTo(px, py)
            } else {
                line.lineTo(px, py)
                area.lineTo(px, py)
            }
        }
        area.lineTo(x(points.last()), padTop + plotHeight)
        area.close()

        canvas.drawPath(area, fillPaint)
        canvas.drawPath(line, linePaint)

        // Dots only when they will not merge into a smear.
        if (points.size <= 60) {
            val radius = min(6f, plotWidth / points.size / 2f)
            points.forEach { canvas.drawCircle(x(it), y(it), radius, dotPaint) }
        }
    }
}
