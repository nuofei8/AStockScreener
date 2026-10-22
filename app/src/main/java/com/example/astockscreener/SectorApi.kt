package com.example.astockscreener

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 热点题材判断：
 *   1. fetchHotSectorNames() 拉取"概念板块"当日涨幅排名前N的板块名称
 *   2. fetchStockSectorName(code) 拉取个股所属行业名称
 *   3. 两者按名称做简单包含匹配
 *
 * ⚠️ 字段未验证，和 EastmoneyApi 一样的提醒：
 *   - fs=m:90+t:3 是社区常见的"概念板块"分类代码，不是官方文档，可能不准
 *   - f127 是否真的等于"所属行业"也没有实测验证过
 *   - "行业" 和 "概念板块" 在东财是两套不同的分类体系，用行业名去匹配概念板块名
 *     未必能完全对上（比如个股属于"锂电池"概念，但所属行业可能显示"电气设备"），
 *     这是一个粗略近似，不是精确匹配，如果命中率太低/太高，检查这里的字段和匹配逻辑
 */
object SectorApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** 拉取概念板块当日涨幅排名前 topN 的板块名称 */
    fun fetchHotSectorNames(topN: Int): List<String> {
        val url = "https://push2.eastmoney.com/api/qt/clist/get" +
            "?pn=1&pz=$topN&po=1&np=1&fltt=2&invt=2&fid=f3" +
            "&fs=m:90+t:3" +
            "&fields=f12,f14,f3"
        return try {
            val body = httpGet(url)
            val diff = JSONObject(body).optJSONObject("data")?.optJSONArray("diff") ?: return emptyList()
            val result = mutableListOf<String>()
            for (i in 0 until diff.length()) {
                val name = diff.optJSONObject(i)?.optString("f14") ?: continue
                if (name.isNotEmpty()) result.add(name)
            }
            result
        } catch (e: Exception) {
            emptyList() // 拉取失败不影响主流程，当天就没有题材加分
        }
    }

    /** 拉取单只股票所属行业名称（作为题材归属的近似） */
    fun fetchStockSectorName(code: String): String? {
        val secid = SecidUtil.toSecid(code)
        val url = "https://push2.eastmoney.com/api/qt/stock/get" +
            "?secid=$secid&fields=f127"
        return try {
            val body = httpGet(url)
            val data = JSONObject(body).optJSONObject("data") ?: return null
            val industry = data.optString("f127")
            industry.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile) AStockScreener/1.0")
            .header("Referer", "https://quote.eastmoney.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw java.io.IOException("响应为空")
        }
    }
}
