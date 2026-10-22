package com.example.astockscreener

/**
 * 对全市场快照应用选股画像过滤，并按资金驱动强度排序。
 * 所有阈值和开关都来自 ScreenerConfig（可在设置页面调整）。
 */
object ScreenerRepository {

    /**
     * 过滤条件（全部满足才入选）：
     *   1. 现价 <= MAX_PRICE
     *   2. 仅主板 + 创业板（可在设置里关闭这个限制）
     *   3. 流通市值 <= MAX_CIRC_MARKET_CAP_YI
     *   4. 主力净流入占比 >= MIN_NET_INFLOW_PCT
     *   5.（可选）超大单净流入占比 >= MIN_SUPER_BIG_NET_PCT
     *   6.（可选）大单净流入占比 >= MIN_BIG_NET_PCT
     * 这里的5、6两项代表"开盘后持续的资金强度"——因为这份数据本身就是当日累计、
     * 每次自动刷新都会更新，不需要像竞价那样额外做定时快照。
     * 结果按净流入占比降序排列（而非净流入绝对金额，避免大盘股垄断排名）。
     */
    fun filterAndRank(all: List<StockItem>): List<StockItem> {
        return all.asSequence()
            .filter { !it.price.isNaN() && it.price <= ScreenerConfig.MAX_PRICE }
            .filter { !ScreenerConfig.RESTRICT_TO_MAIN_AND_CHINEXT || BoardFilter.isMainOrChinext(it.code) }
            .filter {
                !it.circMarketCapYi.isNaN() &&
                    it.circMarketCapYi <= ScreenerConfig.MAX_CIRC_MARKET_CAP_YI
            }
            .filter {
                !it.netInflowPct.isNaN() &&
                    it.netInflowPct >= ScreenerConfig.MIN_NET_INFLOW_PCT
            }
            .filter {
                !ScreenerConfig.ENABLE_SUPER_BIG_CHECK ||
                    (!it.superBigNetPct.isNaN() && it.superBigNetPct >= ScreenerConfig.MIN_SUPER_BIG_NET_PCT)
            }
            .filter {
                !ScreenerConfig.ENABLE_BIG_CHECK ||
                    (!it.bigNetPct.isNaN() && it.bigNetPct >= ScreenerConfig.MIN_BIG_NET_PCT)
            }
            .sortedByDescending { it.netInflowPct }
            .toList()
    }
}
