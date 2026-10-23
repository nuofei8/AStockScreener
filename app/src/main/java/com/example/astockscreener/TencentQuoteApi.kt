package com.example.astockscreener

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

data class RealtimeQuote(
    val name: String,
    val price: Double,
    val prevClose: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val volume: Double,       // 成交量(手)
    val changeAmt: Double,
    val changePct: Double
)

data class MinutePoint(val timeHHMM: String, val price: Double, val cumVolume: Double)

data class KlinePoint(
    val date: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double
)

/**
 * 腾讯免费行情接口（qt.gtimg.cn / ifzq.gtimg.cn），不需要token，App内直接解析渲染，
 * 不是嵌网页，天然没有广告、没有东财自己的推荐内容。
 *
 * ⚠️ 和本项目其他接口一样的提醒：这几个接口格式是按社区公开资料整理的，
 * 没有联网验证过，东西能不能跑起来、字段对不对得上，需要你实测后反馈，
 * 字段顺序错了的话对照下面每个函数的注释改下标就行。
 */
object TencentQuoteApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** 代码转腾讯格式："sh600000" / "sz000001"，6开头(含688科创板)是沪市，其余是深市 */
    private fun toTencentCode(code: String): String {
        val prefix = if (code.startsWith("6")) "sh" else "sz"
        return "$prefix$code"
    }

    /**
     * 实时行情快照。腾讯这个接口返回的是 GBK 编码的纯文本，不是JSON，
     * 格式大致是：v_sz000001="51~平安银行~000001~现价~昨收~今开~成交量~...~最高~最低~...";
     * 用 "~" 分割后按下标取值，具体下标是社区公开资料整理的，不保证每个版本都对。
     */
    fun fetchRealtimeQuote(code: String): RealtimeQuote? {
        val tCode = toTencentCode(code)
        val url = "https://qt.gtimg.cn/q=$tCode"
        return try {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile) AStockScreener/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                val text = String(bytes, Charset.forName("GBK"))
                val content = text.substringAfter("\"", "").substringBeforeLast("\"", "")
                if (content.isEmpty()) return null
                val parts = content.split("~")
                if (parts.size < 35) return null

                RealtimeQuote(
                    name = parts.getOrElse(1) { "" },
                    price = parts.getOrElse(3) { "" }.toDoubleOrNull() ?: Double.NaN,
                    prevClose = parts.getOrElse(4) { "" }.toDoubleOrNull() ?: Double.NaN,
                    open = parts.getOrElse(5) { "" }.toDoubleOrNull() ?: Double.NaN,
                    volume = parts.getOrElse(6) { "" }.toDoubleOrNull() ?: Double.NaN,
                    high = parts.getOrElse(33) { "" }.toDoubleOrNull() ?: Double.NaN,
                    low = parts.getOrElse(34) { "" }.toDoubleOrNull() ?: Double.NaN,
                    changeAmt = parts.getOrElse(31) { "" }.toDoubleOrNull() ?: Double.NaN,
                    changePct = parts.getOrElse(32) { "" }.toDoubleOrNull() ?: Double.NaN
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 今日分时数据：每分钟一个点，格式 "HHMM 价格 累计成交量"，空格分隔。
     * 返回JSON结构大致是 data.data.<code>.data.data: ["0930 11.01 1223", ...]
     */
    fun fetchMinuteData(code: String): List<MinutePoint> {
        val tCode = toTencentCode(code)
        val url = "https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=$tCode"
        return try {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile) AStockScreener/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val root = JSONObject(body)
                val data = root.optJSONObject("data")?.optJSONObject(tCode)
                    ?.optJSONObject("data")?.optJSONArray("data") ?: return emptyList()

                val result = mutableListOf<MinutePoint>()
                for (i in 0 until data.length()) {
                    val line = data.optString(i) ?: continue
                    val f = line.split(" ")
                    if (f.size < 3) continue
                    val price = f[1].toDoubleOrNull() ?: continue
                    val vol = f[2].toDoubleOrNull() ?: continue
                    result.add(MinutePoint(f[0], price, vol))
                }
                result
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 日K线数据，默认取最近120根。
     * 返回JSON结构大致是 data.data.<code>.qfqday 或 .day: [["日期","开","收","高","低","量"], ...]
     */
    fun fetchDailyKline(code: String, count: Int = 120): List<KlinePoint> {
        val tCode = toTencentCode(code)
        val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get" +
            "?param=$tCode,day,,,${count},qfq"
        return try {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile) AStockScreener/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val root = JSONObject(body)
                val stockData = root.optJSONObject("data")?.optJSONObject(tCode) ?: return emptyList()
                // 优先用前复权数据(qfqday)，没有的话退回不复权(day)
                val arr = stockData.optJSONArray("qfqday") ?: stockData.optJSONArray("day")
                    ?: return emptyList()

                val result = mutableListOf<KlinePoint>()
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONArray(i) ?: continue
                    if (row.length() < 6) continue
                    val date = row.optString(0)
                    val open = row.optString(1).toDoubleOrNull() ?: continue
                    val close = row.optString(2).toDoubleOrNull() ?: continue
                    val high = row.optString(3).toDoubleOrNull() ?: continue
                    val low = row.optString(4).toDoubleOrNull() ?: continue
                    val vol = row.optString(5).toDoubleOrNull() ?: 0.0
                    result.add(KlinePoint(date, open, close, high, low, vol))
                }
                result
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
