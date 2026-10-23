package com.example.astockscreener

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Locale

/**
 * 分时图：参考主流行情软件的配色习惯——
 *   现价 >= 昨收 用红色（A股涨=红），< 昨收用绿色，逐段渲染（不是整条线一个颜色）
 *   中间一条白色虚线是昨收参考线
 *   下半部分是成交量柱状图，颜色跟对应那一分钟的涨跌方向一致
 *   左上角显示最高/最低价，昨收价标在参考线左侧
 */
class MinuteChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val COLOR_UP = 0xFFFF5252.toInt()      // 红：涨
        private const val COLOR_DOWN = 0xFF4CAF50.toInt()    // 绿：跌
        private const val COLOR_GRID = 0xFF2A2A2A.toInt()
        private const val COLOR_AXIS_TEXT = 0xFFAAAAAA.toInt()
        private const val COLOR_REF_LINE = 0xFFBBBBBB.toInt()
        private const val COLOR_BG = 0xFF121212.toInt()
    }

    private var points: List<MinutePoint> = emptyList()
    private var prevClose: Double = Double.NaN

    private val bgPaint = Paint().apply { color = COLOR_BG }
    private val gridPaint = Paint().apply {
        color = COLOR_GRID
        strokeWidth = 1.5f
    }
    private val refLinePaint = Paint().apply {
        color = COLOR_REF_LINE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val linePaint = Paint().apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val volPaint = Paint().apply { isAntiAlias = true }
    private val textPaint = Paint().apply {
        color = COLOR_AXIS_TEXT
        textSize = 28f
        isAntiAlias = true
    }

    fun setData(points: List<MinutePoint>, prevClose: Double) {
        this.points = points
        this.prevClose = prevClose
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (points.isEmpty() || prevClose.isNaN() || prevClose <= 0) {
            canvas.drawText("暂无分时数据", w / 2 - 80, h / 2, textPaint)
            return
        }

        val topMargin = 50f
        val bottomMargin = 10f
        val leftMargin = 90f
        val rightMargin = 16f
        val priceAreaHeight = (h - topMargin - bottomMargin) * 0.68f
        val volAreaTop = topMargin + priceAreaHeight + 16f
        val volAreaHeight = h - volAreaTop - bottomMargin

        val maxPrice = maxOf(points.maxOf { it.price }, prevClose)
        val minPrice = minOf(points.minOf { it.price }, prevClose)
        val priceRange = (maxPrice - minPrice).let { if (it <= 0) 1.0 else it }
        // 价格区间留一点上下边距，避免曲线贴边
        val paddedMax = maxPrice + priceRange * 0.08
        val paddedMin = minPrice - priceRange * 0.08
        val paddedRange = (paddedMax - paddedMin).let { if (it <= 0) 1.0 else it }

        fun yOf(price: Double): Float {
            return (topMargin + priceAreaHeight * (1 - ((price - paddedMin) / paddedRange))).toFloat()
        }

        val chartWidth = w - leftMargin - rightMargin
        val n = points.size
        fun xOf(i: Int): Float = leftMargin + if (n <= 1) 0f else chartWidth * i / (n - 1).toFloat()

        // 网格：三条横线（最高/昨收/最低）
        canvas.drawLine(leftMargin, topMargin, w - rightMargin, topMargin, gridPaint)
        canvas.drawLine(leftMargin, topMargin + priceAreaHeight, w - rightMargin, topMargin + priceAreaHeight, gridPaint)
        val refY = yOf(prevClose)
        canvas.drawLine(leftMargin, refY, w - rightMargin, refY, refLinePaint)

        // 坐标文字
        canvas.drawText(fmt(paddedMax), 4f, topMargin + 10f, textPaint)
        canvas.drawText(fmt(prevClose), 4f, refY + 10f, textPaint)
        canvas.drawText(fmt(paddedMin), 4f, topMargin + priceAreaHeight, textPaint)

        // 分段染色的价格曲线
        val maxVol = points.maxOf { it.cumVolume }.let { if (it <= 0) 1.0 else it }
        var prevVol = 0.0
        for (i in 0 until n) {
            val p = points[i]
            val color = if (p.price >= prevClose) COLOR_UP else COLOR_DOWN

            if (i > 0) {
                linePaint.color = color
                canvas.drawLine(xOf(i - 1), yOf(points[i - 1].price), xOf(i), yOf(p.price), linePaint)
            }

            // 成交量柱（用每分钟的增量，而不是累计值）
            val deltaVol = (p.cumVolume - prevVol).coerceAtLeast(0.0)
            prevVol = p.cumVolume
            val barH = (volAreaHeight * (deltaVol / maxVol)).toFloat().coerceAtLeast(1f)
            volPaint.color = color
            val barX = xOf(i)
            canvas.drawLine(barX, volAreaTop + volAreaHeight, barX, volAreaTop + volAreaHeight - barH, volPaint.apply { strokeWidth = (chartWidth / n).coerceAtLeast(2f) })
        }

        // 最新价标注
        val last = points.last()
        val lastColor = if (last.price >= prevClose) COLOR_UP else COLOR_DOWN
        textPaint.color = lastColor
        canvas.drawText(fmt(last.price), w - rightMargin - 90f, yOf(last.price) - 10f, textPaint)
        textPaint.color = COLOR_AXIS_TEXT
    }

    private fun fmt(v: Double): String = String.format(Locale.CHINA, "%.2f", v)
}
