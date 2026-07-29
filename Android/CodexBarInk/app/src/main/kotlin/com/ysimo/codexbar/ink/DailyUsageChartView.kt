package com.ysimo.codexbar.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.ColorInt
import com.ysimo.codexbar.ink.core.UsageHistoryKind
import com.ysimo.codexbar.ink.core.UsageHistoryPoint
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

class DailyUsageChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_black)
        textSize = sp(15f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_dark)
        textSize = sp(13f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_light)
        strokeWidth = dp(1f)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.ink_black)
        style = Paint.Style.FILL
    }

    private var title = context.getString(R.string.daily_usage)
    private var emptyLabel = context.getString(R.string.usage_history_unavailable)
    private var kind = UsageHistoryKind.COST
    private var points: List<UsageHistoryPoint> = emptyList()
    private var compactMode = false

    fun bind(
        title: String,
        emptyLabel: String,
        kind: UsageHistoryKind?,
        points: List<UsageHistoryPoint>,
    ) {
        this.title = title
        this.emptyLabel = emptyLabel
        this.kind = kind ?: UsageHistoryKind.COST
        this.points = points.takeLast(MAX_POINTS)
        contentDescription = if (this.points.isEmpty()) {
            "$title. $emptyLabel"
        } else {
            "$title. ${this.points.size} days."
        }
        invalidate()
    }

    fun setAccentColor(@ColorInt color: Int) {
        if (barPaint.color == color) return
        barPaint.color = color
        invalidate()
    }

    fun setCompactMode(compact: Boolean) {
        if (compactMode == compact) return
        compactMode = compact
        titlePaint.textSize = sp(if (compact) 12f else 15f)
        labelPaint.textSize = sp(if (compact) 10f else 13f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right <= left || bottom <= top) return

        canvas.drawText(title.uppercase(Locale.getDefault()), left, top - titlePaint.fontMetrics.top, titlePaint)
        val chartTop = top + dp(if (compactMode) 30f else 36f)
        val chartBottom = bottom - dp(if (compactMode) 17f else 21f)
        if (points.isEmpty() || chartBottom <= chartTop) {
            canvas.drawText(emptyLabel, left, chartTop + dp(28f), labelPaint)
            return
        }

        val maximum = max(points.maxOf { it.value }, 1.0)
        repeat(3) { index ->
            val y = chartTop + (chartBottom - chartTop) * index / 2f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        val maximumLabel = when (kind) {
            UsageHistoryKind.COST -> String.format(Locale.getDefault(), "\$%.2f", maximum)
            UsageHistoryKind.TOKENS -> compactNumber(maximum)
        }
        canvas.drawText(maximumLabel, left, chartTop - dp(5f), labelPaint)

        val slotWidth = (right - left) / points.size
        val barWidth = (slotWidth * 0.58f).coerceAtLeast(dp(3f))
        points.forEachIndexed { index, point ->
            val centerX = left + slotWidth * (index + 0.5f)
            val barHeight = ((point.value / maximum) * (chartBottom - chartTop)).toFloat()
            canvas.drawRect(
                centerX - barWidth / 2,
                chartBottom - barHeight,
                centerX + barWidth / 2,
                chartBottom,
                barPaint,
            )
            val shouldDrawLabel = if (compactMode) {
                index == 0 || index == points.lastIndex / 2 || index == points.lastIndex
            } else {
                index == 0 ||
                    index == points.lastIndex ||
                    (index % 4 == 0 && index <= points.lastIndex - 3)
            }
            if (shouldDrawLabel) {
                val label = shortDate(point.date)
                val labelWidth = labelPaint.measureText(label)
                val labelX = (centerX - labelWidth / 2).coerceIn(left, right - labelWidth)
                canvas.drawText(label, labelX, bottom, labelPaint)
            }
        }
    }

    private fun shortDate(raw: String): String = runCatching {
        DATE_LABEL_FORMATTER.format(LocalDate.parse(raw.take(10)))
    }.getOrDefault(raw.takeLast(5))

    private fun compactNumber(value: Double): String = when {
        value >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000)
        value >= 1_000 -> String.format(Locale.getDefault(), "%.1fK", value / 1_000)
        else -> value.toInt().toString()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    private companion object {
        const val MAX_POINTS = 14
        val DATE_LABEL_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")
    }
}
