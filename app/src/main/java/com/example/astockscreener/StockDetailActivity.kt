package com.example.astockscreener

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 股票详情页：原生渲染，不走WebView嵌网页，所以没有广告、没有第三方推荐内容。
 * 数据来源：腾讯免费行情接口(TencentQuoteApi) —— 实时报价 + 分时 + 日K线，
 * 下方资讯列表复用已有的 NewsApi（东财公告接口）。
 */
class StockDetailActivity : AppCompatActivity() {

    private lateinit var tvPrice: TextView
    private lateinit var tvChange: TextView
    private lateinit var tvOpen: TextView
    private lateinit var tvHigh: TextView
    private lateinit var tvLow: TextView
    private lateinit var tvVolume: TextView
    private lateinit var tvPrevClose: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvDetailError: TextView
    private lateinit var minuteChart: MinuteChartView
    private lateinit var klineChart: KlineChartView
    private lateinit var btnTabMinute: TextView
    private lateinit var btnTabKline: TextView
    private lateinit var newsContainer: LinearLayout

    private lateinit var code: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_detail)

        code = intent.getStringExtra(EXTRA_CODE) ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: code

        val toolbar = findViewById<Toolbar>(R.id.toolbarDetail)
        toolbar.title = "$name ($code)"
        toolbar.setNavigationOnClickListener { finish() }

        bindViews()
        setupTabs()
        loadAll()
    }

    private fun bindViews() {
        tvPrice = findViewById(R.id.tvDetailPrice)
        tvChange = findViewById(R.id.tvDetailChange)
        tvOpen = findViewById(R.id.tvDetailOpen)
        tvHigh = findViewById(R.id.tvDetailHigh)
        tvLow = findViewById(R.id.tvDetailLow)
        tvVolume = findViewById(R.id.tvDetailVolume)
        tvPrevClose = findViewById(R.id.tvDetailPrevClose)
        progressBar = findViewById(R.id.progressDetail)
        tvDetailError = findViewById(R.id.tvDetailError)
        minuteChart = findViewById(R.id.minuteChart)
        klineChart = findViewById(R.id.klineChart)
        btnTabMinute = findViewById(R.id.btnTabMinute)
        btnTabKline = findViewById(R.id.btnTabKline)
        newsContainer = findViewById(R.id.newsContainer)
    }

    private fun setupTabs() {
        btnTabMinute.setOnClickListener { showTab(isMinute = true) }
        btnTabKline.setOnClickListener { showTab(isMinute = false) }
        showTab(isMinute = true)
    }

    private fun showTab(isMinute: Boolean) {
        minuteChart.visibility = if (isMinute) View.VISIBLE else View.GONE
        klineChart.visibility = if (isMinute) View.GONE else View.VISIBLE
        btnTabMinute.setBackgroundColor(if (isMinute) 0xFF1565C0.toInt() else Color.TRANSPARENT)
        btnTabKline.setBackgroundColor(if (!isMinute) 0xFF1565C0.toInt() else Color.TRANSPARENT)
    }

    private fun loadAll() {
        progressBar.visibility = View.VISIBLE
        tvDetailError.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val quote = withContext(Dispatchers.IO) { TencentQuoteApi.fetchRealtimeQuote(code) }
                val minutes = withContext(Dispatchers.IO) { TencentQuoteApi.fetchMinuteData(code) }
                val klines = withContext(Dispatchers.IO) { TencentQuoteApi.fetchDailyKline(code) }
                val titles = withContext(Dispatchers.IO) {
                    runCatching { NewsApi.fetchRecentTitles(code, limit = 15) }.getOrDefault(emptyList())
                }

                progressBar.visibility = View.GONE

                if (quote == null) {
                    tvDetailError.visibility = View.VISIBLE
                    tvDetailError.text = "实时行情拉取失败，可能是接口字段格式不对，或者网络问题"
                } else {
                    bindQuote(quote)
                    minuteChart.setData(minutes, quote.prevClose)
                }
                klineChart.setData(klines)
                bindNews(titles)
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                tvDetailError.visibility = View.VISIBLE
                tvDetailError.text = "加载失败：${e.message ?: "未知错误"}"
            }
        }
    }

    private fun bindQuote(q: RealtimeQuote) {
        val up = q.changePct >= 0
        val color = if (up) 0xFFFF5252.toInt() else 0xFF4CAF50.toInt()

        tvPrice.text = String.format(Locale.CHINA, "%.2f", q.price)
        tvPrice.setTextColor(color)
        tvChange.text = String.format(Locale.CHINA, "%+.2f  %+.2f%%", q.changeAmt, q.changePct)
        tvChange.setTextColor(color)
        tvOpen.text = "今开 ${fmt(q.open)}"
        tvHigh.text = "最高 ${fmt(q.high)}"
        tvLow.text = "最低 ${fmt(q.low)}"
        tvVolume.text = "成交量 ${fmtVol(q.volume)}"
        tvPrevClose.text = "昨收 ${fmt(q.prevClose)}"
    }

    private fun bindNews(titles: List<String>) {
        newsContainer.removeAllViews()
        if (titles.isEmpty()) {
            val tv = TextView(this).apply {
                text = "暂无相关资讯"
                setTextColor(0xFF888888.toInt())
                setPadding(0, 16, 0, 16)
            }
            newsContainer.addView(tv)
            return
        }
        for (title in titles) {
            val card = TextView(this).apply {
                text = "· $title"
                textSize = 14f
                setTextColor(0xFFE0E0E0.toInt())
                setPadding(20, 20, 20, 20)
                setBackgroundColor(0xFF1E1E1E.toInt())
            }
            newsContainer.addView(card)
            val spacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 4
                )
            }
            newsContainer.addView(spacer)
        }
    }

    private fun fmt(v: Double): String =
        if (v.isNaN()) "-" else String.format(Locale.CHINA, "%.2f", v)

    private fun fmtVol(v: Double): String =
        if (v.isNaN()) "-" else if (v >= 10000) String.format(Locale.CHINA, "%.1f万手", v / 10000) else "${v.toInt()}手"

    companion object {
        const val EXTRA_CODE = "extra_code"
        const val EXTRA_NAME = "extra_name"
    }
}
