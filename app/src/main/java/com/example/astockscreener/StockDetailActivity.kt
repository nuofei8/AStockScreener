package com.example.astockscreener

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

/**
 * 股票详情页：不自己画分时图/K线图（那需要额外的图表库+逐股K线数据接口，
 * 风险和工作量都更大），而是直接用 WebView 加载东方财富自己的手机行情页，
 * 该页面本身就自带分时图、K线图、买卖盘等完整信息，加载的是东财官方页面，
 * 图表渲染的准确性由东财自己保证，比自己拼接图表数据更可靠。
 *
 * ⚠️ 这个页面URL格式（wap.eastmoney.com/quote/stock/<市场>.<代码>.html）
 * 没有联网验证过，如果打开后显示404或者页面不对，大概率是URL格式变了，
 * 可以先用手机浏览器直接访问这个链接确认一下实际地址应该长什么样。
 */
class StockDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_detail)

        val code = intent.getStringExtra(EXTRA_CODE) ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: code

        val toolbar = findViewById<Toolbar>(R.id.toolbarDetail)
        toolbar.title = "$name ($code)"
        toolbar.setNavigationOnClickListener { finish() }

        val progressBar = findViewById<ProgressBar>(R.id.progressDetail)
        val tvDetailError = findViewById<TextView>(R.id.tvDetailError)
        val webView = findViewById<WebView>(R.id.webViewDetail)

        setupWebView(webView, progressBar, tvDetailError)

        val secid = SecidUtil.toSecid(code)
        val url = "https://wap.eastmoney.com/quote/stock/$secid.html"
        webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView, progressBar: ProgressBar, tvDetailError: TextView) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                progressBar.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    progressBar.visibility = View.GONE
                    tvDetailError.visibility = View.VISIBLE
                    tvDetailError.text = "页面加载失败：${error.description}\n" +
                        "可能是URL格式不对，或者网络问题，用手机浏览器直接打开试试"
                }
            }
        }
    }

    companion object {
        const val EXTRA_CODE = "extra_code"
        const val EXTRA_NAME = "extra_name"
    }
}
