package com.example.astockscreener

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 东方财富 push2 实时行情/资金流接口（免费、无需 token，网页版行情就是走这个接口）。
 *
 * ⚠️ 重要提醒：
 *   - 这是免费公开接口，不是官方授权的商业数据服务，字段含义基于社区公开资料整理，
 *     东财随时可能调整字段或限流策略，拿到的数据如果不对、或者请求报错，
 *     第一步建议用电脑浏览器直接打开下面这个URL看看返回的JSON长什么样，
 *     对照字段再调整代码里的 f12/f14/f2/f3/f20/f21/f62/f184 这些字段号。
 *   - 东财按来源IP限流，并会临时封禁突发请求，所以 ScreenerConfig.POLL_INTERVAL_MS
 *     不要调太小，出现请求失败时也不要无脑重试刷接口。
 *   - 这是"准实时"快照，不保证固定延迟，行情剧烈波动、访问量大时延迟会变大。
 */
object EastmoneyApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true) // 弱网下连接中断自动重试一次（OkHttp内置机制）
        .build()

    /**
     * 东财一次性返回全市场几千只股票数据，包体比较大，手机网络（尤其4G/5G切换、
     * 信号不稳）下容易出现 "unexpected end of stream"（传输中途断开）。
     * 这里包一层简单重试：失败后间隔重试，最多尝试 maxAttempts 次。
     */
    private inline fun <T> withRetry(maxAttempts: Int = 3, block: () -> T): T {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts - 1) {
                    Thread.sleep(800L * (attempt + 1)) // 重试间隔递增：0.8s / 1.6s
                }
            }
        }
        throw lastError ?: IOException("未知错误")
    }

    // fs 参数：m:0 t:6 深圳主板，m:0 t:80 创业板，m:1 t:2 上海主板，m:1 t:23 科创板。
    // 科创板数据也会被拉回来，但客户端用 BoardFilter 过滤掉，不影响结果。
    // fields 字段号含义：
    //   f12=代码 f14=名称 f2=最新价 f3=涨跌幅(%)
    //   f20=总市值(元) f21=流通市值(元)
    //   f62=主力净流入净额(元) f184=主力净流入净占比(%)
    //   f69=超大单净流入净占比(%) f75=大单净流入净占比(%) —— 这两项是"资金强度"细分，
    //   每次自动刷新都会重新拉取，天然就是"开盘后持续监测"，不需要额外的定时机制
    private const val URL =
        "https://push2.eastmoney.com/api/qt/clist/get" +
            "?pn=1&pz=6000&po=1&np=1&fltt=2&invt=2&fid=f184" +
            "&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23" +
            "&fields=f12,f14,f2,f3,f20,f21,f62,f184,f69,f75"

    @Throws(IOException::class)
    fun fetchFundFlowSnapshot(): List<StockItem> = withRetry {
        val request = Request.Builder()
            .url(URL)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile) AStockScreener/1.0")
            .header("Referer", "https://quote.eastmoney.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}，可能被限流，稍后重试")
            }
            val body = response.body?.string()
                ?: throw IOException("响应为空")

            val root = JSONObject(body)
            val data = root.optJSONObject("data")
                ?: throw IOException("data字段缺失，接口格式可能变了，请对照返回内容检查字段号")
            val diff = data.optJSONArray("diff") ?: return emptyList()

            val result = mutableListOf<StockItem>()
            for (i in 0 until diff.length()) {
                val item = diff.optJSONObject(i) ?: continue
                val code = item.optString("f12")
                val price = item.optDouble("f2", Double.NaN)
                if (code.isEmpty() || price.isNaN()) continue

                result.add(
                    StockItem(
                        code = code,
                        name = item.optString("f14"),
                        price = price,
                        changePct = item.optDouble("f3", Double.NaN),
                        netInflowAmount = item.optDouble("f62", Double.NaN),
                        netInflowPct = item.optDouble("f184", Double.NaN),
                        totalMarketCapYi = item.optDouble("f20", Double.NaN)
                            .let { if (it.isNaN()) Double.NaN else it / 1e8 },
                        circMarketCapYi = item.optDouble("f21", Double.NaN)
                            .let { if (it.isNaN()) Double.NaN else it / 1e8 },
                        superBigNetPct = item.optDouble("f69", Double.NaN),
                        bigNetPct = item.optDouble("f75", Double.NaN)
                    )
                )
            }
            return result
        }
    }

    // fields: f12=代码 f2=最新价 f5=成交量（集合竞价快照只需要这几个字段，减小请求体积）
    private const val SNAPSHOT_URL =
        "https://push2.eastmoney.com/api/qt/clist/get" +
            "?pn=1&pz=6000&po=1&np=1&fltt=2&invt=2&fid=f3" +
            "&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23" +
            "&fields=f12,f2,f5"

    /**
     * 拉取全市场"代码->（价格，成交量）"的轻量快照，专供集合竞价条件使用。
     * 用小字段集合，减少请求体积和解析耗时。
     */
    @Throws(IOException::class)
    fun fetchPriceVolumeSnapshot(): Map<String, Pair<Double, Double>> = withRetry {
        val request = Request.Builder()
            .url(SNAPSHOT_URL)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile) AStockScreener/1.0")
            .header("Referer", "https://quote.eastmoney.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("响应为空")
            val diff = JSONObject(body).optJSONObject("data")?.optJSONArray("diff") ?: return emptyMap()

            val result = mutableMapOf<String, Pair<Double, Double>>()
            for (i in 0 until diff.length()) {
                val item = diff.optJSONObject(i) ?: continue
                val code = item.optString("f12")
                val price = item.optDouble("f2", Double.NaN)
                val volume = item.optDouble("f5", Double.NaN)
                if (code.isEmpty() || price.isNaN() || volume.isNaN()) continue
                result[code] = price to volume
            }
            return result
        }
    }
}
