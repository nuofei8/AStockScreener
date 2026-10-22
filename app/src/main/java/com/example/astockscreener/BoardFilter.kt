package com.example.astockscreener

/**
 * 判断股票代码是否属于主板或创业板，排除科创板(688开头)和北交所(8/4/92开头)。
 * 口径与配套的 Python 选股脚本（auction_stock_screener.py 中的
 * is_main_or_chinext_board 函数）保持一致，方便两端结果对齐。
 */
object BoardFilter {
    fun isMainOrChinext(code: String): Boolean {
        val c = code.trim()
        if (c.startsWith("688")) return false                       // 科创板，排除
        if (c.startsWith("8") || c.startsWith("4") || c.startsWith("92")) return false  // 北交所，排除
        if (c.startsWith("300") || c.startsWith("301")) return true // 创业板
        if (c.startsWith("600") || c.startsWith("601") ||
            c.startsWith("603") || c.startsWith("605")) return true // 上海主板
        if (c.startsWith("000") || c.startsWith("001") ||
            c.startsWith("002")) return true                        // 深圳主板
        return false
    }
}
