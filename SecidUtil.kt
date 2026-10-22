package com.example.astockscreener

/**
 * 东方财富很多接口要求传"市场代码.股票代码"格式的 secid，
 * 市场代码：0=深圳(含创业板/深主板)，1=上海(含主板/科创板)。
 */
object SecidUtil {
    fun toSecid(code: String): String {
        val market = if (code.startsWith("6")) "1" else "0"
        return "$market.$code"
    }
}
