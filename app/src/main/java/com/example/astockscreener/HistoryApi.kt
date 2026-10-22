package com.example.astockscreener

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ActivityStats(
    val avgTurnoverRate: Double,
    val avgAmplitude: Double,
    val limitUpTimes: Int
) {
    fun isActive(): Boolean =
        avgTurnoverRate >= ScreenerConfig.MIN_AVG_TURNOVER_RATE ||
            avgAmplitude >= ScreenerConfig.MIN_AVG_AMPLITUDE ||
            limitUpTimes >= ScreenerConfig.MIN_LIMIT_UP_TIMES

    fun summary(): String =
        "日均换手%.1f%% 日均振幅%.1f%% 近一年涨停%d次".format(avgTurnoverRate, avgAmplitude, limitUpTimes)
}

/**
 * 近一年股性活跃度判断：拉取日K线历史数据计算。
 *
 * ⚠️ 字段未验证：klines 每条是逗号分隔的字符串，这里按社区公开资料假定顺序为
 *   日期,开盘,收盘,最高,最低,成交量,成交额,振幅,涨跌幅,涨跌额,换手率
 *   如果算出来的换手率/振幅数值明显不对（比如振幅几百%），
 *   大概率是这个顺序或下标错了，打印一条原始 klines[0] 字符串出来对照着数一下逗号位置即可。
 */
object HistoryApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun fetchActivityStats(code: String): ActivityStats? {
        val secid = SecidUtil.toSecid(code)
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
            "?secid=$secid" +
            "&fields1=f1,f2,f3,f4,f5,f6" +
            "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61" +
            "&klt=101&fqt=1&beg=0&end=20500101&lmt=${ScreenerConfig.ACTIVITY_LOOKBACK_DAYS}"

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile) AStockScreener/1.0")
                .header("Referer", "https://quote.eastmoney.com/")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val klines = JSONObject(body).optJSONObject("data")?.optJSONArray("klines") ?: return null
                if (klines.length() == 0) return null

                var turnoverSum = 0.0
                var amplitudeSum = 0.0
                var limitUpCount = 0
                var validRows = 0

                for (i in 0 until klines.length()) {
                    val row = klines.optString(i) ?: continue
                    val parts = row.split(",")
                    // index: 0日期 1开盘 2收盘 3最高 4最低 5成交量 6成交额 7振幅 8涨跌幅 9涨跌额 10换手率
                    if (parts.size < 11) continue
                    val amplitude = parts[7].toDoubleOrNull() ?: continue
                    val changePct = parts[8].toDoubleOrNull() ?: continue
                    val turnover = parts[10].toDoubleOrNull() ?: continue

                    turnoverSum += turnover
                    amplitudeSum += amplitude
                    if (changePct >= 9.5) limitUpCount++
                    validRows++
                }

                if (validRows == 0) return null

                ActivityStats(
                    avgTurnoverRate = turnoverSum / validRows,
                    avgAmplitude = amplitudeSum / validRows,
                    limitUpTimes = limitUpCount
                )
            }
        } catch (e: Exception) {
            null // 拉取失败不影响主流程，这项就当天没有活跃度加分
        }
    }
}
