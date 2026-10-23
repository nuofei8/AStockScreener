package com.example.astockscreener

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.Locale
import kotlin.math.roundToInt

/**
 * K线图：蜡烛图 + MA5/MA10/MA20 均线 + 成交量柱 + 网格坐标 + 手势交互：
 *   - 左右拖动：翻看历史K线（不是只能看最近60根了，全部数据都能滑到）
 *   - 双指缩放：调整一屏显示多少根K线（放大看细节/缩小看全局）
 *   - 按住不放：十字光标，顶部信息条显示那根K线的日期/开/高/低/收/量
 *
 * ⚠️ 拖动方向这种手感细节没有真机测试过，如果感觉"向左滑却往新的方向走"
 * 这种反直觉的情况，去 onScroll 里把 distanceX 前面的符号反一下就行。
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
        private const val COLOR_CROSSHAIR = 0xFFEEEEEE.toInt()
        private const val MIN_VISIBLE = 20
        private const val DEFAULT_VISIBLE = 60
    }

    private var allKlines: List<KlinePoint> = emptyList()
    private var visibleCount = DEFAULT_VISIBLE
    private var scrollOffsetFromEnd = 0 // 0=显示到最新一根；越大越往历史翻
    private var crosshairIndex = -1     // -1 = 不显示十字光标

    private val bgPaint = Paint().apply { color = COLOR_BG }
    private val gridPaint = Paint().apply { color = COLOR_GRID; strokeWidth = 1.5f }
    private val candlePaint = Paint().apply { isAntiAlias = true }
    private val wickPaint = Paint().apply { isAntiAlias = true; strokeWidth = 2.5f }
    private val volPaint = Paint().apply { isAntiAlias = true }
    private val textPaint = Paint().apply { color = COLOR_AXIS_TEXT; textSize = 26f; isAntiAlias = true }
    private val infoPaint = Paint().apply { color = COLOR_CROSSHAIR; textSize = 26f; isAntiAlias = true }
    private val ma5Paint = Paint().apply { color = COLOR_MA5; strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val ma10Paint = Paint().apply { color = COLOR_MA10; strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val ma20Paint = Paint().apply { color = COLOR_MA20; strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val crosshairPaint = Paint().apply {
        color = COLOR_CROSSHAIR
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newCount = (visibleCount / detector.scaleFactor).roundToInt()
            visibleCount = newCount.coerceIn(MIN_VISIBLE, allKlines.size.coerceAtLeast(MIN_VISIBLE))
            clampScrollOffset()
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val slotWidth = width.toFloat() / visibleCount.coerceAtLeast(1)
            if (slotWidth <= 0) return false
            scrollOffsetFromEnd += (distanceX / slotWidth).roundToInt()
            clampScrollOffset()
            invalidate()
            return true
        }

        override fun onDown(e: MotionEvent): Boolean = true
    })

    init {
        isClickable = true
    }

    fun setData(klines: List<KlinePoint>) {
        this.allKlines = klines
        visibleCount = DEFAULT_VISIBLE.coerceAtMost(klines.size.coerceAtLeast(1))
        scrollOffsetFromEnd = 0
        crosshairIndex = -1
        invalidate()
    }

    private fun clampScrollOffset() {
        val maxOffset = (allKlines.size - visibleCount).coerceAtLeast(0)
        scrollOffsetFromEnd = scrollOffsetFromEnd.coerceIn(0, maxOffset)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)

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
        val displayed = currentSlice()
        if (displayed.isEmpty()) return
        val leftMargin = 90f
        val rightMargin = 16f
        val chartWidth = width - leftMargin - rightMargin
        if (chartWidth <= 0) return
        val slotWidth = chartWidth / displayed.size
        var idx = ((touchX - leftMargin) / slotWidth).toInt()
        idx = idx.coerceIn(0, displayed.size - 1)
        crosshairIndex = idx
        invalidate()
    }

    private fun currentSlice(): List<KlinePoint> {
        if (allKlines.isEmpty()) return emptyList()
        val vc = visibleCount.coerceAtMost(allKlines.size)
        val endExclusive = (allKlines.size - scrollOffsetFromEnd).coerceIn(vc, allKlines.size)
        val start = (endExclusive - vc).coerceAtLeast(0)
        return allKlines.subList(start, endExclusive)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val klines = currentSlice()
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

        canvas.drawLine(leftMargin, topMargin, w - rightMargin, topMargin, gridPaint)
        canvas.drawLine(leftMargin, topMargin + priceAreaHeight / 2, w - rightMargin, topMargin + priceAreaHeight / 2, gridPaint)
        canvas.drawLine(leftMargin, topMargin + priceAreaHeight, w - rightMargin, topMargin + priceAreaHeight, gridPaint)
        canvas.drawText(fmt(maxPrice), 4f, topMargin + 10f, textPaint)
        canvas.drawText(fmt((maxPrice + minPrice) / 2), 4f, topMargin + priceAreaHeight / 2 + 10f, textPaint)
        canvas.drawText(fmt(minPrice), 4f, topMargin + priceAreaHeight, textPaint)

        canvas.drawText(klines.first().date.takeLast(5), leftMargin, h - 8f, textPaint)
        canvas.drawText(klines.last().date.takeLast(5), w - rightMargin - 90f, h - 8f, textPaint)

        val maxVol = klines.maxOf { it.volume }.let { if (it <= 0) 1.0 else it }

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

        drawMaLine(canvas, klines, 5, ma5Paint, ::xOf, ::yOf)
        drawMaLine(canvas, klines, 10, ma10Paint, ::xOf, ::yOf)
        drawMaLine(canvas, klines, 20, ma20Paint, ::xOf, ::yOf)

        var legendX = leftMargin
        legendX = drawLegend(canvas, legendX, "MA5", COLOR_MA5)
        legendX = drawLegend(canvas, legendX, "MA10", COLOR_MA10)
        drawLegend(canvas, legendX, "MA20", COLOR_MA20)

        // 十字光标 + 信息条
        if (crosshairIndex in klines.indices) {
            val k = klines[crosshairIndex]
            val x = xOf(crosshairIndex)
            canvas.drawLine(x, topMargin, x, volAreaTop + volAreaHeight, crosshairPaint)
            canvas.drawLine(leftMargin, yOf(k.close), w - rightMargin, yOf(k.close), crosshairPaint)

            val info = String.format(
                Locale.CHINA, "%s 开%.2f 高%.2f 低%.2f 收%.2f 量%s",
                k.date, k.open, k.high, k.low, k.close, fmtVol(k.volume)
            )
            canvas.drawRect(0f, 0f, w, 44f, Paint().apply { color = 0xCC000000.toInt() })
            canvas.drawText(info, 8f, 30f, infoPaint)
        }
    }

    private fun drawMaLine(
        canvas: Canvas, klines: List<KlinePoint>, period: Int, paint: Paint,
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
    private fun fmtVol(v: Double): String =
        if (v >= 10000) String.format(Locale.CHINA, "%.1f万", v / 10000) else v.toInt().toString()
}
