package com.example.astockscreener

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 利好消息共振判断：拉取个股最近公告标题，匹配利好关键词。
 *
 * ⚠️ 字段/接口未验证：这个公告接口是社区常见用法整理的，不是官方文档。
 *   如果请求失败或者解析不到数据，第一步同样建议浏览器直接打开URL看返回内容，
 *   对照实际JSON结构调整下面的 data/list/title 这几层取值路径。
 *
 * 另外要提醒：只做标题关键词匹配，命中不代表真的是利好，也可能是"利空出尽""澄清公告"
 * 这类反而是坏消息的标题里也会出现这些词，命中的标题务必自己点进去确认内容。
 */
object NewsApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun fetchRecentTitles(code: String, limit: Int = 5): List<String> {
        val url = "https://np-anotice-stock.eastmoney.com/api/security/ann" +
            "?sr=-1&page_size=$limit&page_index=1&ann_type=A" +
            "&client_source=web&stock_list=$code&f_node=0&s_node=0"
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile) AStockScreener/1.0")
                .header("Referer", "https://data.eastmoney.com/")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val list = JSONObject(body).optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
                val titles = mutableListOf<String>()
                for (i in 0 until list.length()) {
                    val title = list.optJSONObject(i)?.optString("title") ?: continue
                    if (title.isNotEmpty()) titles.add(title)
                }
                titles
            }
        } catch (e: Exception) {
            emptyList() // 拉取失败不影响主流程，这项就当天没有消息加分
        }
    }

    fun matchKeywords(titles: List<String>, keywords: List<String>): List<String> {
        return titles.filter { title -> keywords.any { kw -> title.contains(kw) } }
    }
}
