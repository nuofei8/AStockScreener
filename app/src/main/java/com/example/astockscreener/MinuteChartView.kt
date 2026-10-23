package com.example.astockscreener

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale

/**
 * 分时图：价格曲线按涨跌逐段染色（红涨绿跌）、昨收虚线参考线、底部成交量柱，
 * 支持按住不放显示十字光标，顶部信息条显示那一分钟的时间/价格/成交量。
 * 分时图是一天的固定数据，不需要像K线那样缩放/翻页，只做触摸定位。
 */
class MinuteChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val COLOR_UP = 0xFFFF5252.toInt()
        private const val COLOR_DOWN = 0xFF4CAF50.toInt()
        private const val COLOR_GRID = 0xFF2A2A2A.toInt()
        private const val COLOR_AXIS_TEXT = 0xFFAAAAAA.toInt()
        private const val COLOR_REF_LINE = 0xFFBBBBBB.toInt()
        private const val COLOR_BG = 0xFF121212.toInt()
        private const val COLOR_CROSSHAIR = 0xFFEEEEEE.toInt()
    }

    private var points: List<MinutePoint> = emptyList()
    private var prevClose: Double = Double.NaN
    private var crosshairIndex = -1

    private val bgPaint = Paint().apply { color = COLOR_BG }
    private val gridPaint = Paint().apply { color = COLOR_GRID; strokeWidth = 1.5f }
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
    private val textPaint = Paint().apply { color = COLOR_AXIS_TEXT; textSize = 28f; isAntiAlias = true }
    private val infoPaint = Paint().apply { color = COLOR_CROSSHAIR; textSize = 26f; isAntiAlias = true }
    private val crosshairPaint = Paint().apply {
        color = COLOR_CROSSHAIR
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val infoBgPaint = Paint().apply { color = 0xCC000000.toInt() }

    init {
        isClickable = true
    }

    fun setData(points: List<MinutePoint>, prevClose: Double) {
        this.points = points
        this.prevClose = prevClose
        this.crosshairIndex = -1
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                updateCrosshair(event.x)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                crosshairIndex = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }

    private fun updateCrosshair(touchX: Float) {
        if (points.isEmpty()) return
        val leftMargin = 90f
        val rightMargin = 16f
        val chartWidth = width - leftMargin - rightMargin
        if (chartWidth <= 0) return
        val n = points.size
        var idx = if (n <= 1) 0 else ((touchX - leftMargin) / chartWidth * (n - 1)).toInt()
        idx = idx.coerceIn(0, n - 1)
        crosshairIndex = idx
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
        val paddedMax = maxPrice + priceRange * 0.08
        val paddedMin = minPrice - priceRange * 0.08
        val paddedRange = (paddedMax - paddedMin).let { if (it <= 0) 1.0 else it }

        fun yOf(price: Double): Float {
            return (topMargin + priceAreaHeight * (1 - ((price - paddedMin) / paddedRange))).toFloat()
        }

        val chartWidth = w - leftMargin - rightMargin
        val n = points.size
        fun xOf(i: Int): Float = leftMargin + if (n <= 1) 0f else chartWidth * i / (n - 1).toFloat()

        canvas.drawLine(leftMargin, topMargin, w - rightMargin, topMargin, gridPaint)
        canvas.drawLine(leftMargin, topMargin + priceAreaHeight, w - rightMargin, topMargin + priceAreaHeight, gridPaint)
        val refY = yOf(prevClose)
        canvas.drawLine(leftMargin, refY, w - rightMargin, refY, refLinePaint)

        canvas.drawText(fmt(paddedMax), 4f, topMargin + 10f, textPaint)
        canvas.drawText(fmt(prevClose), 4f, refY + 10f, textPaint)
        canvas.drawText(fmt(paddedMin), 4f, topMargin + priceAreaHeight, textPaint)

        val maxVol = points.maxOf { it.cumVolume }.let { if (it <= 0) 1.0 else it }
        var prevVol = 0.0
        for (i in 0 until n) {
            val p = points[i]
            val color = if (p.price >= prevClose) COLOR_UP else COLOR_DOWN

            if (i > 0) {
                linePaint.color = color
                canvas.drawLine(xOf(i - 1), yOf(points[i - 1].price), xOf(i), yOf(p.price), linePaint)
            }

            val deltaVol = (p.cumVolume - prevVol).coerceAtLeast(0.0)
            prevVol = p.cumVolume
            val barH = (volAreaHeight * (deltaVol / maxVol)).toFloat().coerceAtLeast(1f)
            volPaint.color = color
            volPaint.strokeWidth = (chartWidth / n).coerceAtLeast(2f)
            val barX = xOf(i)
            canvas.drawLine(barX, volAreaTop + volAreaHeight, barX, volAreaTop + volAreaHeight - barH, volPaint)
        }

        val last = points.last()
        val lastColor = if (last.price >= prevClose) COLOR_UP else COLOR_DOWN
        textPaint.color = lastColor
        canvas.drawText(fmt(last.price), w - rightMargin - 90f, yOf(last.price) - 10f, textPaint)
        textPaint.color = COLOR_AXIS_TEXT

        // 十字光标 + 信息条
        if (crosshairIndex in points.indices) {
            val p = points[crosshairIndex]
            val x = xOf(crosshairIndex)
            canvas.drawLine(x, topMargin, x, volAreaTop + volAreaHeight, crosshairPaint)
            canvas.drawLine(leftMargin, yOf(p.price), w - rightMargin, yOf(p.price), crosshairPaint)

            val changePct = (p.price - prevClose) / prevClose * 100
            val info = String.format(
                Locale.CHINA, "%s  价%.2f  %+.2f%%  量%s",
                formatTime(p.timeHHMM), p.price, changePct, fmtVol(p.cumVolume)
            )
            canvas.drawRect(0f, 0f, w, 44f, infoBgPaint)
            canvas.drawText(info, 8f, 30f, infoPaint)
        }
    }

    private fun formatTime(hhmm: String): String =
        if (hhmm.length == 4) "${hhmm.substring(0, 2)}:${hhmm.substring(2)}" else hhmm

    private fun fmt(v: Double): String = String.format(Locale.CHINA, "%.2f", v)
    private fun fmtVol(v: Double): String =
        if (v >= 10000) String.format(Locale.CHINA, "%.1f万手", v / 10000) else "${v.toInt()}手"
}
