package com.example.astockscreener

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Locale

/**
 * K线图：蜡烛图 + MA5/MA10/MA20 均线 + 成交量柱 + 网格坐标，
 * 风格参照主流行情软件——红涨绿跌（A股习惯，和美股相反）。
 * 只显示最近 maxCandles 根，超出的会自动截取最新的那部分，不支持手势缩放/滑动
 * （加滑动需要GestureDetector+状态管理，这版先保证能用，以后可以再加）。
 */
class KlineChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val COLOR_UP = 0xFFFF5252.toInt()
        private const val COLOR_DOWN = 0xFF4CAF50.toInt()
        private const val COLOR_GRID = 0xFF2A2A2A.toInt()
        private const val COLOR_AXIS_TEXT = 0xFFAAAAAA.toInt()
        private const val COLOR_BG = 0xFF121212.toInt()
        private const val COLOR_MA5 = 0xFFFFC107.toInt()
        private const val COLOR_MA10 = 0xFF29B6F6.toInt()
        private const val COLOR_MA20 = 0xFFBA68C8.toInt()
        private const val MAX_CANDLES = 60
    }

    private var klines: List<KlinePoint> = emptyList()

    private val bgPaint = Paint().apply { color = COLOR_BG }
    private val gridPaint = Paint().apply { color = COLOR_GRID; strokeWidth = 1.5f }
    private val candlePaint = Paint().apply { isAntiAlias = true }
    private val wickPaint = Paint().apply { isAntiAlias = true; strokeWidth = 2.5f }
    private val volPaint = Paint().apply { isAntiAlias = true }
    private val textPaint = Paint().apply { color = COLOR_AXIS_TEXT; textSize = 26f; isAntiAlias = true }
    private val ma5Paint = Paint().apply { color = COLOR_MA5; strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val ma10Paint = Paint().apply { color = COLOR_MA10; strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val ma20Paint = Paint().apply { color = COLOR_MA20; strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }

    fun setData(klines: List<KlinePoint>) {
        this.klines = if (klines.size > MAX_CANDLES) klines.takeLast(MAX_CANDLES) else klines
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (klines.isEmpty()) {
            canvas.drawText("暂无K线数据", w / 2 - 80, h / 2, textPaint)
            return
        }

        val topMargin = 50f
        val bottomMargin = 36f
        val leftMargin = 90f
        val rightMargin = 16f
        val priceAreaHeight = (h - topMargin - bottomMargin) * 0.7f
        val volAreaTop = topMargin + priceAreaHeight + 16f
        val volAreaHeight = h - volAreaTop - bottomMargin

        val maxPrice = klines.maxOf { it.high }
        val minPrice = klines.minOf { it.low }
        val priceRange = (maxPrice - minPrice).let { if (it <= 0) 1.0 else it }

        fun yOf(price: Double): Float =
            (topMargin + priceAreaHeight * (1 - (price - minPrice) / priceRange)).toFloat()

        val n = klines.size
        val chartWidth = w - leftMargin - rightMargin
        val slotWidth = chartWidth / n
        val candleWidth = (slotWidth * 0.6f).coerceAtLeast(2f)
        fun xOf(i: Int): Float = leftMargin + slotWidth * i + slotWidth / 2

        // 网格横线：最高/中间/最低
        canvas.drawLine(leftMargin, topMargin, w - rightMargin, topMargin, gridPaint)
        canvas.drawLine(leftMargin, topMargin + priceAreaHeight / 2, w - rightMargin, topMargin + priceAreaHeight / 2, gridPaint)
        canvas.drawLine(leftMargin, topMargin + priceAreaHeight, w - rightMargin, topMargin + priceAreaHeight, gridPaint)
        canvas.drawText(fmt(maxPrice), 4f, topMargin + 10f, textPaint)
        canvas.drawText(fmt((maxPrice + minPrice) / 2), 4f, topMargin + priceAreaHeight / 2 + 10f, textPaint)
        canvas.drawText(fmt(minPrice), 4f, topMargin + priceAreaHeight, textPaint)

        // 日期标注：第一天/中间/最后一天
        canvas.drawText(klines.first().date.takeLast(5), leftMargin, h - 8f, textPaint)
        canvas.drawText(klines.last().date.takeLast(5), w - rightMargin - 90f, h - 8f, textPaint)

        val maxVol = klines.maxOf { it.volume }.let { if (it <= 0) 1.0 else it }

        // 蜡烛 + 成交量柱
        for (i in 0 until n) {
            val k = klines[i]
            val up = k.close >= k.open
            val color = if (up) COLOR_UP else COLOR_DOWN
            val x = xOf(i)

            wickPaint.color = color
            canvas.drawLine(x, yOf(k.high), x, yOf(k.low), wickPaint)

            candlePaint.color = color
            val openY = yOf(k.open)
            val closeY = yOf(k.close)
            val top = minOf(openY, closeY)
            val bottom = maxOf(openY, closeY).coerceAtLeast(top + 2f)
            canvas.drawRect(x - candleWidth / 2, top, x + candleWidth / 2, bottom, candlePaint)

            volPaint.color = color
            val barH = (volAreaHeight * (k.volume / maxVol)).toFloat().coerceAtLeast(1f)
            canvas.drawRect(
                x - candleWidth / 2, volAreaTop + volAreaHeight - barH,
                x + candleWidth / 2, volAreaTop + volAreaHeight, volPaint
            )
        }

        // MA均线
        drawMaLine(canvas, 5, ma5Paint, ::xOf, ::yOf)
        drawMaLine(canvas, 10, ma10Paint, ::xOf, ::yOf)
        drawMaLine(canvas, 20, ma20Paint, ::xOf, ::yOf)

        // 图例
        var legendX = leftMargin
        legendX = drawLegend(canvas, legendX, "MA5", COLOR_MA5)
        legendX = drawLegend(canvas, legendX, "MA10", COLOR_MA10)
        drawLegend(canvas, legendX, "MA20", COLOR_MA20)
    }

    private fun drawMaLine(
        canvas: Canvas, period: Int, paint: Paint,
        xOf: (Int) -> Float, yOf: (Double) -> Float
    ) {
        if (klines.size < period) return
        var prevX = 0f
        var prevY = 0f
        var hasPrev = false
        for (i in period - 1 until klines.size) {
            val avg = klines.subList(i - period + 1, i + 1).sumOf { it.close } / period
            val x = xOf(i)
            val y = yOf(avg)
            if (hasPrev) canvas.drawLine(prevX, prevY, x, y, paint)
            prevX = x; prevY = y; hasPrev = true
        }
    }

    private fun drawLegend(canvas: Canvas, startX: Float, label: String, color: Int): Float {
        val paint = Paint().apply { this.color = color; strokeWidth = 4f }
        canvas.drawLine(startX, 28f, startX + 24f, 28f, paint)
        textPaint.color = COLOR_AXIS_TEXT
        canvas.drawText(label, startX + 30f, 34f, textPaint)
        return startX + 30f + textPaint.measureText(label) + 20f
    }

    private fun fmt(v: Double): String = String.format(Locale.CHINA, "%.2f", v)
}
