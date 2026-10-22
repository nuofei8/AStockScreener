package com.example.astockscreener

/**
 * 单只股票的实时快照数据。
 * 字段全部来自东方财富 push2 实时接口（免费、无需token），
 * 市值单位已从"元"转换为"亿元"方便展示。
 */
data class StockItem(
    val code: String,
    val name: String,
    val price: Double,
    val changePct: Double,          // 今日涨跌幅(%)
    val netInflowAmount: Double,    // 主力净流入净额(元)
    val netInflowPct: Double,       // 主力净流入净占比(%)
    val circMarketCapYi: Double,    // 流通市值(亿元)
    val totalMarketCapYi: Double,   // 总市值(亿元)

    // ---- 资金强度细分（盘中持续更新，随每次自动刷新一起拿到，代表"开盘后资金强度"） ----
    val superBigNetPct: Double = Double.NaN,  // 超大单净流入净占比(%)
    val bigNetPct: Double = Double.NaN,       // 大单净流入净占比(%)

    // ---- 以下为加分项，初次拉取时为空，由 EnrichmentService 异步补充 ----
    val auctionPriceUp: Boolean? = null,       // 9:15价格 < 9:25价格；null=当天无快照数据，跳过不计分
    val auctionVolumeSurgeRatio: Double? = null, // 9:25量/9:20量（竞价量比）；null=无快照数据
    val auctionVolumeSurgeHit: Boolean? = null,
    val auctionVolume925: Double? = null,      // 9:25竞价成交量（手）；null=无快照数据
    val auctionAmountWan925: Double? = null,   // 9:25竞价成交额(万元) = 价格*成交量/10000
    val auctionVolumeHit: Boolean? = null,     // 竞价成交量是否达标
    val auctionAmountHit: Boolean? = null,     // 竞价成交额是否达标
    val superBigNetHit: Boolean? = null,       // 超大单净流入占比是否达标
    val bigNetHit: Boolean? = null,            // 大单净流入占比是否达标
    val hotSectorNames: List<String> = emptyList(), // 命中的热点板块名称
    val hotSectorHit: Boolean? = null,
    val newsTitles: List<String> = emptyList(),     // 命中关键词的公告/新闻标题
    val newsHit: Boolean? = null,
    val activityDetail: String = "",                // 换手率/振幅/涨停次数 摘要
    val activityHit: Boolean? = null,
    val conditionsMet: Int = 0,                      // 已命中的加分项数量
    val conditionsEvaluated: Int = 0                 // 实际参与评估的加分项数量（排除null项）
)
